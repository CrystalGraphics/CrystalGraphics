package com.crystalgraphics.net;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgConnections;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocols;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgNetworkChannel;
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
 * CgProtocolConnection<Object> toPlayer = CgNetwork.forPlayer(playerUuid);   // server; null if they have none
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

    @Nullable
    private static volatile CgNetworkChannel channel;
    @Nullable
    private static volatile Function<Object, UUID> idOf;
    @Nullable
    private static volatile CgConnections server;
    @Nullable
    private static volatile CgConnections client;

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
                CgHello.bind(connection, CgMessage::namespaces);
                CgMessage.bindAll(connection);
            });
        }
        LOGGER.info("[cg-net] connections installed; contributors: {}", CgProtocols.contributors());
        return true;
    }

    /** Whether {@link #install} installed. A server with no networking otherwise boots and looks healthy. */
    public static boolean isInstalled() {
        return server != null;
    }

    /** Called with each peer whose connection has closed, so per-peer state elsewhere can be dropped. */
    public static void onPeerClosed(Consumer<CgPeer> listener) {
        CLOSED.add(listener);
    }

    /** The connection to this player, or null when they have none. */
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
    }

    public static void serverTick() {
        CgConnections table = server;
        if (table != null) table.tick();
    }

    public static void clientTick() {
        CgConnections table = client;
        if (table != null) table.tick();
    }

    /** Fails everything outstanding now, rather than leaving each caller to wait out its timeout. */
    public static void closeAll(String reason) {
        int had = openConnections();
        CgConnections s = server;
        CgConnections c = client;
        if (s != null) s.closeAll(reason);
        if (c != null) c.closeAll(reason);
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

    /** Back to before {@link #install}. Tests only. */
    static synchronized void resetForTesting() {
        closeAll("reset");
        CLOSED.clear();
        channel = null;
        idOf = null;
        server = null;
        client = null;
    }
}
