package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Collection;
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

import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.platform.service.CgStorageLayout;
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.variable.CgVar;

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
public final class CgNetType<T> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** Whether an untouched value of a {@code comparesOnTouch()} type is checked at its last unwatch. */
    static final boolean DEVELOPMENT = CgVar.bool("crystalgraphics.net.checkTouch", false, CgVar.DEV | CgVar.RESTART,
            "Logs a comparesOnTouch type's value that changed without touch(model), when its last watcher leaves.").get();

    static final List<CgNetType<?>> REGISTERED = new CopyOnWriteArrayList<>();
    private static final Map<String, CgNetType<?>> BY_NAME = new ConcurrentHashMap<>();
    /** The types {@link Builder#onEntities} put on entities: what an entity's arrival asks. */
    static final List<CgNetType<?>> ON_ENTITIES = new CopyOnWriteArrayList<>();
    /** The persisted per-holder types: what an entity being saved is asked for. */
    static final List<CgNetType<?>> SAVED_ON_ENTITIES = new CopyOnWriteArrayList<>();
    /** Where what saves goes unless its namespace names a layout ({@link #storage}). */
    static final CgStorageLayout DEFAULT_STORAGE = CgStorageLayout.of("crystalgraphics");
    private static final Map<String, CgStorageLayout> STORAGE = new ConcurrentHashMap<>();
    /** The types that have had an object or a refusal: the per-tick counters' loop, never every declared type. */
    static final List<CgNetType<?>> USED = new CopyOnWriteArrayList<>();

    final String name;
    final String namespace;
    final int index;
    final CgNetField<?>[] fields;
    final CgNetEvent<?>[] events;
    final CgNetCommand<?>[] commands;
    /** Blocks within which a point anchor's object is seen; negative for its whole dimension. */
    final double range;
    /** Whether it saves any field: its objects outlive the session. */
    final boolean persisted;
    /** By field index: whether the field is saved, by its own {@code persisted()} or the type's. */
    final boolean[] saves;
    /** Its own saved form, in place of each saved field by its codec; null for that. */
    @Nullable
    final CgNetSaveForm saveForm;
    @Nullable
    final BiPredicate<CgNetObject, CgPeer> visibleTo;
    final boolean perHolder;
    final boolean copyOnDeath;
    final boolean visibleToTrackers;
    /** The indices of its list fields: their operations are cleared after each send. */
    final int[] lists;
    /** Seen only by its watchers. */
    final boolean watched;
    /** It has a field sent only to its watchers, or is {@link #watched} whole: it keeps watchers. */
    final boolean keepsWatchers;
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
    /** A codec type's keys as fields and its instances; null for a type declared field by field. */
    @Nullable
    final CgNetModel<T> model;
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
    /** By command index: a {@link Handler} or a call's {@link CgNetCall.Handler}. */
    final Object[] commandHandlers;
    /** By command index: who may send it, asked with its subject; null for anyone its rules let through. */
    final BiPredicate<Object, CgPeer>[] guards;

    @SuppressWarnings("unchecked")
    private CgNetType(Builder<T> b, int index) {
        this.name = b.name;
        this.namespace = b.name.substring(0, b.name.indexOf(':'));
        this.index = index;
        this.fields = b.fields.toArray(new CgNetField<?>[0]);
        this.events = b.events.toArray(new CgNetEvent<?>[0]);
        List<CgNetCommand<?>> commands = new ArrayList<>(b.commands);
        for (CgNetField<?> field : fields) if (field.command != null) commands.add(field.command);
        this.commands = commands.toArray(new CgNetCommand<?>[0]);
        this.range = b.range;
        this.saves = new boolean[fields.length];
        boolean any = false;
        for (int i = 0; i < fields.length; i++) {
            saves[i] = !fields[i].local && (b.persisted || fields[i].persisted);
            any |= saves[i];
        }
        this.persisted = any;
        this.saveForm = b.saveForm;
        this.visibleTo = b.visibleTo;
        this.perHolder = b.perHolder;
        this.copyOnDeath = b.copyOnDeath;
        this.visibleToTrackers = b.visibleToTrackers;
        this.watched = b.watched;
        int listCount = 0;
        for (CgNetField<?> field : fields) if (field instanceof CgNetList) listCount++;
        this.lists = new int[listCount];
        for (int i = 0, n = 0; i < fields.length; i++) if (fields[i] instanceof CgNetList) lists[n++] = i;
        boolean anyWatched = b.watched;
        for (CgNetField<?> field : fields) anyWatched |= field.watched;
        this.keepsWatchers = anyWatched;
        this.linger = b.linger;
        this.predictionTimeout = b.predictionTimeout;
        this.keepRefused = b.keepRefused;
        this.entityTypes = b.entityTypes;
        boolean local = fields.length > 0;
        for (CgNetField<?> field : fields) local &= field.local;
        this.localOnly = local;
        if (local && keepsWatchers) throw new IllegalStateException(name + " is never sent: it cannot be watched");
        this.changeListeners = new List[fields.length];
        for (int i = 0; i < fields.length; i++) changeListeners[i] = new CopyOnWriteArrayList<>();
        this.eventListeners = new List[events.length];
        for (int i = 0; i < events.length; i++) eventListeners[i] = new CopyOnWriteArrayList<>();
        this.commandHandlers = new Object[this.commands.length];
        this.guards = new BiPredicate[this.commands.length];
        this.traceLive = CgTraceNames.intern("net.objects/" + name);
        this.traceViewers = CgTraceNames.intern("net.viewers/" + name);
        this.traceBytes = CgTraceNames.intern("net.bytes/" + name);
        this.traceRefused = CgTraceNames.intern("net.refused/" + name);
        this.model = b.model;
    }

    /** Commands and predictions of this type refused, since the server started. */
    long refusals() {
        return refusedPredictions + refusedCommands;
    }

    /** Starts declaring a type named {@code namespace:path}, field by field: its handlers are handed its objects. */
    public static Builder<CgNetObject> define(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A type name is namespace:path in lowercase, not '" + name + "'");
        }
        return new Builder<>(name);
    }

    /**
     * Starts declaring a type from a data class's codec: each key a field, a primitive one unboxed, a key whose codec is
     * an object codec a part (a child object of its own, watched by itself); its handlers are handed the instance.
     *
     * <pre>{@code
     * public static final CgObjectCodec<PetData> CODEC = CgCodecs.fieldsOf(PetData.class)
     *         .serverOnly("ownerNotes").readOnly("health").range("maxHealth", 1, 100).build();
     * public static final CgNetType<PetData> PET = CgNetType.define("mymod:pet", PetData.CODEC).build();
     *
     * CgNetObject obj = PET.over(pet);                 // server: the object for this instance
     * obj.watch(player);                               // its getters are read once a tick while watched
     * PET.allowEdits((pet, player) -> pet.owner.equals(player.id()));
     * PET.afterEdit((pet, player) -> pet.markDirty());
     * PetData mine = PET.instance(clientObject);       // client: its own copy, filled as fields arrive
     * }</pre>
     *
     * <ul>
     *   <li>Seen by its watchers only: {@link #over} answers the object, made now and ended at its last unwatch.</li>
     *   <li>A key's {@code serverOnly} never leaves the server; a {@code readOnly} key cannot be edited; every other
     *       settable key may be set by a watcher, inside its {@code range} and its type's {@link #allowEdits}.</li>
     *   <li>A primitive is compared by value; anything else by its encoding against the last sent, every tick, unless
     *       {@link Builder#comparesOnTouch()}.</li>
     *   <li>A part's type is {@code name/key}, in lowercase: a second key of that spelling fails at build.</li>
     * </ul>
     */
    public static <T> Builder<T> define(String name, CgObjectCodec<T> codec) {
        Builder<T> b = (Builder<T>) (Builder<?>) define(name);
        b.codec = Objects.requireNonNull(codec, "codec");
        b.watched = true;
        return b;
    }

    /** A field a codec type's server works out from its instance: {@link Builder#computed}. */
    record Computed<T, V>(CgNetField<V> field, Function<? super T, ? extends V> value) {
    }

    /** Declares a type. */
    public static final class Builder<T> {
        private final String name;
        private final List<CgNetField<?>> fields = new ArrayList<>();
        private final List<CgNetEvent<?>> events = new ArrayList<>();
        private final List<CgNetCommand<?>> commands = new ArrayList<>();
        private double range = -1;
        private boolean persisted;
        @Nullable
        private CgNetSaveForm saveForm;
        @Nullable
        private BiPredicate<CgNetObject, CgPeer> visibleTo;
        private boolean perHolder;
        private boolean copyOnDeath;
        private boolean visibleToTrackers, watched;
        private int linger = 200;
        private int predictionTimeout = 40;
        private boolean keepRefused;
        @Nullable
        private Predicate<String> entityTypes;
        @Nullable
        private CgObjectCodec<T> codec;
        private boolean comparesOnTouch;
        private final List<Computed<T, ?>> computed = new ArrayList<>();
        @Nullable
        private CgNetModel<T> model;

        private Builder(String name) {
            this.name = name;
        }

        /** Its fields, in the order they are sent and applied. */
        public Builder<T> fields(CgNetField<?>... fields) {
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
        public Builder<T> events(CgNetEvent<?>... events) {
            for (CgNetEvent<?> event : events) {
                for (CgNetEvent<?> other : this.events) {
                    if (other.name.equals(event.name)) throw new IllegalArgumentException(name + " lists " + event.name + " twice");
                }
                this.events.add(Objects.requireNonNull(event, "event"));
            }
            return this;
        }

        /** What its clients may ask of its objects. */
        public Builder<T> commands(CgNetCommand<?>... commands) {
            for (CgNetCommand<?> command : commands) {
                for (CgNetCommand<?> other : this.commands) {
                    if (other.name.equals(command.name)) throw new IllegalArgumentException(name + " lists " + command.name + " twice");
                }
                this.commands.add(Objects.requireNonNull(command, "command"));
            }
            return this;
        }

        /** Its calls: commands that answer, {@link CgNetCall}. */
        public Builder<T> calls(CgNetCall<?, ?>... calls) {
            for (CgNetCall<?, ?> call : calls) commands(Objects.requireNonNull(call, "call").command);
            return this;
        }

        /** A point-anchored object is seen within {@code blocks}; without this, by its whole dimension. */
        public Builder<T> range(double blocks) {
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
        public Builder<T> visibleTo(BiPredicate<CgNetObject, CgPeer> rule) {
            visibleTo = Objects.requireNonNull(rule, "rule");
            return this;
        }

        /**
         * Every field it lists is saved ({@link CgNetField#persisted()}, field by field, saves only those): its objects are
         * saved with the world and back the next time it starts; a {@link CgNetObject#fire() fired} one never is. A
         * per-holder type's object on an entity other than a player is saved with that entity instead
         * ({@code CgEntitySaves}): it travels with the entity through chunk unloads and dies with it.
         *
         * <pre>{@code
         * WARD = CgNetType.define("mymod:ward").fields(POWER, OWNER_NAME).persisted().build();   // all of it lasts
         * }</pre>
         *
         * <ul>
         *   <li>Its {@code local()} fields are never saved.</li>
         *   <li>For fields shared with other types (a cue's), this saves them here alone; a field's own {@code persisted()}
         *       saves it in every type listing it.</li>
         * </ul>
         */
        public Builder<T> persisted() {
            persisted = true;
            return this;
        }

        /**
         * Saves its objects in a form of its own, written and read by {@code form}, in place of each saved field under its
         * name. The fields saved ({@code persisted()}) still decide when a change is saved. See {@link CgNetSaveForm}.
         *
         * <pre>{@code
         * .fields(X.persisted(), Y.persisted()).savedAs(new PointAsText())
         * }</pre>
         *
         * @throws IllegalStateException at {@code build()} when no field is saved: the form would never be asked
         */
        public Builder<T> savedAs(CgNetSaveForm form) {
            saveForm = Objects.requireNonNull(form, "form");
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
        public Builder<T> perHolder() {
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
        public Builder<T> onEntities(Predicate<String> entityTypes) {
            this.entityTypes = Objects.requireNonNull(entityTypes, "entityTypes");
            perHolder = true;
            return this;
        }

        /** A player-anchored object outlives its player's death; without this it despawns when they die. */
        public Builder<T> copyOnDeath() {
            copyOnDeath = true;
            return this;
        }

        /** A player-anchored object is seen by the players tracking its player too, not only by them. */
        public Builder<T> visibleToTrackers() {
            visibleToTrackers = true;
            return this;
        }

        /**
         * Seen only by the players watching each object ({@link CgNetObject#watch}): a machine's screen, a container,
         * sent to who has it open and to nobody else in range. Its commands and predicted fields are its watchers' too,
         * unless a command says {@code fromOwner()} or {@code fromAnyViewer()}.
         *
         * <pre>{@code
         * CgNetType GRINDER = CgNetType.define("mymod:grinder").fields(PROGRESS, FUEL).range(8).watched().build();
         * grinder.watch(player);      // opened: sent the whole object at the end of the tick
         * grinder.unwatch(player);    // closed: despawned on their client
         * }</pre>
         *
         * <ul>
         *   <li>A watcher who leaves, changes dimension or goes out of its range is unwatched, and the server behaviour
         *       hears why ({@link CgNetBehaviour#unwatched}).</li>
         *   <li>A watched object is first in a watcher's send budget: an open screen never waits behind the world.</li>
         * </ul>
         */
        public Builder<T> watched() {
            watched = true;
            return this;
        }

        /** Server ticks a graceful despawn lets the server behaviour finish in front of its viewers; 200 unless said. */
        public Builder<T> linger(int ticks) {
            if (ticks < 0) throw new IllegalArgumentException("linger: " + ticks);
            linger = ticks;
            return this;
        }

        /** Client ticks a prediction waits for the server's answer before it counts as refused; 40 unless said. */
        public Builder<T> predictionTimeout(int ticks) {
            if (ticks <= 0) throw new IllegalArgumentException("predictionTimeout must be positive: " + ticks);
            predictionTimeout = ticks;
            return this;
        }

        /**
         * A codec type's non-primitive values are compared only after {@link CgNetType#touch}, instead of encoded every
         * tick: for an instance too large to encode each tick, as Unreal's push model. Run with
         * {@code -Dcrystalgraphics.net.checkTouch=true} to log a value that changed without one.
         *
         * <pre>{@code
         * NPC = CgNetType.define("mymod:npc", Npc.CODEC).comparesOnTouch().build();
         * npc.lines.add(text);
         * NPC.touch(npc);
         * }</pre>
         */
        public Builder<T> comparesOnTouch() {
            comparesOnTouch = true;
            return this;
        }

        /**
         * {@code field}, set by the server from each instance once a tick while watched: a value worked out, which the
         * instance does not hold. A codec type's only.
         *
         * <pre>{@code
         * static final CgNetField<Float> HEALTH_PART = CgNetField.ofFloat("healthPart");
         * PET = CgNetType.define("mymod:pet", PetData.CODEC).computed(HEALTH_PART, p -> p.health / p.maxHealth).build();
         * }</pre>
         */
        public <V> Builder<T> computed(CgNetField<V> field, Function<? super T, ? extends V> value) {
            if (codec == null) throw new IllegalStateException("computed(...) is for a type defined from a codec");
            computed.add(new Computed<>(field, value));
            return this;
        }

        /** A refused prediction ends gracefully, finishing what it shows, instead of at once. */
        public Builder<T> keepRefused() {
            keepRefused = true;
            return this;
        }

        public CgNetType<T> build() {
            synchronized (REGISTERED) {
                if (BY_NAME.containsKey(name)) throw new IllegalStateException("Type '" + name + "' is already declared");
                if (codec != null) {
                    // the codec's keys first, then the fields listed by hand
                    model = CgNetModel.compile(name, codec, computed, comparesOnTouch);
                    List<CgNetField<?>> listed = new ArrayList<>(fields);
                    fields.clear();
                    fields(model.fields().toArray(new CgNetField<?>[0]));
                    for (CgNetField<?> field : listed) if (!fields.contains(field)) fields(field);
                    for (CgNetCall<?, ?> call : model.calls()) calls(call);
                }
                CgNetType<T> type = register();
                if (model != null) model.bind(type);
                return type;
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
        @SuppressWarnings("unchecked")
        public CgNetType<T> buildOnce() {
            synchronized (REGISTERED) {
                CgNetType declared = BY_NAME.get(name);
                if (declared == null) return register();
                if (!declared.sameShape(this)) {
                    throw new IllegalStateException(name + " changed its fields, events or rules while declared: restart to change them");
                }
                return (CgNetType<T>) declared;
            }
        }

        private CgNetType<T> register() {
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
                CgNetType<T> type = new CgNetType<>(this, REGISTERED.size());
                if (saveForm != null && !type.persisted) {
                    throw new IllegalStateException(name + " has a saved form and saves no field: mark the fields it saves persisted()");
                }
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
        // what it held when it was last saved: a player's in their file, online or not; an entity's in its own data
        if (persisted && key instanceof UUID) {
            PlayerFiles.restore(obj, (UUID) key);
            obj.offline = CgPlayers.player((UUID) key) == null;
        } else if (persisted && !(key instanceof ChunkKey)) {
            CgNetEntities.restore(obj, entity);
        }
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
    // ── A codec type ────────────────────────────────────────────────────────────────────────────

    /**
     * The object standing for {@code instance}, by identity: made now if it has none, and ended at its last unwatch
     * (or a tick after this call, if nobody watches it by then). Server thread.
     *
     * <pre>{@code
     * PET.over(pet).watch(player);
     * }</pre>
     */
    public CgNetObject over(T instance) {
        return requireModel().over(Objects.requireNonNull(instance, "instance"), instance);
    }

    /**
     * The object of {@code root}'s part under {@code key} (a key whose codec is an object codec), made now if it has
     * none: watch it for a watcher to be sent that part. Server thread.
     *
     * <pre>{@code
     * NPC.over(npc).watch(player);
     * NPC.part(npc, "stats").watch(player);          // the stats tab, opened
     * }</pre>
     */
    public CgNetObject part(T root, String key) {
        CgNetModel<T> m = requireModel();
        for (CgNetModel.Key k : m.keys) {
            if (k.kind == CgNetModel.Kind.PART && k.name.equals(key)) {
                Object held = k.get.apply(root);
                if (held == null) throw new IllegalStateException(name + "'s '" + key + "' holds null");
                return k.part.model.over(held, root);
            }
        }
        throw new IllegalArgumentException(name + " has no part '" + key + "'");
    }

    /**
     * The object of one entry of {@code root}'s map under {@code key}, made now if it has none: watch it for a watcher
     * to be sent that entry. A watcher is sent the map's keys alone; a client opens an entry itself with
     * {@link #open}. Server thread.
     */
    public CgNetObject entry(T root, String key, Object mapKey) {
        CgNetModel<T> m = requireModel();
        CgNetObject obj = over(root);
        return m.entryObject(obj, mapKey(m, key), mapKey);
    }

    /**
     * Client, a watcher: asks the server for one entry of a map key, which then arrives into this client's instance.
     *
     * <pre>{@code
     * BANK.open(bank, "accounts", "Vegeta").then(ok -> show(BANK.instance(bank).accounts.get("Vegeta")));
     * }</pre>
     */
    public CgReply<Boolean> open(CgNetObject obj, String key, Object mapKey) {
        return obj.call(mapKey(requireModel(), key).open, mapKey);
    }

    /** Client, a watcher: adds or replaces one entry of a map key, under {@link #allowEdits}. */
    public CgReply<Boolean> put(CgNetObject obj, String key, Object mapKey, Object value) {
        CgNetModel.Put put = new CgNetModel.Put();
        put.key = mapKey;
        put.value = value;
        return obj.call(mapKey(requireModel(), key).put, put);
    }

    /** Client, a watcher: removes one entry of a map key, under {@link #allowEdits}. */
    public CgReply<Boolean> remove(CgNetObject obj, String key, Object mapKey) {
        return obj.call(mapKey(requireModel(), key).remove, mapKey);
    }

    private CgNetModel.Key mapKey(CgNetModel<T> m, String key) {
        for (CgNetModel.Key k : m.keys) if (k.kind == CgNetModel.Kind.MAP && k.name.equals(key)) return k;
        throw new IllegalArgumentException(name + " has no map key '" + key + "'");
    }

    /** {@code instance}'s values are compared at the next tick: a {@link Builder#comparesOnTouch()} type's. */
    public void touch(T instance) {
        requireModel().touch(instance);
    }

    /**
     * Who may change values of this type's objects, and of its parts unless a part says otherwise; a watcher refused
     * is sent the server's value back. Every watcher may, without one.
     *
     * <pre>{@code
     * PET.allowEdits((pet, player) -> pet.owner.equals(player.id()));
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public CgNetType<T> allowEdits(BiPredicate<? super T, CgPeer> guard) {
        CgNetModel<T> m = requireModel();
        m.mayEdit = (BiPredicate<Object, CgPeer>) guard;
        for (CgNetModel.Key k : m.keys) if (k.part != null && k.part.model.mayEdit == null) k.part.model.mayEdit = m.mayEdit;
        return this;
    }

    /**
     * Who may change one part's values, asked with the root instance: a tab only some players may edit.
     *
     * <pre>{@code
     * NPC.allowEdits(Stats.CODEC, (npc, player) -> player.hasPermission("mymod.npc.stats"));
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public CgNetType<T> allowEdits(CgObjectCodec<?> part, BiPredicate<? super T, CgPeer> guard) {
        CgNetModel<T> m = requireModel();
        for (CgNetModel.Key k : m.keys) {
            if (k.part != null && k.codec == part) {
                k.part.model.mayEdit = (BiPredicate<Object, CgPeer>) guard;
                return this;
            }
        }
        throw new IllegalArgumentException(name + " has no part of that codec");
    }

    /**
     * Run on the server after a watcher's edit is stored into the instance (this type's or a part's), with the root
     * instance and who edited it.
     *
     * <pre>{@code
     * PET.afterEdit((pet, player) -> pet.markDirty());
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public CgNetType<T> afterEdit(BiConsumer<? super T, CgPeer> after) {
        CgNetModel<T> m = requireModel();
        m.afterEdit.add((BiConsumer<Object, CgPeer>) after);
        for (CgNetModel.Key k : m.keys) if (k.part != null) k.part.model.afterEdit.add((BiConsumer<Object, CgPeer>) after);
        return this;
    }

    /**
     * {@code obj}'s instance: on the server the one {@link #over} was given, on a client its own copy, made by the
     * codec's factory and filled through the codec's setters as fields arrive, the same instance for the object's life.
     * Null for a codec with no factory, on a client.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public T instance(CgNetObject obj) {
        if (obj.type != this) throw new IllegalArgumentException(obj + " is not a " + name);
        CgNetModel<T> m = requireModel();
        return (T) (obj.server ? (obj.bound == null ? null : obj.bound.model) : m.instance(obj));
    }

    private CgNetModel<T> requireModel() {
        if (model == null) throw new IllegalStateException(name + " is declared field by field: over, part and touch are a codec type's");
        return model;
    }

    public CgNetType<T> onServer(Function<CgNetObject, CgNetBehaviour> factory) {
        server = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /**
     * On the server: which predictions of this type to make real, asked with the object the client proposed, not yet
     * started, owned by the predicting player. It may change the proposal's fields before answering. Without this,
     * every prediction is refused. At init.
     */
    public CgNetType<T> acceptPredictionsFrom(BiPredicate<CgPeer, CgNetObject> rule) {
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
    public CgNetType<T> onClient(Function<CgNetObject, CgNetBehaviour> factory) {
        client = Objects.requireNonNull(factory, "factory");
        return this;
    }

    /**
     * On a client: called with {@code field}'s value whenever a frame changes it, and with each value a spawn carries.
     * In addition to any behaviour.
     */
    @SuppressWarnings("unchecked")
    public <V> CgNetType<T> onChange(CgNetField<V> field, BiConsumer<CgNetObject, V> listener) {
        int i = indexOf(field);
        if (i < 0) throw new IllegalArgumentException(name + " does not list " + field.name);
        changeListeners[i].add((BiConsumer<CgNetObject, Object>) (BiConsumer<CgNetObject, ?>) listener);
        return this;
    }

    /** On a client: called with each {@code event} an object of this type receives. In addition to any behaviour. */
    @SuppressWarnings("unchecked")
    public <P> CgNetType<T> onEvent(CgNetEvent<P> event, BiConsumer<CgNetObject, P> listener) {
        eventListeners[requireEvent(event)].add((BiConsumer<CgNetObject, Object>) (BiConsumer<CgNetObject, ?>) listener);
        return this;
    }

    /** A command's server handler: the subject (the object, or a codec type's instance), who sent it, its value. */
    @FunctionalInterface
    public interface Handler<T, P> {
        void handle(T subject, CgPeer sender, @Nullable P value);
    }

    /**
     * On the server: what {@code command} does. Replaces any earlier handler. At init.
     *
     * <pre>{@code
     * TURRET.onCommand(AIM, (turret, player, aim) -> turret.set(TARGET, aim.target()));   // a type field by field
     * PET.onCommand(RENAME, (pet, player, name) -> pet.name = name);                      // a codec type: its instance
     * }</pre>
     */
    public <P> CgNetType<T> onCommand(CgNetCommand<P> command, Handler<? super T, P> handler) {
        if (command.call != null) throw new IllegalArgumentException(command.name + " is a call: handle it with onCall");
        commandHandlers[requireCommand(command)] = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * On the server: how {@code call} is answered. Replaces any earlier handler. At init.
     *
     * <pre>{@code
     * SHOP.onCall(SEARCH, (shop, player, text, reply) -> reply.ok(shop.find(text)));
     * SHOP.onCall(BUY, (shop, player, item, reply) -> {
     *     if (!shop.has(item)) reply.fail("mymod/out_of_stock");
     *     else reply.ok(shop.sell(item, player.id()));
     * });
     * }</pre>
     */
    public <A, R> CgNetType<T> onCall(CgNetCall<A, R> call, CgNetCall.Handler<? super T, A, R> handler) {
        commandHandlers[requireCommand(call.command)] = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Who may send {@code command}, beyond its own rules (the owner, or a watcher of a watched type): refused with
     * {@link CgNetCommand#REFUSED_NOT_ALLOWED} otherwise.
     *
     * <pre>{@code
     * PET.allow(RENAME, (pet, player) -> pet.owner.equals(player.id()));
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public CgNetType<T> allow(CgNetCommand<?> command, BiPredicate<? super T, CgPeer> guard) {
        guards[requireCommand(command)] = (BiPredicate<Object, CgPeer>) guard;
        return this;
    }

    /** {@link #allow(CgNetCommand, BiPredicate)} for a call. */
    public CgNetType<T> allow(CgNetCall<?, ?> call, BiPredicate<? super T, CgPeer> guard) {
        return allow(call.command, guard);
    }

    /** What a handler of {@code obj} is handed: a codec type's instance, or the object itself. */
    Object subject(CgNetObject obj) {
        return model != null && obj.bound != null ? obj.bound.model : obj;
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
    private boolean sameShape(Builder<?> b) {
        if (fields.length != b.fields.size() || events.length != b.events.size()) return false;
        for (int i = 0; i < fields.length; i++) {
            CgNetField<?> f = fields[i], g = b.fields.get(i);
            if (!f.name.equals(g.name) || f.kind != g.kind || f.initialOnly != g.initialOnly || f.ownerOnly != g.ownerOnly
                    || f.skipOwner != g.skipOwner || f.serverOnly != g.serverOnly || f.local != g.local || f.watched != g.watched
                    || (f.command == null) != (g.command == null) || f.fallbackBits != g.fallbackBits
                    || !Objects.equals(f.fallback, g.fallback) || saves[i] != (!g.local && (b.persisted || g.persisted))) {
                return false;
            }
        }
        for (int i = 0; i < events.length; i++) if (!events[i].name.equals(b.events.get(i).name)) return false;
        List<String> commandNames = new ArrayList<>();
        for (CgNetCommand<?> c : b.commands) commandNames.add(c.name);
        for (CgNetField<?> f : b.fields) if (f.command != null) commandNames.add(f.command.name);
        if (commands.length != commandNames.size()) return false;
        for (int i = 0; i < commands.length; i++) if (!commands[i].name.equals(commandNames.get(i))) return false;
        // whether a field saves is compared above: the type's persisted() and each field's both decide it
        return range == b.range && saveForm == b.saveForm && perHolder == b.perHolder && copyOnDeath == b.copyOnDeath
                && visibleToTrackers == b.visibleToTrackers && watched == b.watched && linger == b.linger && predictionTimeout == b.predictionTimeout
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
    public static List<CgNetType<?>> all() {
        return Collections.unmodifiableList(REGISTERED);
    }

    /**
     * Saves a namespace's objects under a folder of its own in the world, instead of {@code crystalgraphics}'s: each
     * player's under {@code <world>/<root>/players/<uuid>.jsonc}, its world objects under
     * {@code <world>/<root>/objects/<type>.jsonc}. Entities' objects are unaffected: they save with the entity.
     *
     * <pre>{@code
     * public static final CgStorageLayout LAYOUT = CgStorageLayout.of("rpgcore");
     * CgNetType.storage("rpgcore", LAYOUT);      // at init, before the server starts
     * // rpgcore:ki on a player   -> <world>/rpgcore/players/<uuid>.jsonc
     * // rpgcore:shrine           -> <world>/rpgcore/objects/shrine.jsonc
     * }</pre>
     *
     * <ul>
     *   <li>Without it, a namespace saves to {@code <world>/crystalgraphics/players/<uuid>.jsonc}, shared with every other
     *       such namespace, and {@code <world>/crystalgraphics/objects/<namespace>/<type>.jsonc}.</li>
     *   <li>Call it at init: what a running server already saved stays where it was.</li>
     *   <li>One layout per namespace: a second, different one throws. Several namespaces may share one layout.</li>
     *   <li>{@code players/} and {@code objects/} are this layer's: keep files of your own beside them.</li>
     * </ul>
     */
    public static void storage(String namespace, CgStorageLayout layout) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(layout, "layout");
        CgStorageLayout before = STORAGE.putIfAbsent(namespace, layout);
        if (before != null && before != layout) {
            throw new IllegalStateException(namespace + " already saves under " + before.root() + ", not " + layout.root());
        }
    }

    /** The layout {@code namespace} saves under: its own, or {@code crystalgraphics}'s. */
    static CgStorageLayout storageOf(String namespace) {
        CgStorageLayout layout = STORAGE.get(namespace);
        return layout != null ? layout : DEFAULT_STORAGE;
    }

    /** Every layout something may be saved under: the default, and each namespace's own. */
    static Collection<CgStorageLayout> storageLayouts() {
        Set<CgStorageLayout> all = new LinkedHashSet<>();
        all.add(DEFAULT_STORAGE);
        all.addAll(STORAGE.values());
        return all;
    }

    /** The declared type named {@code name}, or null: what a frame, a prediction or an asset names. */
    @Nullable
    public static CgNetType<?> named(String name) {
        return BY_NAME.get(name);
    }

    @Override
    public String toString() {
        return "CgNetType[" + name + "]";
    }
}
