package com.crystalgraphics.net.object;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * The networked objects of this process: the server's, and what each connection's client sees. {@link CgNetwork} drives
 * it; a mod reaches objects through {@link CgNetType}, and reads this only to inspect them.
 *
 * <pre>{@code
 * int objects = CgNetObjects.serverCount();                       // server: every live object
 * List<CgNetObject> turrets = CgNetObjects.visible(TURRET);       // client: what this client sees
 * }</pre>
 *
 * <p>The wire: one {@code cg/objects} frame per player per server tick that has anything for them, holding the tick,
 * then records. A type's names cross once per connection (a define record before its first spawn), so a field is its
 * index after that, and an older peer skips what it does not know: every field value is length-prefixed.</p>
 */
public final class CgNetObjects {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final String METHOD = "cg/objects";
    static final String COMMANDS = "cg/commands";
    static final String PREDICT = "cg/predict";
    static final int DEFINE = 1, SPAWN = 2, CHANGE = 3, MOVE = 4, DESPAWN = 5, EVENT = 6, REFUSED = 7, STOPPING = 8;
    static final int FLAG_TRANSIENT = 1, FLAG_MINE = 2, FLAG_PREDICTED = 4;
    /** Predictions one player may ask of one type: a second's worth at once, then this many a second. */
    static final float PREDICTIONS_PER_SECOND = 20;

    /** Ticks between saves of a persisted type that changed. */
    static final int SAVE_EVERY = 6000;

    /** Spawn priority: distance in buckets of this many blocks, nearest first. */
    static final int BUCKETS = 64, BUCKET_BLOCKS = 8;
    private static int budget = CgVar.integer("crystalgraphics.net.budget", 32 * 1024, 1024, Integer.MAX_VALUE,
            CgVar.RESTART, "Bytes of objects a server sends each player a tick; CgNetObjects.budget sets it in code.")
            .get();

    // Server thread.
    private static final ArrayList<CgNetObject> LIVE = new ArrayList<>();
    /** {@link #LIVE} by id: a command or a prediction names its object. */
    private static final IdTable BY_ID = new IdTable();
    private static final ArrayList<CgNetObject> FIRED = new ArrayList<>();
    private static boolean refusals;
    private static final ArrayList<Viewer> VIEWERS = new ArrayList<>();
    private static final Map<UUID, Viewer> VIEWERS_BY_ID = new HashMap<>();
    private static long[] usedSlots = new long[1];
    private static int nextId = 1;
    private static long tick;

    /** This tick's encodings of one object, shared by every viewer: spawn and change, for its owner and the rest. */
    private static final NetBuffer ENCODED = new NetBuffer(4096);
    private static final NetBuffer BLOCK = new NetBuffer(1024);
    private static final NetBuffer VALUE = new NetBuffer(256);
    private static final int SPAWN_OWNER = 0, SPAWN_OTHER = 1, CHANGE_OWNER = 2, CHANGE_OTHER = 3;
    private static final int[] SEGMENT_START = new int[4], SEGMENT_END = new int[4];
    private static final double[] AT = new double[3];
    /** An entity's or a chunk's audience this tick: each viewer whose mark is the current generation. */
    private static long generation;
    private static final Consumer<UUID> MARK = id -> {
        Viewer viewer = VIEWERS_BY_ID.get(id);
        if (viewer != null) viewer.mark = generation;
    };

    private static boolean started;
    @Nullable
    private static Object startedServer;

    static Object server() {
        return startedServer;
    }
    private static boolean[] unsaved = new boolean[8];

    private CgNetObjects() {
    }

    // ── The budget ──────────────────────────────────────────────────────────────────────────────

    /**
     * Server: how many bytes one player is sent a tick, about. What changes on objects they already have always goes;
     * new objects fill the rest, nearest first, and wait for a later tick when it is full. A player joining a crowded
     * world, or a thousand objects spawned at once, then arrives over several ticks instead of in one frame.
     *
     * <pre>{@code
     * CgNetObjects.budget(64 * 1024);    // at init: 64 KB a tick, 1.3 MB a second, per player
     * }</pre>
     *
     * <p>32 KB unless said, or {@code -Dcrystalgraphics.net.budget=<bytes>}. New objects always get a quarter of it,
     * so a tick full of changes still lets them in.</p>
     */
    public static void budget(int bytesPerTick) {
        if (bytesPerTick < 1024) throw new IllegalArgumentException("a budget under 1 KB a tick: " + bytesPerTick);
        budget = bytesPerTick;
    }

    public static int budget() {
        return budget;
    }

    /** Which bucket {@code obj}'s spawn waits in for {@code viewer}: nearer first, and older first among equals. */
    private static int bucket(Viewer viewer, CgNetObject obj) {
        double dx, dz, dy = 0;
        switch (obj.kind) {
            case POINT:
            case ENTITY:
                dx = obj.x - viewer.x;
                dy = obj.y - viewer.y;
                dz = obj.z - viewer.z;
                break;
            case CHUNK:
                dx = obj.chunkX * 16 + 8 - viewer.x;
                dz = obj.chunkZ * 16 + 8 - viewer.z;
                break;
            default:
                // On a player, a dimension or everyone: about the player, so first.
                return 0;
        }
        // Up to 150 blocks of credit for having existed 30 s, so a far object is not starved by near ones arriving.
        double score = Math.sqrt(dx * dx + dy * dy + dz * dz) - Math.min(tick - obj.spawnTick, 600) / 4.0;
        return (int) Math.max(0, Math.min(BUCKETS - 1, score / BUCKET_BLOCKS));
    }

    // ── Inspecting ──────────────────────────────────────────────────────────────────────────────

    /** Server: live objects of every type. */
    public static int serverCount() {
        return LIVE.size();
    }

    /** Server: the live object with {@code id}, or null. */
    @Nullable
    public static CgNetObject find(int id) {
        CgNetObject obj = BY_ID.get(id);
        return obj != null && obj.isLive() ? obj : null;
    }

