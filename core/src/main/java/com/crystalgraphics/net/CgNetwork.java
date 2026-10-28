package com.crystalgraphics.net;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.collision.debug.CgCollisionShow;
import com.crystalgraphics.frame.CgFrameBoundary;
import com.crystalgraphics.cue.CgCueNet;
import com.crystalgraphics.tag.CgTagNet;
import com.crystalgraphics.trace.CgThreadAllocation;
import com.crystalgraphics.net.object.CgNetObjects;
import com.crystalgraphics.net.object.CgNetProbe;
import com.crystalgraphics.net.object.CgNetStress;
import com.crystalgraphics.net.protocol.CgConnections;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.probe.CgCollisionProbe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The connections of this process: one per player on a server, one to the server on a client. A loader host
 * forwards its join, leave, tick and connection events here; everything else asks for a connection.
 *
 * <p><b>Using a connection.</b> Register a contributor once, at init; it is bound onto every connection that opens
 * afterwards. Reach one directly with {@link #forPlayer} or {@link #client}:</p>
 *
 * <pre>{@code
 * CgProtocols.server("mymod", connection -> connection.onNotify("mymod/ping", args -> pong(connection)));
 *
 * CgProtocolConnection<Object> toPlayer = CgNetwork.forPlayer(serverPlayer); // server, the host's player; null if none
 * CgProtocolConnection<Object> toServer = CgNetwork.client();                // client; null outside a world
 * }</pre>
 *
 * <p><b>Hosting it</b> (a loader host, once per era):</p>
 *
 * <pre>{@code
 * CgNetwork.install(channel, player -> ((ServerPlayer) player).getGameProfile().getId());   // mod init, both sides
 *
 * CgNetwork.playerJoined(id, name, () -> listener.player);   // join; the supplier resolves the live entity
 * CgNetwork.playerLeft(id);                                  // leave, by id: the entity may be a new one
 * CgNetwork.serverTick();  CgNetwork.clientTick();           // once a tick each
 * CgNetwork.clientConnected();  CgNetwork.clientDisconnected();
 * CgNetwork.closeAll("server stopping");
 * }</pre>
 *
 * <ul>
 *   <li>A connection opens on a lifecycle event, never on traffic: a frame for a peer that has gone is dropped.</li>
 *   <li>Single player holds both tables in one process; each is ticked by its own side's event.</li>
 *   <li>With no channel, {@link #install} warns and nothing opens; {@link #forPlayer} and {@link #client} answer
 *       null, which is also what every consumer must handle for a peer that has left.</li>
 *   <li>{@link CgProtocolConnection#peer()} is a {@link CgPeer} on a server and null on a client.</li>
 * </ul>
 */
public final class CgNetwork {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** The client's single key: it has one server and needs no identity. */
    private static final Object CLIENT = "client";

    /** The hello and the typed messages, on every connection. */
    private static final String CONTRIBUTOR = "cg";

    private static final List<Consumer<CgPeer>> CLOSED = new CopyOnWriteArrayList<>();
    private static final List<Runnable> SERVER_TICK = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CLIENT_TICK = new CopyOnWriteArrayList<>();
    private static final List<Runnable> SESSION_END = new CopyOnWriteArrayList<>();

    /** Server ticks since this process started a server: what an age on the wire is measured in. */
    private static volatile long serverTicks;
    /** The threads the server and the client last ticked on, each written from its own: what {@link #side()} reads. */
    private static volatile Thread serverThread, clientThread;

    @Nullable
    private static volatile CgNetworkChannel channel;
    @Nullable
    private static volatile Function<Object, UUID> idOf;
    @Nullable
    private static volatile CgConnections server;
    @Nullable
    private static volatile CgConnections client;

    /** Which half of the game code runs on: {@link #side()}. */
    public enum Side {
        CLIENT,
        SERVER
    }

    /**
     * The side the calling code runs on: {@link Side#SERVER} on the server's thread, a singleplayer game's included;
     * {@link Side#CLIENT} on the client's. What a call answering for either side asks first.
     *
     * <pre>{@code
     * if (CgNetwork.side() == CgNetwork.Side.SERVER) damage(target);
     * else sparks(target);
     * }</pre>
     *
     * <ul>
     *   <li>A dedicated server is {@link Side#SERVER} on every thread; a client is {@link Side#CLIENT} on every thread
     *       but the integrated server's, once its first tick has run (the title screen's).</li>
     *   <li>A worker has no side of its own: decide on the game thread and hand the work what it needs.</li>
     * </ul>
     */
    public static Side side() {
        Thread now = Thread.currentThread();
        if (now == serverThread) return Side.SERVER;
        return clientThread != null ? Side.CLIENT : Side.SERVER;
    }

    private CgNetwork() {
    }

    /**
     * Provides {@code channel} to {@link CgNetworkChannel#SERVICE} and installs the two tables. Once, from mod init
     * on both sides, before any player can join.
     *
     * @param playerIds a player handle (what the channel hands inbound) to its profile UUID, or null for one
     *                  without a profile
     * @return whether it installed: false when the channel is unavailable
     */
    public static synchronized boolean install(CgNetworkChannel channel, Function<Object, UUID> playerIds) {
        CgPlatform.provide(CgNetworkChannel.SERVICE, channel);
        CgFrameBoundary.installServerTick();   // a dedicated server's boundary: it has no host frame
        if (!channel.isAvailable()) {
            LOGGER.warn("[cg-net] no network channel; connections will not be opened");
            return false;
        }
        CgNetwork.channel = channel;
        idOf = playerIds;
        // The server is not the initiator: odd/even stream ids, as HTTP/2 splits them.
        server = new CgConnections("server", channel.maxFrameBytes(), false).onPeerClosed(CgNetwork::closed);
        client = new CgConnections("client", channel.maxFrameBytes(), true);
        channel.setInboundHandler(CgNetwork::route);
        Set<String> contributors = CgProtocols.contributors();
        if (!contributors.contains(CONTRIBUTOR)) {
            // The hello first: a typed send to a peer waits on it.
            CgProtocols.contribute(CONTRIBUTOR, connection -> {
                CgHello.bind(connection, CgNetwork::namespaces);
                CgMessage.bindAll(connection);
                CgRequest.bindAll(connection);
                CgNetObjects.bind(connection);
            });
        }
        LOGGER.info("[cg-net] connections installed; contributors: {}", CgProtocols.contributors());
        if (Boolean.getBoolean(CgNetStress.PROPERTY)) CgNetStress.install();
        if (CgNetProbe.ENABLED) CgNetProbe.install();
        if (CgCollisionProbe.ENABLED) CgCollisionProbe.install();
        CgCollisionShow.install();
        CgTagNet.install();
        CgCueNet.install();
        return true;
    }

    /** What the hello says this side speaks: every message's, request's and networked object type's namespace. */
    private static Map<String, Integer> namespaces() {
        Map<String, Integer> out = CgMessage.namespaces();
        out.putAll(CgRequest.namespaces());
        out.putAll(CgNetObjects.namespaces());
        return out;
    }

    /** This side's version of {@code namespace}: 1 unless {@link CgMessage#version} said otherwise. */
    public static int version(String namespace) {
        return CgMessage.versionOf(namespace);
    }

    /** Whether the peer of {@code connection} has said hello. */
    public static boolean helloKnown(CgProtocolConnection<Object> connection) {
        return CgHello.state(connection).known();
    }

    /** The version of {@code namespace} the peer of {@code connection} declared; -1 if it lacks it or has not said. */
    public static int helloVersion(CgProtocolConnection<Object> connection, String namespace) {
        CgHello.State state = CgHello.state(connection);
        return state.known() ? state.version(namespace) : -1;
    }

    /** Whether {@link #install} installed. A server with no networking otherwise boots and looks healthy. */
    public static boolean isInstalled() {
        return server != null;
    }

    /** Called with each peer whose connection has closed, so per-peer state elsewhere can be dropped. */
    public static void onPeerClosed(Consumer<CgPeer> listener) {
        CLOSED.add(listener);
    }

    /**
     * Runs {@code listener} once a server tick, on the server thread, before networked objects send the tick's
     * changes: a change made here reaches clients this tick. At init.
     */
    public static void onServerTick(Runnable listener) {
        SERVER_TICK.add(listener);
    }

    /** Runs {@code listener} once a client tick, on the client thread, while connected to a server. At init. */
    public static void onClientTick(Runnable listener) {
        CLIENT_TICK.add(listener);
    }

    /**
     * Runs {@code listener} when this process leaves its world: the client disconnects, or the server stops. On the
     * thread that left; state kept per world elsewhere is dropped here. At init.
     */
    public static void onSessionEnd(Runnable listener) {
        SESSION_END.add(listener);
    }

    /** Server ticks counted since this process first ticked a server; a server thread's clock. */
    public static long serverTicks() {
        return serverTicks;
    }

    /**
     * The connection to this player, the host's own player object ({@code ServerPlayer}, {@code EntityPlayerMP}), or
     * null when they have none.
     *
     * <pre>{@code
     * CgProtocolConnection<Object> toThem = CgNetwork.forPlayer(serverPlayer);   // server
     * if (toThem != null) toThem.notify("mymod/ping", args);
     * }</pre>
     */
    @Nullable
    public static CgProtocolConnection<Object> forPlayer(@Nullable Object player) {
        if (player instanceof UUID) return forPlayer((UUID) player);
        Function<Object, UUID> ids = idOf;
        return player == null || ids == null ? null : forPlayer(ids.apply(player));
    }

    /** The connection to the player with this profile UUID, or null when they have none. */
    @Nullable
    public static CgProtocolConnection<Object> forPlayer(@Nullable UUID id) {
        CgConnections table = server;
        return id == null || table == null ? null : table.get(id);
    }

    /** The connection to the server, or null when not in a world. */
    @Nullable
    public static CgProtocolConnection<Object> client() {
        CgConnections table = client;
        return table == null ? null : table.get(CLIENT);
    }

    /** Hands each connected player's profile id to {@code out}. Server side; what {@link CgAudience#all()} reads. */
    public static void forEachPlayer(Consumer<UUID> out) {
        CgConnections table = server;
        if (table == null) return;
        for (Object key : table.keys()) {
            if (key instanceof UUID) out.accept((UUID) key);
        }
    }

    /** Hands each connected player's peer to {@code out}. Server side; what the dimension and radius audiences read. */
    static void forEachPeer(Consumer<CgPeer> out) {
        CgConnections table = server;
        if (table == null) return;
        for (Object key : table.keys()) {
            CgProtocolConnection<Object> connection = table.get(key);
            if (connection != null && connection.peer() instanceof CgPeer) out.accept((CgPeer) connection.peer());
        }
    }

    /** How many connections are open, both sides. Diagnostics, and what a leak shows up in. */
    public static int openConnections() {
        CgConnections s = server;
        CgConnections c = client;
        return (s == null ? 0 : s.size()) + (c == null ? 0 : c.size());
    }

    // ── What a host forwards ────────────────────────────────────────────────────────────────────

    /** @param player resolves the live player handle at send time; the entity is replaced on every respawn */
    public static void playerJoined(UUID id, String name, Supplier<Object> player) {
        CgConnections table = server;
        CgNetworkChannel wire = channel;
        if (table == null || wire == null) return;
        CgPeer peer = new CgPeer(id, name, player);
        table.open(id, peer, frame -> {
            Object live = peer.player();
            if (live != null) wire.sendToPlayer(live, frame);
        });
        LOGGER.info("[cg-net] connection opened for {} ({} open)", name, openConnections());
    }

    /** By id: the leave event carries whichever entity the player wears now, not the one that joined. */
    public static void playerLeft(UUID id) {
        CgConnections table = server;
        if (table != null && table.close(id, "player left")) {
            LOGGER.info("[cg-net] connection closed for {} ({} open)", id, openConnections());
        }
    }

    public static void clientConnected() {
        CgConnections table = client;
        CgNetworkChannel wire = channel;
        if (table == null || wire == null) return;
        table.open(CLIENT, null, wire::sendToServer);
        LOGGER.info("[cg-net] client connection opened");
    }

    public static void clientDisconnected() {
        CgConnections table = client;
        if (table != null && table.close(CLIENT, "disconnected")) {
            LOGGER.info("[cg-net] client connection closed");
        }
        sessionEnded();
    }

    private static void sessionEnded() {
        for (int i = 0; i < SESSION_END.size(); i++) {
            try {
                SESSION_END.get(i).run();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] a session-end listener failed", failed);
            }
        }
    }

    /**
     * The server is starting: persisted networked objects are loaded, before any mod's own start handler or player.
     * {@code server} is anything {@code CgWorldQueries.saveDirectory} accepts.
     */
    public static void serverStarting(@Nullable Object server) {
        serverThread = Thread.currentThread();
        CgNetObjects.serverStarting(server);
    }

    public static void serverTick() {
        CgConnections table = server;
        if (table == null) return;
        serverThread = Thread.currentThread();
        serverTicks++;
        // Indexed: a copy-on-write list's iterator is an allocation every tick.
        for (int i = 0; i < SERVER_TICK.size(); i++) {
            try {
                SERVER_TICK.get(i).run();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] a server-tick listener failed", failed);
            }
        }
        long started = System.nanoTime();
        long allocated = measureAllocation ? CgThreadAllocation.current() : 0;
        CgNetObjects.serverTick();
        long objectsDone = System.nanoTime();
        long objectsAllocated = measureAllocation ? CgThreadAllocation.current() : 0;
        table.tick();
        serverTickNanos = System.nanoTime() - started;
        serverObjectsNanos = objectsDone - started;
        if (measureAllocation) {
            serverTickAllocated = CgThreadAllocation.current() - allocated;
            serverObjectsAllocated = objectsAllocated - allocated;
        }
    }

    public static void clientTick() {
        clientThread = Thread.currentThread();
        CgConnections table = client;
        if (table == null) return;
        for (int i = 0; i < CLIENT_TICK.size(); i++) {
            try {
                CLIENT_TICK.get(i).run();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] a client-tick listener failed", failed);
            }
        }
        long started = System.nanoTime();
        table.tick();
        clientTickNanos = System.nanoTime() - started;
    }

    private static volatile boolean measureAllocation;
    private static long serverTickNanos, serverTickAllocated, serverObjectsNanos, serverObjectsAllocated, clientTickNanos;

    /** The last server tick's network work, objects then every connection, in nanoseconds. Server thread. */
    public static long serverTickNanos() {
        return serverTickNanos;
    }

    /** Bytes the server thread allocated in that work, or 0 unless {@link #measureAllocation(boolean)} is on. */
    public static long serverTickAllocated() {
        return serverTickAllocated;
    }

    /** The networked-objects share of {@link #serverTickNanos()}; the rest is the connections'. */
    public static long serverObjectsNanos() {
        return serverObjectsNanos;
    }

    /** The networked-objects share of {@link #serverTickAllocated()}. */
    public static long serverObjectsAllocated() {
        return serverObjectsAllocated;
    }

    /** Reads the server thread's allocation around each tick's network work: three counter reads a tick. */
    public static void measureAllocation(boolean on) {
        measureAllocation = on && CgThreadAllocation.available();
    }

    /** The last client tick's network work, receiving and every listener it ran, in nanoseconds. Client thread. */
    public static long clientTickNanos() {
        return clientTickNanos;
    }

    /**
     * The server is stopping: fails everything outstanding now, rather than leaving each caller to wait out its timeout,
     * and saves the networked objects that persist.
     */
    public static void closeAll(String reason) {
        int had = openConnections();
        CgConnections s = server;
        CgConnections c = client;
        if (s != null) s.closeAll(reason);
        if (c != null) c.closeAll(reason);
        CgNetObjects.serverStopped();
        sessionEnded();
        if (had > 0) LOGGER.info("[cg-net] closed {} connection(s): {}", had, reason);
    }

    /** <b>Network thread.</b> {@code sender} is the player handle on a server and null on a client. */
    private static void route(@Nullable Object sender, byte[] frame) {
        if (sender == null) {
            CgConnections table = client;
            if (table != null) table.route(CLIENT, frame);
            return;
        }
        Function<Object, UUID> ids = idOf;
        CgConnections table = server;
        UUID id = ids == null ? null : ids.apply(sender);
        if (id != null && table != null) table.route(id, frame);
    }

    private static void closed(Object peer) {
        if (!(peer instanceof CgPeer)) return;
        for (Consumer<CgPeer> listener : CLOSED) {
            try {
                listener.accept((CgPeer) peer);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] a peer-closed listener failed", failed);
            }
        }
    }

    /** Back to before {@link #install}, with no networked objects; declarations stay. Tests only. */
    public static synchronized void resetForTesting() {
        closeAll("reset");
        CgNetObjects.resetForTesting();
        CgTagNet.resetForTesting();
        CLOSED.clear();
        SERVER_TICK.clear();
        CLIENT_TICK.clear();
        serverTicks = 0;
        serverThread = clientThread = null;
        channel = null;
        idOf = null;
        server = null;
        client = null;
    }
}
