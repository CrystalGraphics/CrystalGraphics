package com.crystalgraphics.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgBinaryFormat;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgPlainOps;

/**
 * Server-owned objects whose state reaches the clients that can see them, now and whenever they come to see them: a
 * crater, a lingering storm, a world event. The server creates and changes them; each client is told when one comes
 * into view, changes and goes.
 *
 * <pre>{@code
 * public static final CgReplicated<Crater> CRATERS =
 *         CgReplicated.define("mymod:vfx/crater", Crater.CODEC).visibleWithin(128).persisted();   // at init, both sides
 *
 * CgReplicated.Handle<Crater> c = CRATERS.create(level, x, y, z, new Crater(radius, 0));       // server
 * c.update(crater -> crater.aged(1));                                                       // server, any tick
 * CRATERS.forEach(h -> { if (h.value().age() > 600) h.remove(); });                         // server
 *
 * CRATERS.onClient(new CgReplicated.View<Crater>() {                                         // client entry point
 *     public void added(long id, Crater crater) { ... }
 *     public void changed(long id, Crater crater) { ... }
 *     public void removed(long id) { ... }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Define at init, before any connection opens: the definition is a {@link CgMessage}, and one declared after a
 *       peer's hello is never sent to that peer.</li>
 *   <li>Changes made in one tick reach a client as one, with the value as it stands at the end of the tick.</li>
 *   <li>A player who joins or comes into range is sent the current value, never the changes it missed.</li>
 *   <li>Visible to the whole dimension unless {@link #visibleWithin} says otherwise; a player who never answered the
 *       hello, or lacks the namespace, is never sent one.</li>
 *   <li>{@link #persisted()} saves with the world: when the server stops and every five minutes. An object's id is
 *       for this session only, and a client never keeps one across a reconnect.</li>
 *   <li>Server calls on the server thread; a {@link View} is called on the client thread.</li>
 * </ul>
 */