    /** Client: the objects of {@code type} this client sees now, or none outside a world. */
    public static List<CgNetObject> visible(CgNetType type) {
        CgProtocolConnection<Object> connection = CgNetwork.client();
        if (connection == null || !connection.hasAttachment(ClientObjects.class)) return Collections.emptyList();
        return connection.attachment(ClientObjects.class, ClientObjects::new).visible(type);
    }

    /** This process's client, for a prediction. */
    static ClientObjects client() {
        CgProtocolConnection<Object> connection = CgNetwork.client();
        if (connection == null) throw new IllegalStateException("a prediction needs a connection to a server");
        return connection.attachment(ClientObjects.class, ClientObjects::new);
    }

    static long currentTick() {
        return tick;
    }

    /** The objects of {@code namespace} the server holds as seen by {@code player}: what that client should hold. */
    static int seenBy(UUID player, String namespace) {
        Viewer viewer = VIEWERS_BY_ID.get(player);
        if (viewer == null) return -1;
        int n = 0;
        for (int i = 0; i < LIVE.size(); i++) {
            CgNetObject obj = LIVE.get(i);
            if (obj.type.namespace.equals(namespace) && CgNetObject.has(obj.seenBy, viewer.slot)) n++;
        }
        return n;
    }

    /** Live objects of {@code namespace}. Server thread. */
    static int live(String namespace) {
        int n = 0;
        for (int i = 0; i < LIVE.size(); i++) if (LIVE.get(i).type.namespace.equals(namespace)) n++;
        return n;
    }

    /** Object frames sent to {@code player}: bytes, frames, the largest. Server thread. */
    static long[] sentTo(UUID player) {
        Viewer viewer = VIEWERS_BY_ID.get(player);
        return viewer == null ? new long[3] : new long[]{viewer.bytesSent, viewer.framesSent, viewer.largestFrame};
    }

    /** What a leak would show in, for tests: viewers, viewers by id, slots held, live, indexed and fired objects. */
    static String footprint() {
        int slots = 0;
        for (long word : usedSlots) slots += Long.bitCount(word);
        return "viewers " + VIEWERS.size() + ", by id " + VIEWERS_BY_ID.size() + ", slots " + slots + ", live " + LIVE.size()
                + ", indexed " + BY_ID.size() + ", fired " + FIRED.size();
    }

    static void forEachLive(CgNetType type, Consumer<CgNetObject> each) {
        for (int i = 0; i < LIVE.size(); i++) {
            CgNetObject obj = LIVE.get(i);
            if (obj.type == type && obj.isLive()) each.accept(obj);
        }
    }

    static int countLive(CgNetType type) {
        int n = 0;
        for (CgNetObject obj : LIVE) if (obj.type == type && obj.isLive()) n++;
        return n;
    }

    // ── Spawning ────────────────────────────────────────────────────────────────────────────────

    static void start(CgNetObject obj) {
        obj.live(nextId++, -1);
        obj.behaviourPending = true;
        LIVE.add(obj);
        BY_ID.put(obj.id, obj);
        markUnsaved(obj.type);
    }

    static void fire(CgNetObject obj) {
        obj.live(nextId++, -1);
        FIRED.add(obj);
    }

    private static void markUnsaved(CgNetType type) {
        if (!type.persisted) return;
        if (type.index >= unsaved.length) unsaved = Arrays.copyOf(unsaved, type.index + 8);
        unsaved[type.index] = true;
    }

    // ── Wiring, from CgNetwork ──────────────────────────────────────────────────────────────────

    /** Binds the layer onto a connection as it opens: a viewer on a server, an object table on a client. */
    public static void bind(CgProtocolConnection<Object> connection) {
        if (connection.peer() instanceof CgPeer) {
            Viewer viewer = new Viewer((CgPeer) connection.peer(), connection, takeSlot());
            VIEWERS.add(viewer);
            VIEWERS_BY_ID.put(viewer.peer.id(), viewer);
            connection.router().onNotify(COMMANDS, payload -> {
                if (payload instanceof byte[]) receiveCommands(viewer, (byte[]) payload);
            });
            connection.router().onNotify(PREDICT, payload -> {
                if (payload instanceof byte[]) receivePredictions(viewer, (byte[]) payload);
            });
            connection.onClosed(reason -> dropViewer(viewer));
        } else if (connection.peer() == null) {
            ClientObjects client = connection.attachment(ClientObjects.class, ClientObjects::new);
            connection.router().onNotify(METHOD, payload -> {
                if (payload instanceof byte[]) client.receive((byte[]) payload);
            });
            connection.onTick(client::tick);
            connection.onClosed(reason -> client.closed());
        }
    }

