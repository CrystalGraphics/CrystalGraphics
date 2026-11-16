package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.platform.world.CgPlayers;

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

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    static final List<CgNetType> REGISTERED = new CopyOnWriteArrayList<>();
    private static final Map<String, CgNetType> BY_NAME = new ConcurrentHashMap<>();
    /** The types {@link Builder#onEntities} put on entities: what an entity's arrival asks. */
    static final List<CgNetType> ON_ENTITIES = new CopyOnWriteArrayList<>();
    /** The persisted per-holder types: what an entity being saved is asked for. */
    static final List<CgNetType> SAVED_ON_ENTITIES = new CopyOnWriteArrayList<>();
    /** The types that have had an object or a refusal: the per-tick counters' loop, never every declared type. */
    static final List<CgNetType> USED = new CopyOnWriteArrayList<>();

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
    /** The entity types it is on, each side making their objects as they appear; null for none. */
    @Nullable
    final Predicate<String> entityTypes;
    /** Every field local: it never touches the wire, each side keeping its own objects. */
    final boolean localOnly;
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
    private volatile boolean used;
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
        this.entityTypes = b.entityTypes;
        boolean local = fields.length > 0;
        for (CgNetField<?> field : fields) local &= field.local;
        this.localOnly = local;
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
        @Nullable
        private Predicate<String> entityTypes;

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

        /**
         * Saved with the world and back the next time it starts; a {@link CgNetObject#fire() fired} one never is. A per-holder
         * type's object on an entity other than a player is saved with that entity instead ({@code CgEntitySaves}): it
         * travels with the entity through chunk unloads and dies with it.
         *
         * <pre>{@code
         * RAGE = CgNetType.define("mymod:rage").fields(LEVEL).onEntities(t -> t.equals("minecraft:zombie")).persisted().build();
         * }</pre>
         */
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

        /**
         * Puts one object on every entity whose type id {@code entityTypes} accepts, made as the entity appears on each
         * side and ended as it leaves: the entity's own properties. Implies {@link #perHolder()}.
         *
         * <pre>{@code
         * public static final CgNetField<Float> CURRENT = CgNetField.ofFloat("current");     // the server's, sent
         * public static final CgNetField<Integer> GEAR = CgNetField.ofInt("gear").local();   // each side's own
         * public static final CgNetType KI = CgNetType.define("mymod:ki").fields(CURRENT, GEAR)
         *         .onEntities(type -> type.equals("minecraft:player"))
         *         .onServer(obj -> new KiRegen())          // ticks on the server
         *         .onClient(obj -> new KiGear())           // its frame() each client frame
         *         .build();
         *
         * KI.of(player).get(CURRENT);
         * }</pre>
         *
         * <ul>
         *   <li>A type of only {@link CgNetField#local()} fields never touches the wire: each side makes its own
         *       objects, read and written there, and its events are heard on the side that emits them.</li>
         *   <li>Otherwise the server makes each entity's object and every client that sees the entity is sent it.</li>
         * </ul>
         */
        public Builder onEntities(Predicate<String> entityTypes) {
            this.entityTypes = Objects.requireNonNull(entityTypes, "entityTypes");
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
                if (BY_NAME.containsKey(name)) throw new IllegalStateException("Type '" + name + "' is already declared");
                return register();
            }
        }

        /**
         * This type, declared now, or the one already declared under its name if it has the same shape: what a compiler
         * calls each time it compiles the asset declaring a type, as a reload does. The answer's own fields and events
         * are the ones to use, found by name.
         *
         * <pre>{@code
         * CgNetType type = CgNetType.define(id).fields(fieldsFromTheFile).buildOnce();
         * CgNetField<?> current = type.field("current");
         * }</pre>
         *
         * @throws IllegalStateException when a type of its name is declared with other fields, events or rules: its
         *                               shape is fixed until a restart
         */
        public CgNetType buildOnce() {
            synchronized (REGISTERED) {
                CgNetType declared = BY_NAME.get(name);
                if (declared == null) return register();
                if (!declared.sameShape(this)) {
                    throw new IllegalStateException(name + " changed its fields, events or rules while declared: restart to change them");
                }
                return declared;
            }
        }

        private CgNetType register() {
            {
                for (CgNetField<?> field : fields) {
                    field.freeze();
                    for (CgNetCommand<?> command : commands) {
                        if (field.command != null && command.name.equals(field.command.name)) {
                            throw new IllegalArgumentException(name + " has a command named as predicted field " + field.name + "'s");
                        }
                    }
                }
                boolean local = !fields.isEmpty();
                for (CgNetField<?> field : fields) local &= field.local;
                if (local && persisted) throw new IllegalStateException(name + ": its fields are all local, and a local field is never saved");
                if (local && !commands.isEmpty()) throw new IllegalStateException(name + ": its fields are all local, and nothing reaches the server");
                CgNetType type = new CgNetType(this, REGISTERED.size());
                REGISTERED.add(type);
                BY_NAME.put(name, type);
                if (type.entityTypes != null) ON_ENTITIES.add(type);
                if (type.persisted && type.perHolder) SAVED_ON_ENTITIES.add(type);
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
        if (persisted && anchor.kind == CgAnchor.Kind.ENTITY && !perHolder) {
            throw new IllegalArgumentException(name + " is persisted, and an entity saves one object of a type: make it per "
                    + "holder (of(entity)), or anchor it to a point, a chunk or a player");
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
        if (playerOrEntity instanceof Integer) throw new IllegalArgumentException("an entity id is an int: of(int), not a boxed Integer");
        if (localOnly) {
            if (!perHolder) throw new IllegalStateException(name + " is not per holder: spawn its objects with spawn()");
            return CgNetwork.side() == CgNetwork.Side.SERVER ? CgNetEntities.server(this, playerOrEntity, true)
                    : CgNetEntities.client(this, CgEntityQueries.idOf(playerOrEntity), true);
        }
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
        if (playerOrEntity instanceof Integer) throw new IllegalArgumentException("an entity id is an int: find(int), not a boxed Integer");
        if (!perHolder) throw new IllegalStateException(name + " is not per holder: find answers per-holder types");
        if (localOnly) {
            return CgNetwork.side() == CgNetwork.Side.SERVER ? CgNetEntities.server(this, playerOrEntity, false)
                    : CgNetEntities.client(this, CgEntityQueries.idOf(playerOrEntity), false);
        }
        if (CgNetwork.side() == CgNetwork.Side.SERVER) {
            UUID player = playerOrEntity instanceof UUID ? (UUID) playerOrEntity : CgPlayers.idOf(playerOrEntity);
            CgNetObject obj = holders.get(player != null ? player : playerOrEntity);
            return obj != null && !obj.isRemoved() ? obj : null;
        }
        Object entity = playerOrEntity instanceof UUID ? CgPlayers.player((UUID) playerOrEntity) : playerOrEntity;
        return entity == null ? null : CgNetObjects.clientHolder(this, CgEntityQueries.idOf(entity));
    }

    /**
     * A client: its object on entity {@code entityId}, the network id this client knows the entity by, or null while it
     * has none: what {@link #find(Object)} is for a caller holding the id. A sent type's is this client's copy; a
     * {@link #isLocal() local} type's is this client's own. Allocates nothing.
     *
     * <pre>{@code
     * CgNetObject ki = KI.find(entityId);
     * if (ki != null) bar.set(ki.get(KI_CURRENT));
     * }</pre>
     *
     * @throws IllegalStateException on the server's thread, where an id names no entity without its level: name the
     *                               entity, {@code find(entity)}
     */
    @Nullable
    public CgNetObject find(int entityId) {
        byId("find");
        return localOnly ? CgNetEntities.client(this, entityId, false) : CgNetObjects.clientHolder(this, entityId);
    }

    /**
     * A client: a {@link #isLocal() local} type's object on entity {@code entityId}, made if absent: what {@link #of(Object)}
     * is for a caller holding the id.
     *
     * <pre>{@code
     * float speed = CgEntityProperties.MOTION.of(entityId).get(CgEntityProperties.SPEED);
     * }</pre>
     *
     * @throws IllegalStateException for a sent type, whose objects only the server makes ({@link #find(int)} reads a
     *                               client's copy), or on the server's thread
     */
    public CgNetObject of(int entityId) {
        byId("of");
        if (!localOnly) throw new IllegalStateException(name + " is the server's to make: a client reads its objects with find(id)");
        CgNetObject obj = CgNetEntities.client(this, entityId, true);
        if (obj == null) throw new IllegalArgumentException("no entity " + entityId);
        return obj;
    }

    private void byId(String call) {
        if (!perHolder) throw new IllegalStateException(name + " is not per holder: " + call + " answers per-holder types");
        if (CgNetwork.onServerThread()) {
            throw new IllegalStateException(name + "." + call + "(id) on the server: an id names no entity without its level; "
                    + call + "(entity) names it");
        }
    }

    /** Whether its objects come one per holder ({@link Builder#perHolder}). */
    public boolean isPerHolder() {
        return perHolder;
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
        // an entity's saved values, from its own saved data: what it held when the game last saved it
        if (persisted && anchor == null) CgNetObjects.restore(obj, entity);
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
     * Client: the objects of this type this client sees now; none outside a world.
     *
     * <pre>{@code
     * for (CgNetObject turret : Turret.TYPE.visible()) drawRangeRing(turret);
     * }</pre>
     */
    public List<CgNetObject> visible() {
        return CgNetObjects.visible(this);
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

    /** Tells {@code obj}'s behaviour and the type's listeners of event {@code index}, on this side. */
    @SuppressWarnings("unchecked")
    void deliver(CgNetObject obj, int index, @Nullable Object payload) {
        CgNetEvent<Object> event = (CgNetEvent<Object>) events[index];
        if (obj.behaviour != null) {
            try {
                obj.behaviour.event(obj, event, payload);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] the behaviour of {} failed on {}", obj, event, failed);
            }
        }
        for (BiConsumer<CgNetObject, Object> listener : eventListeners[index]) {
            try {
                listener.accept(obj, payload);
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] an event listener of {} failed", name, failed);
            }
        }
    }

    /** Whether {@code b} declares what this type is: its fields, events and commands by name and rule, and its rules. */
    private boolean sameShape(Builder b) {
        if (fields.length != b.fields.size() || events.length != b.events.size()) return false;
        for (int i = 0; i < fields.length; i++) {
            CgNetField<?> f = fields[i], g = b.fields.get(i);
            if (!f.name.equals(g.name) || f.kind != g.kind || f.initialOnly != g.initialOnly || f.ownerOnly != g.ownerOnly
                    || f.skipOwner != g.skipOwner || f.serverOnly != g.serverOnly || f.local != g.local
                    || (f.command == null) != (g.command == null) || f.fallbackBits != g.fallbackBits
                    || !Objects.equals(f.fallback, g.fallback)) {
                return false;
            }
        }
        for (int i = 0; i < events.length; i++) if (!events[i].name.equals(b.events.get(i).name)) return false;
        List<String> commandNames = new ArrayList<>();
        for (CgNetCommand<?> c : b.commands) commandNames.add(c.name);
        for (CgNetField<?> f : b.fields) if (f.command != null) commandNames.add(f.command.name);
        if (commands.length != commandNames.size()) return false;
        for (int i = 0; i < commands.length; i++) if (!commands[i].name.equals(commandNames.get(i))) return false;
        return range == b.range && persisted == b.persisted && perHolder == b.perHolder && copyOnDeath == b.copyOnDeath
                && visibleToTrackers == b.visibleToTrackers && linger == b.linger && predictionTimeout == b.predictionTimeout
                && keepRefused == b.keepRefused && (entityTypes == null) == (b.entityTypes == null)
                && (visibleTo == null) == (b.visibleTo == null);
    }

    /**
     * Its field named {@code name}: how code holding a type declared from data, by {@link Builder#buildOnce()}, reaches
     * its fields.
     *
     * @throws IllegalArgumentException naming the type, where it lists no such field
     */
    public CgNetField<?> field(String name) {
        for (CgNetField<?> field : fields) if (field.name.equals(name)) return field;
        throw new IllegalArgumentException(this.name + " has no field " + name);
    }

    /** Its event named {@code name}. */
    public CgNetEvent<?> event(String name) {
        for (CgNetEvent<?> event : events) if (event.name.equals(name)) return event;
        throw new IllegalArgumentException(this.name + " has no event " + name);
    }

    /** Counts it in the per-tick traffic from its first object or refusal on. */
    void markUsed() {
        if (used) return;
        synchronized (USED) {
            if (!used) {
                USED.add(this);
                used = true;
            }
        }
    }

    /** Whether its fields are all {@link CgNetField#local()}: never sent, each side keeping its own objects. */
    public boolean isLocal() {
        return localOnly;
    }

    /** Every declared type, in declaration order. */
    public static List<CgNetType> all() {
        return Collections.unmodifiableList(REGISTERED);
    }

    /** The declared type named {@code name}, or null: what a frame, a prediction or an asset names. */
    @Nullable
    public static CgNetType named(String name) {
        return BY_NAME.get(name);
    }

    @Override
    public String toString() {
        return "CgNetType[" + name + "]";
    }
}
