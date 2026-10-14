package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;

/**
 * A kind of networked object: its fields, who sees it, whether it persists, and what it is on a client. Declared once,
 * at mod init, on both sides; the server spawns objects of it, and each client that sees one builds it.
 *
 * <pre>{@code
 * public final class Turret {
 *     public static final CgNetField<Float>   HEAT = CgNetField.ofFloat("heat");
 *     public static final CgNetField<Integer> AMMO = CgNetField.ofInt("ammo").ownerOnly();
 *
 *     public static final CgNetEvent<Shot>   FIRED = CgNetEvent.of("fired", Shot.CODEC);
 *     public static final CgNetCommand<Aim>  AIM  = CgNetCommand.of("aim", Aim.CODEC).rate(CgRatePolicy.throttle(50));
 *
 *     public static final CgNetType TYPE = CgNetType.define("mymod:turret")
 *             .fields(HEAT, AMMO).events(FIRED).commands(AIM).range(96).persisted().build();
 * }
 *
 * // server
 * CgNetObject turret = Turret.TYPE.spawn(CgAnchor.at(level, x, y, z)).owner(player).setInt(Turret.AMMO, 50).start();
 * turret.setFloat(Turret.HEAT, 0.4f);        // viewers get the tick's last value, once
 * turret.emit(Turret.FIRED, shot);           // to its viewers, after the tick's changes
 * Turret.TYPE.onCommand(Turret.AIM, (obj, sender, aim) -> obj.set(TARGET, aim.target()));   // at init
 * turret.despawn();
 *
 * // client, from client code
 * Turret.TYPE.onClient(obj -> new TurretView(obj));
 * Turret.TYPE.onChange(Turret.HEAT, (obj, heat) -> glow(obj, heat));
 * mine.send(Turret.AIM, new Aim(yaw, pitch));   // the owner's client
 * }</pre>
 *
 * <p>A one-shot that every viewer builds now and no later viewer ever sees — a hit spark — is the same type,
 * {@link CgNetObject#fire() fired} instead of started:</p>
 *
 * <pre>{@code
 * SPARK.spawn(CgAnchor.at(level, x, y, z)).setFloat(SIZE, 2f).fire();
 * }</pre>
 *
 * <p>The authority is a behaviour on the server, ticked on the server tick:</p>
 *
 * <pre>{@code
 * Turret.TYPE.onServer(obj -> new TurretBrain(obj));   // at init
 * }</pre>
 *
 * <p>A client that acted can show the result now and ask the server to make it real; the server's object takes the
 * predicted one over, with no duplicate:</p>
 *
 * <pre>{@code
 * SPARK.acceptPredictionsFrom((peer, spark) -> near(peer, spark, 6));   // server, at init
 * SPARK.predict(CgAnchor.at(x, y, z)).setFloat(SIZE, 1f).fire();        // client, the player who struck
 * }</pre>
 *
 * <ul>
 *   <li>Declare before any player connects; a second type with the same name is refused.</li>
 *   <li>Fields are sent and applied in the order {@link Builder#fields} lists them: list what others depend on
 *       first.</li>
 *   <li>A client without the type's namespace (the mod not installed there) is sent none of its objects.</li>
 *   <li>Server calls on the server thread; client callbacks on the client thread.</li>
 * </ul>
 */
public final class CgNetType {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    static final List<CgNetType> REGISTERED = new CopyOnWriteArrayList<>();

    final String name;
    final String namespace;
    final int index;
    final CgNetField<?>[] fields;
    final CgNetEvent<?>[] events;
    final CgNetCommand<?>[] commands;
    /** Blocks within which a point anchor's object is seen; negative for its whole dimension. */
    final double range;
    final boolean persisted;
    @Nullable
    final BiPredicate<CgNetObject, CgPeer> visibleTo;
    final boolean perHolder;
    final boolean copyOnDeath;
    final boolean visibleToTrackers;
    /** Server ticks a graceful despawn lets the server behaviour finish. */
    final int linger;
    /** Client ticks a prediction waits for the server before it counts as refused. */
    final int predictionTimeout;
    final boolean keepRefused;
    /** Server: a per-holder type's live objects by holder (a profile id, an entity, a {@link ChunkKey}). */
    final Map<Object, CgNetObject> holders = new HashMap<>();