    /** The namespaces the hello must announce: every declared type's. */
    public static Map<String, Integer> namespaces() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CgNetType type : CgNetType.REGISTERED) out.put(type.namespace, CgNetwork.version(type.namespace));
        return out;
    }

    private static int takeSlot() {
        for (int word = 0; ; word++) {
            if (word == usedSlots.length) usedSlots = Arrays.copyOf(usedSlots, word + 1);
            if (usedSlots[word] != -1L) {
                int bit = Long.numberOfTrailingZeros(~usedSlots[word]);
                usedSlots[word] |= 1L << bit;
                return word * 64 + bit;
            }
        }
    }

    private static void dropViewer(Viewer viewer) {
        if (!VIEWERS.remove(viewer)) return;
        VIEWERS_BY_ID.remove(viewer.peer.id(), viewer);
        CgNetObject.unmark(usedSlots, viewer.slot);
        for (CgNetObject obj : LIVE) CgNetObject.unmark(obj.seenBy, viewer.slot);
    }

    /** The server is starting: persisted objects are loaded, before any player. */
    public static void serverStarting(@Nullable Object server) {
        started = true;
        startedServer = server;
        tick = CgNetwork.serverTicks();
        Path world = CgWorldQueries.saveDirectory(server);
        if (world == null) return;
        for (CgNetType type : CgNetType.REGISTERED) if (type.persisted) load(type, world);
    }

    /** A persisted type declared once the server is up is loaded now, or its first save writes over the last. */
    static void declaredLate(CgNetType type) {
        if (!started) return;
        LOGGER.warn("[cg-net] {} was declared after the server started; declare types at mod init", type.name);
        Path world = CgWorldQueries.saveDirectory(startedServer);
        if (world != null) load(type, world);
    }

    /** The server is stopping: what persists is saved, and nothing carries into the next world. */
    public static void serverStopped() {
        if (started) save();
        started = false;
        startedServer = null;
        LIVE.clear();
        BY_ID.clear();
        FIRED.clear();
        for (CgNetType type : CgNetType.REGISTERED) type.holders.clear();
        Arrays.fill(unsaved, false);
    }

    // ── Each server tick ────────────────────────────────────────────────────────────────────────

    /** Sends this tick's spawns, changes, moves and despawns, one frame per player. Server thread. */
    public static void serverTick() {
        tick = CgNetwork.serverTicks();
        List<CgNetType> types = CgNetType.REGISTERED;
        for (int t = 0; t < types.size(); t++) {
            CgNetType type = types.get(t);
            type.tickLive = type.tickViewers = type.tickBytes = 0;
        }
        if (!LIVE.isEmpty() || !FIRED.isEmpty() || refusals) {
            refusals = false;
            for (int v = 0; v < VIEWERS.size(); v++) VIEWERS.get(v).refresh(AT);
            sendLive();
            sendFired();
            for (int v = 0; v < VIEWERS.size(); v++) VIEWERS.get(v).flush();
        }
        trace(types);
        if (started && tick % SAVE_EVERY == 0) save();
    }

    private static void trace(List<CgNetType> types) {
        for (int t = 0; t < types.size(); t++) {
            CgNetType type = types.get(t);
            if (type.tickLive == 0 && type.tickBytes == 0 && type.refusalsTraced == type.refusals()) continue;
            CgTrace.counter(CgChannels.NET, type.traceLive, type.tickLive);
            CgTrace.counter(CgChannels.NET, type.traceViewers, type.tickViewers);
            CgTrace.counter(CgChannels.NET, type.traceBytes, type.tickBytes);
            long refused = type.refusals();
            CgTrace.counter(CgChannels.NET, type.traceRefused, refused - type.refusalsTraced);
            type.refusalsTraced = refused;
        }
    }

    /**
     * Server: each type's objects and traffic over the last tick, one line a type with any, for a console or a log.
     *
     * <pre>{@code
     * LOGGER.info(CgNetObjects.report());
     * // networked objects: 2 types live, 3 objects, 2 players
     * //   dragonforge:kamehameha   1 live   2 seen   96 B a tick   0 refused
     * }</pre>
     */
    public static String report() {
        StringBuilder out = new StringBuilder();
        int types = 0, objects = 0;
        for (CgNetType type : CgNetType.REGISTERED) {
            long refused = type.refusals();
            if (type.tickLive == 0 && type.tickBytes == 0 && refused == 0) continue;
            types++;
            objects += type.tickLive;
            out.append(String.format("%n  %-32s %d live   %d seen   %d B a tick   %d refused", type.name, type.tickLive,
                    type.tickViewers, type.tickBytes, refused));
        }
        return "networked objects: " + types + " types live, " + objects + " objects, " + VIEWERS.size() + " players" + out;
    }

    /** Follows anchors, runs server behaviours, then sends: a behaviour's changes leave the tick it makes them. */
    private static void sendLive() {
        int followed = LIVE.size();
        for (int i = 0; i < followed; i++) {
            CgNetObject obj = LIVE.get(i);
            if (obj.spawnTick < 0) obj.spawnTick = tick;
            if (obj.isLive() && obj.lifetime > 0 && tick - obj.spawnTick >= obj.lifetime) obj.despawn();
            if (obj.isLive() || obj.isStopping()) follow(obj);
        }
        for (int i = 0; i < followed; i++) {
            CgNetObject obj = LIVE.get(i);
            if (obj.behaviourPending && obj.isLive()) serverSpawned(obj);
            if (obj.isStopping()) serverStopping(obj);
            else if (obj.behaviour != null && obj.isLive() && !obj.dormant) serverTick(obj);
        }
        int keep = 0;
        for (int i = 0; i < LIVE.size(); i++) {
            CgNetObject obj = LIVE.get(i);
            if (i >= followed) {
                // Started by a behaviour this tick.
                if (obj.spawnTick < 0) obj.spawnTick = tick;
                if (obj.isLive()) follow(obj);
            }
            if (obj.isEnding()) {
                for (int v = 0; v < VIEWERS.size(); v++) {
                    Viewer viewer = VIEWERS.get(v);
                    if (!CgNetObject.has(obj.seenBy, viewer.slot)) continue;
                    int before = viewer.frame.size;
                    viewer.events(obj, isOwner(obj, viewer));
                    viewer.despawn(obj.id, obj.killed);
                    obj.type.tickBytes += viewer.frame.size - before;
                }
                BY_ID.remove(obj.id);
                obj.removed();
                if (obj.holderKey != null) obj.type.holders.remove(obj.holderKey, obj);
                markUnsaved(obj.type);
                serverEnded(obj);
                continue;
            }
            if (obj.anyDirty || obj.moved) markUnsaved(obj.type);
            send(obj);
            obj.clearDirty();
            LIVE.set(keep++, obj);
        }
        for (int i = LIVE.size() - 1; i >= keep; i--) LIVE.remove(i);
    }

    private static void sendFired() {
        for (int i = 0; i < FIRED.size(); i++) {
            CgNetObject obj = FIRED.get(i);
            obj.spawnTick = tick;
            follow(obj);
            if (!obj.isLive()) continue;
            beginObject();
            audience(obj);
            obj.type.tickLive++;
            for (int v = 0; v < VIEWERS.size(); v++) {
                Viewer viewer = VIEWERS.get(v);
                if (!viewer.ready || !viewer.speaks(obj.type) || !sees(viewer, obj)) continue;
                int before = viewer.frame.size;
                viewer.spawn(obj, spawnSegment(obj, isOwner(obj, viewer)));
                obj.type.tickBytes += viewer.frame.size - before;
                obj.type.tickViewers++;
            }
            obj.removed();
        }
        FIRED.clear();
    }

    private static void send(CgNetObject obj) {
        beginObject();
        boolean stopping = obj.isStopping();
        if (!stopping) audience(obj);
        obj.type.tickLive++;
        for (int v = 0; v < VIEWERS.size(); v++) {
            Viewer viewer = VIEWERS.get(v);
            if (!viewer.ready) continue;
            int before = viewer.frame.size;
            boolean saw = CgNetObject.has(obj.seenBy, viewer.slot);
            // Stopping, it finishes in front of whoever saw it, wherever they stand, and spawns for nobody new.
            boolean sees = stopping ? saw && inDimension(viewer, obj) : viewer.speaks(obj.type) && sees(viewer, obj);
            boolean owner = isOwner(obj, viewer);
            if (sees && !saw) {
                int segment = spawnSegment(obj, owner);
                // A prediction never waits: its client drops it if the server's copy is late.
                boolean predicted = owner && obj.predictionKey != 0;
                if (!predicted && !viewer.admits(owner ? 0 : bucket(viewer, obj), SEGMENT_END[segment] - SEGMENT_START[segment])) {
                    continue;
                }
                viewer.spawn(obj, segment);
                obj.seenBy(viewer.slot);
            } else if (!sees && saw) {
                viewer.despawn(obj.id, true);
                CgNetObject.unmark(obj.seenBy, viewer.slot);
            } else if (sees) {
                if (obj.anyDirty) {
                    int segment = changeSegment(obj, owner);
                    if (SEGMENT_END[segment] > SEGMENT_START[segment]) viewer.append(segment);
                }
                if (obj.moved) viewer.move(obj);
                if (obj.stopSend) viewer.stopping(obj.id);
            }
            if (sees) {
                viewer.events(obj, owner);
                obj.type.tickViewers++;
            }
            obj.type.tickBytes += viewer.frame.size - before;
        }
    }

    private static boolean isOwner(CgNetObject obj, Viewer viewer) {
        return obj.owner != null && obj.owner.equals(viewer.peer.id());
    }

    // ── Server behaviours ───────────────────────────────────────────────────────────────────────

    private static void serverSpawned(CgNetObject obj) {
        obj.behaviourPending = false;
        Function<CgNetObject, CgNetBehaviour> factory = obj.type.server;
        if (factory == null && obj.behaviour == null) return;
        try {
            if (obj.behaviour == null) obj.behaviour = factory.apply(obj);
            if (obj.behaviour != null) obj.behaviour.spawned(obj, obj.age());
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the server behaviour of {} failed to spawn", obj, failed);
        }
    }

    private static void serverTick(CgNetObject obj) {
        try {
            obj.behaviour.tick(obj);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the server behaviour of {} failed to tick", obj, failed);
        }
    }

    /** Despawned gracefully: told once, then ticked in front of its viewers until it is done or its linger runs out. */
    private static void serverStopping(CgNetObject obj) {
        if (!obj.stopAnnounced) {
            obj.stopAnnounced = true;
            obj.stopSend = true;
            obj.lingerUntil = tick + obj.type.linger;
            serverDespawned(obj, false);
        } else {
            serverTick(obj);
        }
        if (tick >= obj.lingerUntil || serverDone(obj)) obj.stopped();
    }

    /** Ended: a server behaviour not already told of a graceful stop hears of the end. */
    private static void serverEnded(CgNetObject obj) {
        if (obj.behaviour != null && !obj.stopAnnounced) serverDespawned(obj, obj.killed);
    }

    private static void serverDespawned(CgNetObject obj, boolean killed) {
        try {
            obj.behaviour.despawned(obj, killed);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the server behaviour of {} failed to despawn", obj, failed);
        }
    }

    private static boolean serverDone(CgNetObject obj) {
        try {
            return obj.behaviour.done();
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the server behaviour of {} failed", obj, failed);
            return true;
        }
    }

    // ── Anchors and audiences ───────────────────────────────────────────────────────────────────

    /**
     * Follows an object's anchor: an entity's place, a player's entity and dimension, whether a chunk is loaded. Ends
     * one whose holder has gone.
     */
    private static void follow(CgNetObject obj) {
        switch (obj.kind) {
            case ENTITY: {
                Object entity = obj.holder;
                if (!CgEntityQueries.alive(entity) || !obj.dimension.equals(CgWorldQueries.dimension(entity))) {
                    obj.despawn();
                    return;
                }
                if (CgEntityQueries.position(entity, AT)) obj.place(AT[0], AT[1], AT[2]);
                return;
            }
            case PLAYER: {
                Viewer viewer = VIEWERS_BY_ID.get(obj.player);
                Object player = viewer == null ? null : viewer.peer.player();
                if (viewer == null && !obj.type.persisted) {
                    obj.kill();
                    return;
                }
                if (player != null && !obj.type.copyOnDeath && !CgEntityQueries.alive(player)) {
                    obj.despawn();
                    return;
                }
                obj.holder = player;
                obj.dormant = player == null;
                if (player == null) return;
                String dimension = CgWorldQueries.dimension(player);
                int entityId = CgEntityQueries.idOf(player);
                if (entityId != obj.entityId || !dimension.equals(obj.dimension)) {
                    obj.entityId = entityId;
                    obj.dimension = dimension;
                    obj.moved = true;
                }
                if (CgEntityQueries.position(player, AT)) obj.place(AT[0], AT[1], AT[2]);
                return;
            }
            case CHUNK:
                if (obj.holder == null) obj.holder = CgWorldQueries.level(startedServer, obj.dimension);
                obj.dormant = obj.holder == null || !CgWorldQueries.chunkLoaded(obj.holder, obj.chunkX, obj.chunkZ);
                return;
            default:
        }
    }

    /** Marks the viewers the host says have an object's entity or chunk loaded, for {@link #sees}. */
    private static void audience(CgNetObject obj) {
        if (obj.dormant || obj.type.visibleTo != null) return;
        switch (obj.kind) {
            case ENTITY: {
                generation++;
                CgPlayers.trackingEntity(obj.holder, MARK);
                UUID self = CgPlayers.idOf(obj.holder);
                if (self != null) MARK.accept(self);
                return;
            }
            case PLAYER:
                if (!obj.type.visibleToTrackers || obj.holder == null) return;
                generation++;
                CgPlayers.trackingEntity(obj.holder, MARK);
                return;
            case CHUNK:
                generation++;
                CgPlayers.trackingChunk(obj.holder, obj.chunkX, obj.chunkZ, MARK);
                return;
            default:
        }
    }

    private static boolean inDimension(Viewer viewer, CgNetObject obj) {
        return obj.kind == CgAnchor.Kind.GLOBAL || viewer.dimension != null && viewer.dimension.equals(obj.dimension);
    }

    private static boolean sees(Viewer viewer, CgNetObject obj) {
        if (obj.dormant || !inDimension(viewer, obj)) return false;
        CgNetType type = obj.type;
        if (type.visibleTo != null) return type.visibleTo.test(obj, viewer.peer);
        switch (obj.kind) {
            case POINT: {
                if (type.range < 0) return true;
                double dx = viewer.x - obj.x, dy = viewer.y - obj.y, dz = viewer.z - obj.z;
                return dx * dx + dy * dy + dz * dz <= type.range * type.range;
            }
            case ENTITY:
            case CHUNK:
                return viewer.mark == generation;
            case PLAYER:
                return viewer.peer.id().equals(obj.player) || type.visibleToTrackers && viewer.mark == generation;
            default:
                return true;
        }
    }

    // ── Encoding, once per object per tick ──────────────────────────────────────────────────────

    private static void beginObject() {
        ENCODED.clear();
        for (int s = 0; s < 4; s++) SEGMENT_START[s] = -1;
    }

    private static int spawnSegment(CgNetObject obj, boolean owner) {
        int segment = owner ? SPAWN_OWNER : SPAWN_OTHER;
        if (SEGMENT_START[segment] >= 0) return segment;
        SEGMENT_START[segment] = ENCODED.size;
        ENCODED.writeByte(SPAWN);
        ENCODED.writeVarInt(obj.id);
        ENCODED.writeVarInt(obj.type.index);
        boolean predicted = owner && obj.predictionKey != 0;
        ENCODED.writeByte((obj.transientSpawn ? FLAG_TRANSIENT : 0) | (owner ? FLAG_MINE : 0) | (predicted ? FLAG_PREDICTED : 0));
        if (predicted) ENCODED.writeVarInt(obj.predictionKey);
        ENCODED.writeVarLong(obj.spawnTick);
        ENCODED.writeByte(obj.kind.ordinal());
        if (obj.kind != CgAnchor.Kind.GLOBAL) ENCODED.writeString(obj.dimension);
        writePlace(ENCODED, obj);
        writeFields(obj, obj.set, owner, true);
        SEGMENT_END[segment] = ENCODED.size;
        return segment;
    }

    private static int changeSegment(CgNetObject obj, boolean owner) {
        int segment = owner ? CHANGE_OWNER : CHANGE_OTHER;
        if (SEGMENT_START[segment] >= 0) return segment;
        int start = ENCODED.size;
        SEGMENT_START[segment] = start;
        ENCODED.writeByte(CHANGE);
        ENCODED.writeVarInt(obj.id);
        if (writeFields(obj, obj.dirty, owner, false) == 0) ENCODED.size = start;
        SEGMENT_END[segment] = ENCODED.size;
        return segment;
    }

    /** The fields in {@code which} a viewer may be sent, as a count then (index, length, value) each. */
    private static int writeFields(CgNetObject obj, long[] which, boolean owner, boolean spawn) {
        BLOCK.clear();
        int count = 0;
        CgNetField<?>[] fields = obj.type.fields;
        for (int i = 0; i < fields.length; i++) {
            CgNetField<?> field = fields[i];
            if (!CgNetObject.has(which, i) || !field.sentTo(owner) || (!spawn && field.initialOnly)) continue;
            if (owner && !spawn && CgNetObject.has(obj.ownerHas, i)) continue;
            VALUE.clear();
            field.write(VALUE, obj.bits[i], obj.refs[i]);
            BLOCK.writeVarInt(i);
            BLOCK.writeVarInt(VALUE.size);
            BLOCK.append(VALUE, 0, VALUE.size);
            count++;
        }
        ENCODED.writeVarInt(count);
        ENCODED.append(BLOCK, 0, BLOCK.size);
        return count;
    }

    /** What a client needs to find an object: a point, an entity's network id, a chunk. */
    private static void writePlace(NetBuffer out, CgNetObject obj) {
        switch (obj.kind) {
            case POINT:
                out.writeDouble(obj.x);
                out.writeDouble(obj.y);
                out.writeDouble(obj.z);
                return;
            case ENTITY:
            case PLAYER:
                out.writeVarInt(obj.entityId);
                return;
            case CHUNK:
                out.writeZigZag(obj.chunkX);
                out.writeZigZag(obj.chunkZ);
                return;
            default:
        }
    }

    static <P> void writeEvent(NetBuffer out, int id, int index, CgNetEvent<P> event, @Nullable P value) {
        out.writeByte(EVENT);
        out.writeVarInt(id);
        out.writeVarInt(index);
        VALUE.clear();
        event.write(VALUE, value);
        out.writeVarInt(VALUE.size);
        out.append(VALUE, 0, VALUE.size);
    }

    /** Commands from one player, each (object id, command index, length, value). Server thread, at its connection's tick. */
    private static void receiveCommands(Viewer viewer, byte[] payload) {
        NetBuffer in = new NetBuffer(payload);
        try {
            while (in.more()) {
                CgNetObject obj = find(in.readVarInt());
                int index = in.readVarInt();
                int length = in.readVarInt();
                if (obj == null || index >= obj.type.commands.length) {
                    in.skip(length);
                    continue;
                }
                command(viewer, obj, obj.type.commands[index], index, in, length);
            }
        } catch (CgCodecException malformed) {
            LOGGER.debug("[cg-net] a malformed command frame from {}: {}", viewer.peer, malformed.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <P> void command(Viewer viewer, CgNetObject obj, CgNetCommand<P> command, int index, NetBuffer in, int length) {
        int end = in.pos + length;
        boolean allowed = CgNetObject.has(obj.seenBy, viewer.slot)
                && (command.fromAnyViewer || isOwner(obj, viewer))
                && viewer.take(command);
        if (!allowed) {
            command.refused++;
            obj.type.refusedCommands++;
            in.skip(length);
            return;
        }
        P value;
        try {
            value = command.read(in, length);
        } catch (CgCodecException | ClassCastException malformed) {
            command.malformed++;
            in.pos = end;
            return;
        }
        P sent = value;
        if (command.sanitize != null && value != null) value = command.sanitize.apply(obj, value);
        if (value == null && command.codec != null) return;
        if (command.field != null) {
            obj.predictedSet(obj.type.require(command.field), value, value.equals(sent));
            return;
        }
        CgNetCommand.Handler<P> handler = (CgNetCommand.Handler<P>) obj.type.commandHandlers[index];
        if (handler == null) return;
        try {
            handler.handle(obj, viewer.peer, value);
        } catch (RuntimeException failed) {
            LOGGER.error("[cg-net] the {} handler of {} failed", command.name, obj.type.name, failed);
        }
    }

    /**
     * Predictions from one player, each (key, type name, flags, anchor, place, fields): proposed, put to the type's
     * rule, then started or fired with the key for the predictor, or refused. Server thread, at its connection's tick.
     */
    private static void receivePredictions(Viewer viewer, byte[] payload) {
        NetBuffer in = new NetBuffer(payload);
        try {
            while (in.more()) predicted(viewer, in);
        } catch (CgCodecException malformed) {
            LOGGER.debug("[cg-net] a malformed prediction frame from {}: {}", viewer.peer, malformed.getMessage());
        }
    }

    private static void predicted(Viewer viewer, NetBuffer in) {
        int key = in.readVarInt();
        String name = in.readString();
        int flags = in.readByte();
        int kind = in.readByte();
        double x = 0, y = 0, z = 0;
        int entityId = -1;
        if (kind == CgAnchor.Kind.POINT.ordinal()) {
            x = in.readDouble();
            y = in.readDouble();
            z = in.readDouble();
        } else if (kind == CgAnchor.Kind.ENTITY.ordinal()) {
            entityId = in.readVarInt();
        }
        CgNetType type = CgNetType.named(name);
        CgNetObject obj = null;
        Object player = viewer.peer.player();
        String dimension = player == null ? null : CgWorldQueries.dimension(player);
        if (type != null && type.acceptPredictions != null && !type.perHolder && dimension != null
                && viewer.speaks(type) && viewer.take(type, PREDICTIONS_PER_SECOND, PREDICTIONS_PER_SECOND)) {
            CgAnchor anchor = resolve(player, dimension, kind, x, y, z, entityId);
            if (anchor != null) obj = type.create(anchor);
        }
        int count = in.readVarInt();
        for (int f = 0; f < count; f++) {
            int index = in.readVarInt();
            int length = in.readVarInt();
            if (obj == null || index >= obj.type.fields.length || obj.type.fields[index].serverOnly) {
                in.skip(length);
                continue;
            }
            CgNetField<?> field = obj.type.fields[index];
            if (field.kind == CgNetField.Kind.CODEC) {
                obj.refs[index] = field.readRef(in, length);
            } else {
                obj.bits[index] = field.readBits(in);
            }
            CgNetObject.mark(obj.set, index);
        }
        boolean accepted = false;
        if (obj != null) {
            obj.owner = viewer.peer.id();
            try {
                accepted = type.acceptPredictions.test(viewer.peer, obj) && !obj.isRemoved();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the prediction rule of {} failed", type.name, failed);
            }
        }
        if (!accepted) {
            if (type != null) type.refusedPredictions++;
            viewer.refuse(key);
            return;
        }
        obj.predictionKey = key;
        if ((flags & FLAG_TRANSIENT) != 0) obj.fire();
        else obj.start();
    }

    /** A predicted anchor in the predicting player's dimension; null where the server cannot find it. */
    @Nullable
    private static CgAnchor resolve(Object player, String dimension, int kind, double x, double y,
                                    double z, int entityId) {
        if (kind == CgAnchor.Kind.POINT.ordinal()) return CgAnchor.at(dimension, x, y, z);
        if (kind == CgAnchor.Kind.DIMENSION.ordinal()) return CgAnchor.dimension(dimension);
        if (kind == CgAnchor.Kind.GLOBAL.ordinal()) return CgAnchor.global();
        if (kind != CgAnchor.Kind.ENTITY.ordinal()) return null;
        Object level = CgWorldQueries.level(player, dimension);
        Object entity = level == null ? null : CgEntityQueries.of(level).entity(entityId);
        return entity == null || !CgEntityQueries.alive(entity) ? null : CgAnchor.on(entity);
    }

    /** One player's place and frame. Server thread. */
    private static final class Viewer {
        final CgPeer peer;
        final CgProtocolConnection<Object> connection;
        final int slot;
        final NetBuffer frame = new NetBuffer(512);
        /** By type index: 0 not asked, 1 speaks it, 2 does not. */
        byte[] speaks = new byte[8];
        long[] defined = new long[1];
        boolean ready;
        @Nullable
        String dimension;
        double x, y, z;
        /** The {@link #generation} at which an object's audience last included it. */
        long mark = -1;
        /** Prediction keys refused since the last frame. */
        int[] refused = new int[4];
        int refusedCount;
        /**
         * Bytes of spawns held back last tick, by bucket; the worst bucket this tick may spawn; spawn bytes so far; and
         * the bytes of last tick's held-back spawns nearer than the cutoff, which the cutoff bucket leaves room for.
         */
        final int[] wanting = new int[BUCKETS];
        boolean anyWanting;
        int cutoff = BUCKETS, spawned, reserve;

        Viewer(CgPeer peer, CgProtocolConnection<Object> connection, int slot) {
            this.peer = peer;
            this.connection = connection;
            this.slot = slot;
        }

        void refresh(double[] at) {
            frame.clear();
            for (int r = 0; r < refusedCount; r++) {
                begin();
                frame.writeByte(REFUSED);
                frame.writeVarInt(refused[r]);
            }
            refusedCount = 0;
            // The nearest buckets of last tick's held-back spawns, up to and including the one the budget runs out in:
            // short of it, a bucket bigger than the budget would never be let in. This tick's new spawns arrive in
            // creation order, so priority takes one tick to apply.
            cutoff = BUCKETS;
            reserve = 0;
            if (anyWanting) {
                int b = 0;
                for (; b < BUCKETS - 1 && reserve + wanting[b] < budget; b++) reserve += wanting[b];
                cutoff = b;
                Arrays.fill(wanting, 0);
                anyWanting = false;
            }
            spawned = 0;
            ready = !connection.isClosed() && CgNetwork.helloKnown(connection);
            Object player = peer.player();
            if (player != null && CgEntityQueries.position(player, at)) {
                dimension = CgWorldQueries.dimension(player);
                x = at[0];
                y = at[1];
                z = at[2];
            } else {
                dimension = null;
            }
        }

        boolean speaks(CgNetType type) {
            if (type.index >= speaks.length) speaks = Arrays.copyOf(speaks, type.index + 8);
            if (speaks[type.index] == 0) {
                boolean same = CgNetwork.helloVersion(connection, type.namespace) == CgNetwork.version(type.namespace);
                speaks[type.index] = (byte) (same ? 1 : 2);
            }
            return speaks[type.index] == 1;
        }

        private void begin() {
            if (frame.isEmpty()) frame.writeVarLong(tick);
        }

        /** Whether a spawn of {@code bytes} in {@code bucket} goes this tick; held back, it is offered again next tick. */
        boolean admits(int bucket, int bytes) {
            // Spawns arrive in creation order: the cutoff bucket's must not take the room of nearer ones held back.
            int held = bucket < cutoff ? 0 : reserve;
            if (bucket <= cutoff && (frame.size + bytes + held <= budget || spawned + held < budget / 4)) {
                spawned += bytes;
                if (bucket < cutoff) reserve = Math.max(0, reserve - bytes);
                return true;
            }
            wanting[bucket] += bytes;
            anyWanting = true;
            return false;
        }

        void spawn(CgNetObject obj, int segment) {
            begin();
            if (!CgNetObject.has(defined, obj.type.index)) {
                if (obj.type.index >>> 6 >= defined.length) defined = Arrays.copyOf(defined, (obj.type.index >>> 6) + 1);
                CgNetObject.mark(defined, obj.type.index);
                frame.writeByte(DEFINE);
                frame.writeVarInt(obj.type.index);
                frame.writeString(obj.type.name);
                frame.writeVarInt(obj.type.fields.length);
                for (CgNetField<?> field : obj.type.fields) frame.writeString(field.name);
                frame.writeVarInt(obj.type.events.length);
                for (CgNetEvent<?> event : obj.type.events) frame.writeString(event.name);
                frame.writeVarInt(obj.type.commands.length);
                for (CgNetCommand<?> command : obj.type.commands) frame.writeString(command.name);
            }
            append(segment);
        }

        void append(int segment) {
            begin();
            frame.append(ENCODED, SEGMENT_START[segment], SEGMENT_END[segment]);
        }

        void events(CgNetObject obj, boolean owner) {
            if (obj.eventCount == 0) return;
            int[] spans = obj.eventSpans;
            for (int e = 0; e < obj.eventCount; e++) {
                if (spans[e * 3 + 2] != 0 && !owner) continue;
                begin();
                frame.append(obj.events, spans[e * 3], spans[e * 3 + 1]);
            }
        }

        void refuse(int key) {
            if (refusedCount == refused.length) refused = Arrays.copyOf(refused, refusedCount * 2);
            refused[refusedCount++] = key;
            refusals = true;
        }

        /** A token bucket per command, and per type predicted, this player has sent: tokens, and the tick last refilled. */
        final Map<Object, double[]> buckets = new IdentityHashMap<>();

        boolean take(CgNetCommand<?> command) {
            return take(command, command.perSecond, command.burstOrDefault());
        }

        boolean take(Object what, float perSecond, float burst) {
            double[] bucket = buckets.get(what);
            if (bucket == null) buckets.put(what, bucket = new double[]{burst, tick});
            bucket[0] = Math.min(burst, bucket[0] + (tick - bucket[1]) * perSecond / 20.0);
            bucket[1] = tick;
            if (bucket[0] < 1) return false;
            bucket[0] -= 1;
            return true;
        }

        /** A point that moved, or a player anchor's new entity id or dimension: (id, kind, place[, dimension]). */
        void move(CgNetObject obj) {
            begin();
            frame.writeByte(MOVE);
            frame.writeVarInt(obj.id);
            frame.writeByte(obj.kind.ordinal());
            writePlace(frame, obj);
            if (obj.kind == CgAnchor.Kind.PLAYER) frame.writeString(obj.dimension);
        }

        void despawn(int id, boolean killed) {
            begin();
            frame.writeByte(DESPAWN);
            frame.writeVarInt(id);
            frame.writeBool(killed);
        }

        void stopping(int id) {
            begin();
            frame.writeByte(STOPPING);
            frame.writeVarInt(id);
        }

        long bytesSent;
        int framesSent, largestFrame;

        void flush() {
            if (frame.isEmpty() || connection.isClosed()) return;
            bytesSent += frame.size;
            framesSent++;
            largestFrame = Math.max(largestFrame, frame.size);
            connection.notifyBytes(METHOD, frame.bytes, frame.size);
            connection.flush();
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────────────────────────

    private static Path file(Path world, CgNetType type) {
        return world.resolve("crystalgraphics").resolve("objects").resolve(type.namespace)
                .resolve(type.name.substring(type.namespace.length() + 1) + ".dat");
    }

    private static void load(CgNetType type, Path world) {
        Path file = file(world, type);
        if (!Files.isRegularFile(file)) return;
        CgDynamicOps<Object> ops = CgPlainOps.INSTANCE;
        int count = 0;
        try (InputStream in = Files.newInputStream(file)) {
            Object tree = CgBinaryFormat.readFrom(in);
            for (Object entry : ops.getListValue(CgCodecs.read(ops, tree).raw("objects"))) {
                CgCodecs.MapCodecReader<Object> o = CgCodecs.read(ops, entry);
                CgAnchor.Kind kind = CgAnchor.Kind.values()[o.field("k", CgCodecs.INT)];
                CgAnchor anchor = CgAnchor.restore(kind, o.optional("d", CgCodecs.STRING, null),
                        o.field("x", CgCodecs.DOUBLE), o.field("y", CgCodecs.DOUBLE), o.field("z", CgCodecs.DOUBLE),
                        o.optional("p", CgCodecs.UUID, null), o.optional("cx", CgCodecs.INT, 0), o.optional("cz", CgCodecs.INT, 0));
                CgNetObject obj = new CgNetObject(type, anchor);
                obj.owner = o.optional("o", CgCodecs.UUID, null);
                int lifetime = o.optional("l", CgCodecs.INT, -1);
                CgCodecs.MapCodecReader<Object> fields = CgCodecs.read(ops, o.raw("f"));
                for (int i = 0; i < type.fields.length; i++) {
                    CgNetField<?> field = type.fields[i];
                    if (!fields.has(field.name)) continue;
                    Object value = field.codec.decode(ops, fields.raw(field.name));
                    if (field.kind == CgNetField.Kind.CODEC) obj.refs[i] = value;
                    else obj.bits[i] = field.bitsOf(value);
                    CgNetObject.mark(obj.set, i);
                }
                obj.live(nextId++, tick - o.optional("a", CgCodecs.LONG, 0L));
                obj.lifetime = lifetime;
                obj.behaviourPending = true;
                if (type.perHolder) {
                    obj.holderKey = kind == CgAnchor.Kind.PLAYER ? obj.player : new CgNetType.ChunkKey(obj.dimension, obj.chunkX, obj.chunkZ);
                    type.holders.put(obj.holderKey, obj);
                }
                LIVE.add(obj);
                BY_ID.put(obj.id, obj);
                count++;
            }
            LOGGER.info("[cg-net] loaded {} {} from {}", count, type.name, file);
        } catch (IOException | RuntimeException failed) {
            Path aside = file.resolveSibling(file.getFileName() + ".unreadable");
            LOGGER.error("[cg-net] could not read {}; moved to {}", file, aside, failed);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A later save still writes a fresh file; saving over the unreadable one is what this guards.
            }
        }
    }

    private static void save() {
        Path world = CgWorldQueries.saveDirectory(startedServer);
        if (world == null) return;
        for (CgNetType type : CgNetType.REGISTERED) {
            if (!type.persisted || type.index >= unsaved.length || !unsaved[type.index]) continue;
            if (save(type, world)) unsaved[type.index] = false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean save(CgNetType type, Path world) {
        CgDynamicOps<Object> ops = CgPlainOps.INSTANCE;
        List<Object> objects = new ArrayList<>();
        for (CgNetObject obj : LIVE) {
            if (obj.type != type || !obj.isLive()) continue;
            CgCodecs.MapCodecBuilder<Object> fields = CgCodecs.map(ops);
            for (int i = 0; i < type.fields.length; i++) {
                if (!CgNetObject.has(obj.set, i)) continue;
                CgNetField<Object> field = (CgNetField<Object>) type.fields[i];
                fields.field(field.name, field.codec, field.kind == CgNetField.Kind.CODEC ? obj.refs[i] : field.box(obj.bits[i]));
            }
            CgCodecs.MapCodecBuilder<Object> o = CgCodecs.map(ops).field("k", CgCodecs.INT, obj.kind.ordinal())
                    .optional("d", CgCodecs.STRING, obj.dimension, null)
                    .field("x", CgCodecs.DOUBLE, obj.x).field("y", CgCodecs.DOUBLE, obj.y).field("z", CgCodecs.DOUBLE, obj.z)
                    .optional("o", CgCodecs.UUID, obj.owner, null)
                    .optional("p", CgCodecs.UUID, obj.player, null)
                    .field("a", CgCodecs.LONG, obj.age())
                    .raw("f", fields.build());
            if (obj.lifetime > 0) o.field("l", CgCodecs.INT, (int) Math.max(1, obj.lifetime - obj.age()));
            if (obj.kind == CgAnchor.Kind.CHUNK) o.field("cx", CgCodecs.INT, obj.chunkX).field("cz", CgCodecs.INT, obj.chunkZ);
            objects.add(o.build());
        }
        Object tree = CgCodecs.map(ops).raw("objects", ops.createList(objects)).build();
        Path file = file(world, type);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                CgBinaryFormat.writeTo(out, tree);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException failed) {
            LOGGER.error("[cg-net] could not save {} to {}", type.name, file, failed);
            return false;
        }
    }

    /** Back to no objects and no viewers; declared types stay. Tests only. */
    public static void resetForTesting() {
        LIVE.clear();
        BY_ID.clear();
        FIRED.clear();
        refusals = false;
        VIEWERS.clear();
        VIEWERS_BY_ID.clear();
        for (CgNetType type : CgNetType.REGISTERED) type.holders.clear();
        usedSlots = new long[1];
        nextId = 1;
        tick = 0;
        started = false;
        startedServer = null;
        Arrays.fill(unsaved, false);
    }
}