public final class CgReplicated<T> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Ticks between saves of a persisted definition that changed. */
    static final int SAVE_EVERY = 6000;

    private static final int ADD = 0, CHANGE = 1, REMOVE = 2;

    private static final List<CgReplicated<?>> DEFINED = new CopyOnWriteArrayList<>();

    private static long serverTicks;

    /** A server is running: set at its start, cleared at its stop. A definition loads on its first use while it is. */
    private static volatile boolean started;
    @Nullable
    private static volatile Object startedServer;

    private final String name;
    private final String namespace;
    private final CgCodec<T> codec;
    private final CgMessage<Op<T>> message;
    private double range = -1;
    private boolean persisted;

    // Server thread.
    private final Map<Long, Handle<T>> live = new LinkedHashMap<>();
    private long nextId = 1;
    private boolean loaded;
    private boolean unsaved;

    // Client thread.
    private final Map<Long, T> seen = new HashMap<>();
    @Nullable
    private volatile View<T> view;

    private CgReplicated(String name, CgCodec<T> codec) {
        this.name = name;
        this.namespace = name.substring(0, name.indexOf(':'));
        this.codec = codec;
        this.message = CgMessage.toClients(name, Op.codec(codec));
        message.onReceive(this::receive);
    }

    /** Declares a kind of object, named as a {@link CgMessage} is. Once per name, at init, on both sides. */
    public static <T> CgReplicated<T> define(String name, CgCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        CgReplicated<T> definition = new CgReplicated<>(name, codec);
        DEFINED.add(definition);
        return definition;
    }

    /** Only players within {@code blocks} of an object see it. */
    public CgReplicated<T> visibleWithin(double blocks) {
        if (!(blocks > 0)) throw new IllegalArgumentException("range must be positive: " + blocks);
        range = blocks;
        return this;
    }

    /** Saved with the world and loaded the next time it starts. */
    public CgReplicated<T> persisted() {
        persisted = true;
        return this;
    }

    public String name() {
        return name;
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A new object at a point in a dimension: {@code levelOrDimension} is a level, an entity in it, or the dimension's
     * id. Sent to whoever can see it at the end of this tick.
     */
    public Handle<T> create(Object levelOrDimension, double x, double y, double z, T value) {
        Objects.requireNonNull(value, "value");
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        String dimension = levelOrDimension instanceof String
                ? (String) levelOrDimension : players.dimension(levelOrDimension);
        if (dimension == null) throw new IllegalArgumentException("not a level, an entity or a dimension id: " + levelOrDimension);
        if (!(levelOrDimension instanceof String)) load(players, levelOrDimension);
        ensureLoaded(players);
        return add(dimension, x, y, z, value);
    }

    /** Every live object. Removing one from inside is allowed. */
    public void forEach(Consumer<Handle<T>> each) {
        ensureLoaded(CgPlatform.get(CgServerPlayers.SERVICE));
        for (Handle<T> handle : new ArrayList<>(live.values())) {
            if (!handle.removed) each.accept(handle);
        }
    }

    /** How many objects are live on the server. */
    public int size() {
        ensureLoaded(CgPlatform.get(CgServerPlayers.SERVICE));
        int count = 0;
        for (Handle<T> handle : live.values()) {
            if (!handle.removed) count++;
        }
        return count;
    }

    private Handle<T> add(String dimension, double x, double y, double z, T value) {
        Handle<T> handle = new Handle<>(this, nextId++, dimension, x, y, z, value);
        live.put(handle.id, handle);
        unsaved = true;
        return handle;
    }

    /** One object on the server. Server thread only, like everything that changes it. */
    public static final class Handle<T> {
        private final CgReplicated<T> owner;
        private final long id;
        private final String dimension;
        private double x, y, z;
        private T value;
        private boolean dirty;
        private boolean removed;
        private final Set<UUID> viewers = new HashSet<>();

        private Handle(CgReplicated<T> owner, long id, String dimension, double x, double y, double z, T value) {
            this.owner = owner;
            this.id = id;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
        }

        /** What clients name this object by, for this session. */
        public long id() {
            return id;
        }

        public T value() {
            return value;
        }

        public String dimension() {
            return dimension;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public boolean isRemoved() {
            return removed;
        }

        /** Replaces the value; its viewers get it at the end of the tick. */
        public void set(T value) {
            Objects.requireNonNull(value, "value");
            if (removed) return;
            this.value = value;
            dirty = true;
            owner.unsaved = true;
        }

        public void update(UnaryOperator<T> change) {
            set(change.apply(value));
        }

        /** Moves it within its dimension: who sees it is decided again at the end of the tick. */
        public void moveTo(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            owner.unsaved = true;
        }

        /** Gone from every client at the end of the tick. */
        public void remove() {
            if (removed) return;
            removed = true;
            owner.unsaved = true;
        }
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    /** What a client does with the objects it can see. Called on the client thread. */
    public interface View<T> {
        void added(long id, T value);

        void changed(long id, T value);

        void removed(long id);
    }

    /** On a client: the one view of this kind of object. Replaces any earlier one. */
    public CgReplicated<T> onClient(View<T> view) {
        this.view = Objects.requireNonNull(view, "view");
        return this;
    }

    /** On a client: the objects it can see now, by id. */
    public Map<Long, T> visible() {
        return new LinkedHashMap<>(seen);
    }

    private void receive(Op<T> op) {
        View<T> v = view;
        switch (op.kind) {
            case ADD:
            case CHANGE:
                if (op.value == null) return;
                boolean had = seen.put(op.id, op.value) != null;
                if (v != null) {
                    if (had) v.changed(op.id, op.value);
                    else v.added(op.id, op.value);
                }
                break;
            case REMOVE:
                if (seen.remove(op.id) != null && v != null) v.removed(op.id);
                break;
            default:
        }
    }

    private void clientClosed() {
        View<T> v = view;
        List<Long> ids = new ArrayList<>(seen.keySet());
        seen.clear();
        if (v != null) ids.forEach(v::removed);
    }

    // ── Each server tick, from CgNetwork ────────────────────────────────────────────────────────

    static void serverTickAll() {
        serverTicks++;
        if (DEFINED.isEmpty()) return;
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        List<Viewer> viewers = viewers(players);
        for (CgReplicated<?> definition : DEFINED) definition.serverTick(players, viewers);
    }

    /** A player who can be sent objects: connected, past the hello, with a place in the world. */
    private static final class Viewer {
        final UUID id;
        final Object player;
        final String dimension;
        final double x, y, z;
        final CgHello.State hello;

        Viewer(UUID id, Object player, String dimension, double[] at, CgHello.State hello) {
            this.id = id;
            this.player = player;
            this.dimension = dimension;
            this.x = at[0];
            this.y = at[1];
            this.z = at[2];
            this.hello = hello;
        }
    }

    private static List<Viewer> viewers(CgServerPlayers players) {
        List<Viewer> out = new ArrayList<>();
        double[] at = new double[3];
        CgNetwork.forEachPeer(peer -> {
            CgProtocolConnection<Object> connection = CgNetwork.forPlayer(peer.id());
            Object player = peer.player();
            if (connection == null || connection.isClosed() || player == null) return;
            CgHello.State hello = CgHello.state(connection);
            if (!hello.known()) return;
            String dimension = players.dimension(player);
            if (dimension != null && players.position(player, at)) out.add(new Viewer(peer.id(), player, dimension, at, hello));
        });
        return out;
    }

    private void serverTick(CgServerPlayers players, List<Viewer> viewers) {
        ensureLoaded(players);
        if (persisted && !loaded && !viewers.isEmpty()) load(players, viewers.get(0).player);

        // Removals and changes go to who saw the old value, before anyone new is added with the current one.
        for (Iterator<Handle<T>> it = live.values().iterator(); it.hasNext(); ) {
            Handle<T> handle = it.next();
            if (handle.removed) {
                if (!handle.viewers.isEmpty()) message.send(handle.viewers::forEach, Op.remove(handle.id));
                it.remove();
            } else if (handle.dirty) {
                handle.dirty = false;
                if (!handle.viewers.isEmpty()) message.send(handle.viewers::forEach, Op.change(handle.id, handle.value));
            }
        }

        int version = CgMessage.versionOf(namespace);
        for (Viewer viewer : viewers) {
            boolean speaks = viewer.hello.version(namespace) == version;
            CgAudience one = CgAudience.player(viewer.id);
            for (Handle<T> handle : live.values()) {
                boolean sees = speaks && sees(viewer, handle);
                boolean saw = handle.viewers.contains(viewer.id);
                if (sees && !saw) {
                    handle.viewers.add(viewer.id);
                    message.send(one, Op.add(handle.id, handle.value));
                } else if (!sees && saw) {
                    handle.viewers.remove(viewer.id);
                    message.send(one, Op.remove(handle.id));
                }
            }
        }

        if (persisted && unsaved && serverTicks % SAVE_EVERY == 0) save(players, viewers.isEmpty() ? null : viewers.get(0).player);
    }

    private boolean sees(Viewer viewer, Handle<T> handle) {
        if (!viewer.dimension.equals(handle.dimension)) return false;
        if (range < 0) return true;
        double dx = viewer.x - handle.x, dy = viewer.y - handle.y, dz = viewer.z - handle.z;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    static void peerClosed(UUID id) {
        for (CgReplicated<?> definition : DEFINED) {
            for (Handle<?> handle : definition.live.values()) handle.viewers.remove(id);
        }
    }

    static void serverStarting(@Nullable Object server) {
        startedServer = server;
        started = true;
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        for (CgReplicated<?> definition : DEFINED) definition.load(players, server);
    }

    /** The server is stopping: what persists is saved, and nothing carries into the next world. */
    static void serverStopped() {
        started = false;
        startedServer = null;
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        for (CgReplicated<?> definition : DEFINED) {
            if (definition.persisted && definition.unsaved) definition.save(players, null);
            definition.live.clear();
            definition.loaded = false;
            definition.unsaved = false;
            definition.saveDirectory = null;
        }
    }

    static void clientClosedAll() {
        for (CgReplicated<?> definition : DEFINED) definition.clientClosed();
    }

    // ── Persistence ─────────────────────────────────────────────────────────────────────────────

    /** Where this server saves, remembered from the first handle that could say. */
    @Nullable
    private Path saveDirectory;

    private Path file(Path world) {
        return world.resolve("crystalgraphics").resolve("replicated").resolve(namespace)
                .resolve(name.substring(namespace.length() + 1) + ".dat");
    }

    private void ensureLoaded(CgServerPlayers players) {
        if (persisted && !loaded && started) load(players, startedServer);
    }

    private void load(CgServerPlayers players, @Nullable Object handle) {
        if (!persisted || loaded) return;
        Path world = players.saveDirectory(handle);
        if (world == null) return;
        loaded = true;
        saveDirectory = world;
        Path file = file(world);
        if (!Files.isRegularFile(file)) return;
        try (InputStream in = Files.newInputStream(file)) {
            CgDynamicOps<Object> ops = CgPlainOps.INSTANCE;
            Object tree = CgBinaryFormat.readFrom(in);
            for (Object object : ops.getListValue(CgCodecs.read(ops, tree).raw("objects"))) {
                CgCodecs.MapCodecReader<Object> o = CgCodecs.read(ops, object);
                Handle<T> h = add(o.field("d", CgCodecs.STRING), o.field("x", CgCodecs.DOUBLE),
                        o.field("y", CgCodecs.DOUBLE), o.field("z", CgCodecs.DOUBLE), codec.decode(ops, o.raw("v")));
                h.dirty = false;
            }
            unsaved = false;
            LOGGER.info("[cg-net] loaded {} {} from {}", live.size(), name, file);
        } catch (IOException | RuntimeException failed) {
            Path aside = file.resolveSibling(file.getFileName() + ".unreadable");
            LOGGER.error("[cg-net] could not read {}; moved to {}", file, aside, failed);
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Saving over it next is what this guards against; a later save still writes a fresh file.
            }
        }
    }

    private void save(CgServerPlayers players, @Nullable Object handle) {
        Path world = saveDirectory != null ? saveDirectory : handle == null ? null : players.saveDirectory(handle);
        if (world == null) return;
        CgDynamicOps<Object> ops = CgPlainOps.INSTANCE;
        List<Object> objects = new ArrayList<>();
        for (Handle<T> h : live.values()) {
            if (h.removed) continue;
            objects.add(CgCodecs.map(ops).field("d", CgCodecs.STRING, h.dimension).field("x", CgCodecs.DOUBLE, h.x)
                    .field("y", CgCodecs.DOUBLE, h.y).field("z", CgCodecs.DOUBLE, h.z).raw("v", codec.encode(ops, h.value))
                    .build());
        }
        Object tree = CgCodecs.map(ops).raw("objects", ops.createList(objects)).build();
        Path file = file(world);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                CgBinaryFormat.writeTo(out, tree);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            unsaved = false;
        } catch (IOException failed) {
            LOGGER.error("[cg-net] could not save {} to {}", name, file, failed);
        }
    }

    // ── The wire ────────────────────────────────────────────────────────────────────────────────

    /** One add, change or remove. */
    private static final class Op<T> {
        final int kind;
        final long id;
        @Nullable
        final T value;

        private Op(int kind, long id, @Nullable T value) {
            this.kind = kind;
            this.id = id;
            this.value = value;
        }

        static <T> Op<T> add(long id, T value) {
            return new Op<>(ADD, id, value);
        }

        static <T> Op<T> change(long id, T value) {
            return new Op<>(CHANGE, id, value);
        }

        static <T> Op<T> remove(long id) {
            return new Op<>(REMOVE, id, null);
        }

        static <T> CgCodec<Op<T>> codec(CgCodec<T> value) {
            return new CgCodec<Op<T>>() {
                @Override
                public <O> O encode(CgDynamicOps<O> ops, Op<T> op) {
                    CgCodecs.MapCodecBuilder<O> out = CgCodecs.map(ops).field("o", CgCodecs.INT, op.kind)
                            .field("i", CgCodecs.LONG, op.id);
                    if (op.value != null) out.raw("v", value.encode(ops, op.value));
                    return out.build();
                }

                @Override
                public <O> Op<T> decode(CgDynamicOps<O> ops, O input) {
                    CgCodecs.MapCodecReader<O> in = CgCodecs.read(ops, input);
                    O raw = in.raw("v");
                    return new Op<>(in.field("o", CgCodecs.INT), in.field("i", CgCodecs.LONG),
                            raw == null ? null : value.decode(ops, raw));
                }
            };
        }
    }

    /** Forgets every definition. Tests only. */
    static void resetForTesting() {
        DEFINED.clear();
        serverTicks = 0;
        started = false;
        startedServer = null;
    }
}