    @Nullable
    volatile Function<CgNetObject, CgNetBehaviour> client;
    @Nullable
    volatile Function<CgNetObject, CgNetBehaviour> server;
    @Nullable
    volatile BiPredicate<CgPeer, CgNetObject> acceptPredictions;
    long refusedPredictions, refusedCommands;
    /** The last server tick's traffic, written by {@code CgNetObjects}: live objects, viewers summed, frame bytes. */
    int tickLive, tickViewers, tickBytes;
    /** Refusals already counted onto the trace. */
    long refusalsTraced;
    /** Its {@code CgChannels.NET} counters' names, interned once. */
    final int traceLive, traceViewers, traceBytes, traceRefused;
    /** By field index: what {@link #onChange} installed. */
    final List<BiConsumer<CgNetObject, Object>>[] changeListeners;
    /** By event index: what {@link #onEvent} installed. */
    final List<BiConsumer<CgNetObject, Object>>[] eventListeners;
    /** By command index: what {@link #onCommand} installed. */
    final CgNetCommand.Handler<?>[] commandHandlers;

    @SuppressWarnings("unchecked")
    private CgNetType(Builder b, int index) {
        this.name = b.name;
        this.namespace = b.name.substring(0, b.name.indexOf(':'));
        this.index = index;
        this.fields = b.fields.toArray(new CgNetField<?>[0]);
        this.events = b.events.toArray(new CgNetEvent<?>[0]);
        List<CgNetCommand<?>> commands = new ArrayList<>(b.commands);
        for (CgNetField<?> field : fields) if (field.command != null) commands.add(field.command);
        this.commands = commands.toArray(new CgNetCommand<?>[0]);
        this.range = b.range;
        this.persisted = b.persisted;
        this.visibleTo = b.visibleTo;
        this.perHolder = b.perHolder;
        this.copyOnDeath = b.copyOnDeath;
        this.visibleToTrackers = b.visibleToTrackers;
        this.linger = b.linger;
        this.predictionTimeout = b.predictionTimeout;
        this.keepRefused = b.keepRefused;
        this.changeListeners = new List[fields.length];
        for (int i = 0; i < fields.length; i++) changeListeners[i] = new CopyOnWriteArrayList<>();
        this.eventListeners = new List[events.length];
        for (int i = 0; i < events.length; i++) eventListeners[i] = new CopyOnWriteArrayList<>();
        this.commandHandlers = new CgNetCommand.Handler<?>[this.commands.length];
        this.traceLive = CgTraceNames.intern("net.objects/" + name);
        this.traceViewers = CgTraceNames.intern("net.viewers/" + name);
        this.traceBytes = CgTraceNames.intern("net.bytes/" + name);
        this.traceRefused = CgTraceNames.intern("net.refused/" + name);
    }

    /** Commands and predictions of this type refused, since the server started. */
    long refusals() {
        return refusedPredictions + refusedCommands;
    }

    /** Starts declaring a type named {@code namespace:path}. */
    public static Builder define(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A type name is namespace:path in lowercase, not '" + name + "'");
        }
        return new Builder(name);
    }

    /** Declares a type. */
    public static final class Builder {
        private final String name;
        private final List<CgNetField<?>> fields = new ArrayList<>();
        private final List<CgNetEvent<?>> events = new ArrayList<>();
        private final List<CgNetCommand<?>> commands = new ArrayList<>();
        private double range = -1;
        private boolean persisted;
        @Nullable
        private BiPredicate<CgNetObject, CgPeer> visibleTo;
        private boolean perHolder;
        private boolean copyOnDeath;
        private boolean visibleToTrackers;
        private int linger = 200;
        private int predictionTimeout = 40;
        private boolean keepRefused;

        private Builder(String name) {
            this.name = name;
        }

        /** Its fields, in the order they are sent and applied. */
        public Builder fields(CgNetField<?>... fields) {
            for (CgNetField<?> field : fields) {
                Objects.requireNonNull(field, "field");
                for (CgNetField<?> other : this.fields) {
                    if (other.name.equals(field.name)) throw new IllegalArgumentException(name + " lists " + field.name + " twice");
                }
                this.fields.add(field);
            }
            return this;
        }

        /** What happens to its objects, server to viewers. */
        public Builder events(CgNetEvent<?>... events) {
            for (CgNetEvent<?> event : events) {
                for (CgNetEvent<?> other : this.events) {
                    if (other.name.equals(event.name)) throw new IllegalArgumentException(name + " lists " + event.name + " twice");
                }
                this.events.add(Objects.requireNonNull(event, "event"));
            }
            return this;
        }

        /** What its clients may ask of its objects. */
        public Builder commands(CgNetCommand<?>... commands) {
            for (CgNetCommand<?> command : commands) {
                for (CgNetCommand<?> other : this.commands) {
                    if (other.name.equals(command.name)) throw new IllegalArgumentException(name + " lists " + command.name + " twice");
                }
                this.commands.add(Objects.requireNonNull(command, "command"));
            }
            return this;
        }

        /** A point-anchored object is seen within {@code blocks}; without this, by its whole dimension. */
        public Builder range(double blocks) {
            if (!(blocks > 0)) throw new IllegalArgumentException("range must be positive: " + blocks);
            range = blocks;
            return this;
        }

        /**
         * Who sees each object, asked once a tick per object and player, in place of the range. Never across
         * dimensions. Server thread.
         *
         * <pre>{@code
         * .visibleTo((obj, peer) -> team(peer.id()) == obj.getInt(TEAM))
         * }</pre>
         */
        public Builder visibleTo(BiPredicate<CgNetObject, CgPeer> rule) {
            visibleTo = Objects.requireNonNull(rule, "rule");
            return this;
        }

        /** Saved with the world and back the next time it starts; a {@link CgNetObject#fire() fired} one never is. */
        public Builder persisted() {
            persisted = true;
            return this;
        }

        /**
         * One object per holder, made on first use: an attachment. Its objects come from {@link #of} and
         * {@link #ofChunk}, never {@link #spawn}.
         *
         * <pre>{@code
         * MANA = CgNetType.define("mymod:mana").fields(CURRENT, MAX).perHolder().persisted().copyOnDeath().build();
         * MANA.of(player).setInt(CURRENT, 80);
         * }</pre>
         */
        public Builder perHolder() {
            perHolder = true;
            return this;
        }

        /** A player-anchored object outlives its player's death; without this it despawns when they die. */
        public Builder copyOnDeath() {
            copyOnDeath = true;
            return this;
        }

        /** A player-anchored object is seen by the players tracking its player too, not only by them. */
        public Builder visibleToTrackers() {
            visibleToTrackers = true;
            return this;
        }

        /** Server ticks a graceful despawn lets the server behaviour finish in front of its viewers; 200 unless said. */
        public Builder linger(int ticks) {
            if (ticks < 0) throw new IllegalArgumentException("linger: " + ticks);
            linger = ticks;
            return this;
        }

        /** Client ticks a prediction waits for the server's answer before it counts as refused; 40 unless said. */
        public Builder predictionTimeout(int ticks) {
            if (ticks <= 0) throw new IllegalArgumentException("predictionTimeout must be positive: " + ticks);
            predictionTimeout = ticks;
            return this;
        }

        /** A refused prediction ends gracefully, finishing what it shows, instead of at once. */
        public Builder keepRefused() {
            keepRefused = true;
            return this;
        }

        public CgNetType build() {
            synchronized (REGISTERED) {
                for (CgNetType other : REGISTERED) {
                    if (other.name.equals(name)) throw new IllegalStateException("Type '" + name + "' is already declared");
                }
                for (CgNetField<?> field : fields) {
                    field.freeze();
                    for (CgNetCommand<?> command : commands) {
                        if (field.command != null && command.name.equals(field.command.name)) {
                            throw new IllegalArgumentException(name + " has a command named as predicted field " + field.name + "'s");
                        }
                    }
                }
                CgNetType type = new CgNetType(this, REGISTERED.size());
                REGISTERED.add(type);
                if (type.persisted) CgNetObjects.declaredLate(type);
                return type;
            }
        }
    }

    // ── Server ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A new object at {@code anchor}, not yet sent: set its owner and fields, then {@link CgNetObject#start()} or
     * {@link CgNetObject#fire()}. Server thread.
     */
    public CgNetObject spawn(CgAnchor anchor) {
        if (perHolder) throw new IllegalStateException(name + " is per holder: its objects come from of() and ofChunk()");
        return create(Objects.requireNonNull(anchor, "anchor"));
    }

    CgNetObject create(CgAnchor anchor) {
        if (anchor.predictionOnly()) {
            throw new IllegalArgumentException(anchor + " names no level: it is for a client's predict(), not the server");
        }
        if (persisted && anchor.kind == CgAnchor.Kind.ENTITY) {
            throw new IllegalArgumentException(name + " is persisted, and an entity-anchored object is not yet: "
                    + "anchor it to a point, a chunk or a player");
        }
        return new CgNetObject(this, anchor);
    }

    /**
     * A per-holder type's object for a player (an entity or a profile id) or an entity, started if it was absent.
     * Server thread.
     *
     * <pre>{@code
     * CgNetObject mana = MANA.of(player);
     * mana.setInt(CURRENT, mana.getInt(CURRENT) - 10);
     * }</pre>
     */
    public CgNetObject of(Object playerOrEntity) {
        Objects.requireNonNull(playerOrEntity, "playerOrEntity");
        UUID player = playerOrEntity instanceof UUID ? (UUID) playerOrEntity
                : CgPlayers.idOf(playerOrEntity);
        return holder(player != null ? player : playerOrEntity, player != null ? CgAnchor.player(player) : null, playerOrEntity);
    }

    /**
     * A per-holder type's object for a player (an entity or a profile id) or an entity, if it has one; never made.
     * On the server thread, the object {@link #of} would answer; on a client, this client's copy of it, or null while it
     * does not see it.
     *
     * <pre>{@code
     * CHARACTER.find(Minecraft.getInstance().player).send(INPUT, match);   // client: command your own
     * CgNetObject other = CHARACTER.find(entity);                          // client: one you see, or null
     * if (CHARACTER.find(player) == null) welcome(player);                 // server: asks, makes nothing
     * }</pre>
     */
    @Nullable
    public CgNetObject find(Object playerOrEntity) {
        Objects.requireNonNull(playerOrEntity, "playerOrEntity");
        if (!perHolder) throw new IllegalStateException(name + " is not per holder: find answers per-holder types");
        if (CgNetwork.side() == CgNetwork.Side.SERVER) {
            UUID player = playerOrEntity instanceof UUID ? (UUID) playerOrEntity : CgPlayers.idOf(playerOrEntity);
            CgNetObject obj = holders.get(player != null ? player : playerOrEntity);
            return obj != null && !obj.isRemoved() ? obj : null;
        }
        Object entity = playerOrEntity instanceof UUID ? CgPlayers.player((UUID) playerOrEntity) : playerOrEntity;
        return entity == null ? null : CgNetObjects.clientHolder(this, CgEntityQueries.idOf(entity));
    }

    /** A per-holder type's object for a chunk, started if it was absent. Server thread. */
    public CgNetObject ofChunk(Object levelOrDimension, int chunkX, int chunkZ) {
        CgAnchor anchor = CgAnchor.chunk(levelOrDimension, chunkX, chunkZ);
        return holder(new ChunkKey(anchor.dimension, chunkX, chunkZ), anchor, null);
    }

    private CgNetObject holder(Object key, @Nullable CgAnchor anchor, @Nullable Object entity) {
        if (!perHolder) throw new IllegalStateException(name + " is not per holder: spawn its objects with spawn()");
        CgNetObject obj = holders.get(key);
        if (obj != null && !obj.isRemoved()) return obj;
        obj = create(anchor != null ? anchor : CgAnchor.on(entity));
        obj.holderKey = key;
        holders.put(key, obj);
        return obj.start();
    }

    /** A chunk as a holder key. */
    static final class ChunkKey {
        final String dimension;
        final int x, z;

        ChunkKey(String dimension, int x, int z) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ChunkKey)) return false;
            ChunkKey key = (ChunkKey) other;
            return x == key.x && z == key.z && dimension.equals(key.dimension);
        }

        @Override
        public int hashCode() {
            return (dimension.hashCode() * 31 + x) * 31 + z;
        }
    }

    /** Each live object of this type on the server. Despawning one from inside is allowed. */
    public void forEach(Consumer<CgNetObject> each) {
        CgNetObjects.forEachLive(this, each);
    }

    /** How many objects of this type are live on the server. */
    public int size() {
        return CgNetObjects.countLive(this);
    }

    /**
     * On the server: the authority over each object, made at its first server tick (and when a persisted one loads),
     * ticked each server tick while it is live and not dormant. After a graceful despawn it ticks on until
     * {@link CgNetBehaviour#done()}, for at most {@link Builder#linger}, its viewers keeping the object meanwhile.
     * Never for a fired object. At init.
     */
    public CgNetType onServer(Function<CgNetObject, CgNetBehaviour> factory) {
        server = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /**
     * On the server: which predictions of this type to make real, asked with the object the client proposed, not yet
     * started, owned by the predicting player. It may change the proposal's fields before answering. Without this,
     * every prediction is refused. At init.
     */
    public CgNetType acceptPredictionsFrom(BiPredicate<CgPeer, CgNetObject> rule) {
        acceptPredictions = Objects.requireNonNull(rule, "rule");
        return this;
    }

    /** Predictions the server refused, or could not resolve. */
    public long refusedPredictions() {
        return refusedPredictions;
    }

    // ── Client ──────────────────────────────────────────────────────────────────────────────────

    /**
     * On a client: an object shown here now, which the server is asked to make real once it is
     * {@link CgNetObject#start() started} or {@link CgNetObject#fire() fired}. Accepted, the server's object takes it
     * over (its fields override the predicted ones); refused, or unanswered within {@link Builder#predictionTimeout},
     * it ends. Anchors from {@link CgAnchor#at(double, double, double)}, {@link CgAnchor#onEntity},
     * {@link CgAnchor#dimension} or {@link CgAnchor#global()}.
     */
    public CgNetObject predict(CgAnchor anchor) {
        return CgNetObjects.client().predict(this, Objects.requireNonNull(anchor, "anchor"));
    }

    /** On a client: what each object of this type is there. Replaces any earlier factory. From client code. */
    public CgNetType onClient(Function<CgNetObject, CgNetBehaviour> factory) {
        client = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /**
     * On a client: called with {@code field}'s value whenever a frame changes it, and with each value a spawn carries.
     * In addition to any behaviour.
     */
    @SuppressWarnings("unchecked")
    public <V> CgNetType onChange(CgNetField<V> field, BiConsumer<CgNetObject, V> listener) {
        int i = indexOf(field);
        if (i < 0) throw new IllegalArgumentException(name + " does not list " + field.name);
        changeListeners[i].add((BiConsumer<CgNetObject, Object>) (BiConsumer<CgNetObject, ?>) listener);
        return this;
    }

    /** On a client: called with each {@code event} an object of this type receives. In addition to any behaviour. */
    @SuppressWarnings("unchecked")
    public <P> CgNetType onEvent(CgNetEvent<P> event, BiConsumer<CgNetObject, P> listener) {
        eventListeners[requireEvent(event)].add((BiConsumer<CgNetObject, Object>) (BiConsumer<CgNetObject, ?>) listener);
        return this;
    }

    /** On the server: what {@code command} does. Replaces any earlier handler. At init. */
    public <P> CgNetType onCommand(CgNetCommand<P> command, CgNetCommand.Handler<P> handler) {
        commandHandlers[requireCommand(command)] = Objects.requireNonNull(handler, "handler");
        return this;
    }

    // ── Shape ───────────────────────────────────────────────────────────────────────────────────

    public String name() {
        return name;
    }

    public String namespace() {
        return namespace;
    }

    public List<CgNetField<?>> fields() {
        return Collections.unmodifiableList(Arrays.asList(fields));
    }

    /** Whether it lists {@code field}. */
    public boolean lists(CgNetField<?> field) {
        return indexOf(field) >= 0;
    }

    /** Where this type lists {@code field}, or -1. */
    int indexOf(CgNetField<?> field) {
        for (int i = 0; i < fields.length; i++) if (fields[i] == field) return i;
        return -1;
    }

    int require(CgNetField<?> field) {
        int i = indexOf(field);
        if (i < 0) throw new IllegalArgumentException(name + " does not list " + field.name);
        return i;
    }

    int requireEvent(CgNetEvent<?> event) {
        for (int i = 0; i < events.length; i++) if (events[i] == event) return i;
        throw new IllegalArgumentException(name + " does not list " + event);
    }

    int requireCommand(CgNetCommand<?> command) {
        for (int i = 0; i < commands.length; i++) if (commands[i] == command) return i;
        throw new IllegalArgumentException(name + " does not list " + command);
    }

    /** Every declared type, in declaration order. */
    public static List<CgNetType> all() {
        return Collections.unmodifiableList(REGISTERED);
    }

    /** The declared type named {@code name}, or null: what a frame or a prediction names. */
    @Nullable
    static CgNetType named(String name) {
        for (int i = 0; i < REGISTERED.size(); i++) {
            CgNetType type = REGISTERED.get(i);
            if (type.name.equals(name)) return type;
        }
        return null;
    }

    @Override
    public String toString() {
        return "CgNetType[" + name + "]";
    }
}
