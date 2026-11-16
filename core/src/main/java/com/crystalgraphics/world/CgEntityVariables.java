package com.crystalgraphics.world;

import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.BOOL;
import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.FLOAT;
import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.INT;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.object.CgAnchor;
import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.tag.CgTagCounts;

/**
 * The standard variables: everything an entity's {@link CgEntityState} says, named for a graph to read, so a graph needs
 * no code to follow the entity it animates. A graph binds its own variables to them, in its file's {@code entity:} block
 * or with {@link CgAnimGraph.Builder#entity}; the entity's actor sets each bound variable every frame before the graph
 * runs, on both sides. Unreal's gameplay tag property map, over the entity's state rather than its tags: the graph names
 * a property and what feeds it, and the owner sets it.
 *
 * <pre>{@code
 * # anim_graphs/mymod/hero.yaml
 * variables:
 *   speed: { type: float }
 *   in_air: { type: bool }
 * entity:
 *   speed: entity.speed           # the graph's speed, fed by the entity's
 *   in_air: entity.airborne
 * }</pre>
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .variables(SPEED, IN_AIR)
 *         .entity(SPEED, CgEntityVariables.SPEED)
 *         .entity(IN_AIR, CgEntityVariables.AIRBORNE);
 *
 * anim.onUpdate(CgEntityVariables.feed(state));   // anything that is no entity actor feeds its own instance so
 * }</pre>
 *
 * A mod's own value joins them under a name of its namespace, read off the host's entity, and a graph binds it the
 * same way:
 *
 * <pre>{@code
 * public static final CgEntityVariable KI = CgEntityVariables.register("mymod:ki", CgAnimVariable.Type.FLOAT,
 *         (state, entity) -> Mana.ki(entity)).replicated();          // replicated: only the server knows it
 *
 * entity:
 *   ki: mymod:ki
 * }</pre>
 *
 * <ul>
 *   <li>A name no variable has, a graph variable the graph does not declare, or a type that cannot take the value
 *       fails the graph at load, naming them. A float takes any value; a bool or an int only its own type.</li>
 *   <li>Events ({@link #JUMPED}, {@link #LANDED}, ...) are true for the one frame they happen in.</li>
 *   <li>Angles are degrees, speeds blocks a second, times seconds, as {@link CgEntityState} gives them.</li>
 * </ul>
 */
public final class CgEntityVariables {

    private static final Map<String, CgEntityVariable> BY_NAME = new LinkedHashMap<>();
    /** The replicated ones, by {@code replicatedIndex}. */
    private static final List<CgEntityVariable> REPLICATED = new ArrayList<>();

    // ── motion ──────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#speed}: along the ground, blocks a second. */
    public static final CgEntityVariable SPEED = standard("entity.speed", FLOAT, CgEntityState::speed);
    public static final CgEntityVariable VERTICAL_SPEED = standard("entity.vertical_speed", FLOAT, CgEntityState::verticalSpeed);
    /** {@link CgEntityState#moveDirection}: degrees against the body's facing, 90 left. */
    public static final CgEntityVariable MOVE_DIRECTION = standard("entity.move_direction", FLOAT, CgEntityState::moveDirection);
    public static final CgEntityVariable ACCELERATION = standard("entity.acceleration", FLOAT, CgEntityState::acceleration);
    public static final CgEntityVariable TURN_RATE = standard("entity.turn_rate", FLOAT, CgEntityState::turnRate);

    // ── intent ──────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#input}: how hard it means to move, 0 to 1. */
    public static final CgEntityVariable INPUT = standard("entity.input", FLOAT, CgEntityState::input);
    public static final CgEntityVariable INPUT_DIRECTION = standard("entity.input_direction", FLOAT, CgEntityState::inputDirection);
    public static final CgEntityVariable SPRINT_HELD = standard("entity.sprint_held", BOOL, s -> bool(s.sprintHeld()));
    public static final CgEntityVariable JUMP_HELD = standard("entity.jump_held", BOOL, s -> bool(s.jumpHeld()));

    // ── ground and air ──────────────────────────────────────────────────────────────────

    public static final CgEntityVariable GROUNDED = standard("entity.grounded", BOOL, s -> bool(s.grounded()));
    public static final CgEntityVariable AIRBORNE = standard("entity.airborne", BOOL, s -> bool(!s.grounded() && !s.riding()));
    public static final CgEntityVariable AIR_TIME = standard("entity.air_time", FLOAT, CgEntityState::airSeconds);
    public static final CgEntityVariable GROUND_DISTANCE = standard("entity.ground_distance", FLOAT, CgEntityState::groundDistance);
    public static final CgEntityVariable LAND_HEIGHT = standard("entity.land_height", FLOAT, CgEntityState::landHeight);

    // ── stance ──────────────────────────────────────────────────────────────────────────

    public static final CgEntityVariable SNEAKING = standard("entity.sneaking", BOOL, s -> bool(s.sneaking()));
    public static final CgEntityVariable SPRINTING = standard("entity.sprinting", BOOL, s -> bool(s.sprinting()));
    public static final CgEntityVariable SWIMMING = standard("entity.swimming", BOOL, s -> bool(s.swimming()));
    public static final CgEntityVariable CRAWLING = standard("entity.crawling", BOOL, s -> bool(s.crawling()));
    public static final CgEntityVariable SWIM_AMOUNT = standard("entity.swim_amount", FLOAT, CgEntityState::swimAmount);
    public static final CgEntityVariable GLIDING = standard("entity.gliding", BOOL, s -> bool(s.gliding()));
    public static final CgEntityVariable SPIN_ATTACK = standard("entity.spin_attack", BOOL, s -> bool(s.spinAttack()));
    public static final CgEntityVariable IN_WATER = standard("entity.in_water", BOOL, s -> bool(s.inWater()));
    public static final CgEntityVariable RIDING = standard("entity.riding", BOOL, s -> bool(s.riding()));
    public static final CgEntityVariable SLEEPING = standard("entity.sleeping", BOOL, s -> bool(s.sleeping()));

    // ── life ────────────────────────────────────────────────────────────────────────────

    public static final CgEntityVariable DEAD = standard("entity.dead", BOOL, s -> bool(s.dead()));
    public static final CgEntityVariable DEAD_TIME = standard("entity.dead_time", FLOAT, CgEntityState::secondsDead);
    /** {@link CgEntityState#secondsSinceHurt}: infinite before the first hit. */
    public static final CgEntityVariable HURT_TIME = standard("entity.hurt_time", FLOAT, CgEntityState::secondsSinceHurt);

    // ── look ────────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#headYaw}: the head against the body, degrees, 90 left. */
    public static final CgEntityVariable HEAD_YAW = standard("entity.head_yaw", FLOAT, CgEntityState::headYaw);
    public static final CgEntityVariable HEAD_PITCH = standard("entity.head_pitch", FLOAT, CgEntityState::headPitch);

    // ── hands ───────────────────────────────────────────────────────────────────────────

    public static final CgEntityVariable LEFT_HANDED = standard("entity.left_handed", BOOL, s -> bool(s.leftHanded()));
    /** The main hand's arm pose: a {@code CgEntityState.ARM_} constant. */
    public static final CgEntityVariable MAIN_ARM = standard("entity.main_arm", INT, s -> s.armPose(CgEntityState.MAIN));
    public static final CgEntityVariable OFF_ARM = standard("entity.off_arm", INT, s -> s.armPose(CgEntityState.OFF));
    public static final CgEntityVariable USING = standard("entity.using", BOOL,
            s -> bool(s.using(CgEntityState.MAIN) || s.using(CgEntityState.OFF)));
    /** The item in use's look in use: a {@code CgEntityFacts.USE_} constant. */
    public static final CgEntityVariable USE_ANIMATION = standard("entity.use_animation", INT, CgEntityState::useAnimation);
    public static final CgEntityVariable USE_TIME = standard("entity.use_time", FLOAT, CgEntityState::useSeconds);
    public static final CgEntityVariable USE_LENGTH = standard("entity.use_length", FLOAT, CgEntityState::useLength);
    public static final CgEntityVariable SWINGING = standard("entity.swinging", BOOL, s -> bool(s.swinging()));
    /** {@link CgEntityState#swing}: the swing's progress, 0 to 1. */
    public static final CgEntityVariable SWING = standard("entity.swing", FLOAT, CgEntityState::swing);
    public static final CgEntityVariable SWING_HAND = standard("entity.swing_hand", INT, CgEntityState::swingHand);

    // ── events, true for their frame ────────────────────────────────────────────────────

    public static final CgEntityVariable JUMPED = event("entity.jumped", CgEntityState.JUMPED);
    public static final CgEntityVariable LANDED = event("entity.landed", CgEntityState.LANDED);
    public static final CgEntityVariable HURT = event("entity.hurt", CgEntityState.HURT);
    public static final CgEntityVariable SWING_STARTED = event("entity.swing_started", CgEntityState.SWING_STARTED);
    public static final CgEntityVariable USE_STARTED = event("entity.use_started", CgEntityState.USE_STARTED);
    public static final CgEntityVariable USE_STOPPED = event("entity.use_stopped", CgEntityState.USE_STOPPED);
    public static final CgEntityVariable MOUNTED = event("entity.mounted", CgEntityState.MOUNTED);
    public static final CgEntityVariable DISMOUNTED = event("entity.dismounted", CgEntityState.DISMOUNTED);
    public static final CgEntityVariable DIED = event("entity.died", CgEntityState.DIED);

    private CgEntityVariables() {
    }

    /** A standard variable's reader: the entity's state alone. */
    @FunctionalInterface
    private interface StateReader {
        float read(CgEntityState state);
    }

    private static CgEntityVariable standard(String name, CgAnimVariable.Type type, StateReader reader) {
        CgEntityVariable v = new CgEntityVariable(name, type, (state, entity) -> reader.read(state));
        BY_NAME.put(name, v);
        return v;
    }

    /**
     * A mod's own variable, {@code namespace:path}, read off the host's entity each frame on each side (or on the server
     * alone, {@link CgEntityVariable#replicated}). At load, before a graph naming it loads.
     *
     * <pre>{@code
     * public static final CgEntityVariable FLIGHT_GEAR = CgEntityVariables.register("mymod:flight_gear",
     *         CgAnimVariable.Type.INT, (state, entity) -> Flight.gear(entity));
     * }</pre>
     *
     * @throws IllegalArgumentException for a name not {@code namespace:path}, or one taken
     */
    public static synchronized CgEntityVariable register(String name, CgAnimVariable.Type type, CgEntityVariable.Reader reader) {
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1 || name.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("a mod's entity variable is namespace:path, not " + name);
        }
        if (BY_NAME.containsKey(name)) throw new IllegalArgumentException("entity variable " + name + " is registered twice");
        CgEntityVariable v = new CgEntityVariable(name, type, reader);
        BY_NAME.put(name, v);
        return v;
    }

    static synchronized void replicate(CgEntityVariable v) {
        if (v.replicatedIndex >= 0) return;
        if (v.name().startsWith("entity.")) throw new IllegalArgumentException(v + " is the engine's, read on each side");
        v.replicatedIndex = REPLICATED.size();
        REPLICATED.add(v);
    }

    private static CgEntityVariable event(String name, int event) {
        return standard(name, BOOL, s -> bool(s.happened(event)));
    }

    private static float bool(boolean b) {
        return b ? 1f : 0f;
    }

    /** The variable named {@code name}; null for none. */
    @Nullable
    public static CgEntityVariable of(String name) {
        return BY_NAME.get(name);
    }

    /** Every one, in the order above: what the spec lists and a debugger shows. */
    public static Collection<CgEntityVariable> all() {
        return Collections.unmodifiableCollection(BY_NAME.values());
    }

    // ── replicated: the server's values, on the clients that see the entity ─────────────

    /** One value as the object carries it, by name: the two sides may register in other orders. */
    record Value(String name, float value) {
    }

    private static final CgCodec<Value> VALUE = CgCodecs.record(
            CgCodecs.field("n", CgCodecs.STRING, Value::name),
            CgCodecs.field("v", CgCodecs.FLOAT, Value::value), Value::new);
    private static final CgNetField<List<Value>> VALUES = CgNetField.of("values", CgCodecs.listOf(VALUE));
    private static final CgNetType TYPE = CgNetType.define("crystalgraphics:entity_variables").fields(VALUES).build();

    /** The server's: what each entity's object last carried, held weakly. */
    private static final Map<Object, Served> SERVER = new WeakHashMap<>();
    /** A client's: each entity's values by network id, kept while its object lives or an actor holds them. */
    private static final Map<Integer, Mirror> CLIENT = new HashMap<>();

    private static final class Served {
        float[] last = new float[0];
        CgNetObject object;
    }

    private static final class Mirror {
        float[] values = new float[0];
        boolean served;
        int holders;
    }

    static {
        TYPE.onClient(obj -> new CgNetBehaviour() {
            @Override
            public void spawned(CgNetObject o, float age) {
                take(o);
            }

            @Override
            public void changed(CgNetObject o, CgNetField<?> field) {
                take(o);
            }

            @Override
            public void despawned(CgNetObject o, boolean killed) {
                Mirror m = CLIENT.get(o.entityId());
                if (m == null) return;
                Arrays.fill(m.values, 0f);
                m.served = false;
                if (m.holders == 0) CLIENT.remove(o.entityId());
            }
        });
    }

    /** Declares the type: at network init, on both sides, before anyone connects. */
    public static void install() {
    }

    /**
     * Server: reads every replicated variable off {@code entity} and sends what changed to the clients that see it. Each
     * tick, for each entity an actor animates; allocates only when a value changed.
     */
    public static void serve(Object entity, CgEntityState state) {
        int n = REPLICATED.size();
        // nothing to send to without a network: a test's world, a server before its network installs
        if (n == 0 || !CgNetwork.isInstalled()) return;
        Served s = SERVER.computeIfAbsent(Objects.requireNonNull(entity, "entity"), e -> new Served());
        boolean fresh = s.last.length != n;
        if (fresh) s.last = new float[n];
        boolean changed = fresh;
        for (int i = 0; i < n; i++) {
            float v = REPLICATED.get(i).reader().read(state, entity);
            if (v != s.last[i]) {
                s.last[i] = v;
                changed = true;
            }
        }
        if (!changed && s.object != null && !s.object.isRemoved()) return;
        List<Value> now = new ArrayList<>(n);
        for (int i = 0; i < n; i++) now.add(new Value(REPLICATED.get(i).name(), s.last[i]));
        if (s.object == null || s.object.isRemoved()) {
            s.object = TYPE.spawn(CgAnchor.on(entity)).set(VALUES, Collections.unmodifiableList(now)).start();
        } else {
            s.object.set(VALUES, Collections.unmodifiableList(now));
        }
    }

    /**
     * Client: entity {@code entityId}'s replicated values, by index, kept and updated until {@link #release}d: what its
     * actor reads each frame. Zero until the server's first values arrive.
     */
    public static synchronized float[] hold(int entityId) {
        Mirror m = CLIENT.computeIfAbsent(entityId, id -> new Mirror());
        m.holders++;
        if (m.values.length < REPLICATED.size()) m.values = Arrays.copyOf(m.values, REPLICATED.size());
        return m.values;
    }

    /** Client: lets go of what {@link #hold} gave. */
    public static synchronized void release(int entityId) {
        Mirror m = CLIENT.get(entityId);
        if (m == null) return;
        if (--m.holders <= 0 && !m.served) CLIENT.remove(entityId);
    }

    /** Client: replicated variable {@code v}'s value on entity {@code entityId}, as the server last sent it; 0 for none. */
    public static synchronized float client(int entityId, CgEntityVariable v) {
        Mirror m = CLIENT.get(entityId);
        return m == null || v.replicatedIndex < 0 || v.replicatedIndex >= m.values.length ? 0f : m.values[v.replicatedIndex];
    }

    private static synchronized void take(CgNetObject o) {
        if (!o.has(VALUES)) return;
        Mirror m = CLIENT.computeIfAbsent(o.entityId(), id -> new Mirror());
        if (m.values.length < REPLICATED.size()) m.values = Arrays.copyOf(m.values, REPLICATED.size());
        m.served = true;
        for (Value value : o.get(VALUES)) {
            CgEntityVariable v = BY_NAME.get(value.name());
            // a variable this client did not register is no binding's
            if (v != null && v.replicatedIndex >= 0) m.values[v.replicatedIndex] = value.value();
        }
    }

    /** Forgets both sides' values, not the registrations. Tests only. */
    static synchronized void resetForTesting() {
        SERVER.clear();
        CLIENT.clear();
    }

    /** What a feed reads an entity's values from: an actor's entity is one ({@code CgEntity}). */
    public interface Source {

        CgEntityState state();

        /** Its owned tags, on this side. */
        CgTagCounts tags();

        /** The host's entity on this side, what a mod's reader reads; null where it has none. */
        @Nullable
        Object entity();

        /** On a client, the server's values of the replicated variables, by their index; null on a server. */
        @Nullable
        float[] replicated();
    }

    /**
     * An update that sets {@code state}'s values into whatever its instance's graph binds, every frame: what an entity
     * actor gives each skeletal mesh it owns. One per instance; it allocates only when the instance's graph changes.
     *
     * <pre>{@code
     * body.anim().onUpdate(CgEntityVariables.feed(entity.state()));
     * }</pre>
     */
    public static CgAnimInstance.Update feed(CgEntityState state) {
        return feed(state, new CgTagCounts());
    }

    /**
     * As {@link #feed(CgEntityState)}, the graph's {@code tags:} bindings read from {@code tags}: the entity's owned
     * tags, kept current by their owner (an actor's are {@code CgEntityTags}', on its side). A mod's variable reads a
     * null entity.
     */
    public static CgAnimInstance.Update feed(CgEntityState state, CgTagCounts tags) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tags, "tags");
        return feed(new Source() {
            @Override public CgEntityState state() { return state; }
            @Override public CgTagCounts tags() { return tags; }
            @Override @Nullable public Object entity() { return null; }
            @Override @Nullable public float[] replicated() { return null; }
        });
    }

    /** As {@link #feed(CgEntityState)}, everything read from {@code source}: what an entity actor feeds with. */
    public static CgAnimInstance.Update feed(Source source) {
        return new Feed(Objects.requireNonNull(source, "source"));
    }

    private static final class Feed implements CgAnimInstance.Update {

        private final Source source;
        @Nullable
        private CgAnimGraph bound;
        private CgAnimVariable<?>[] targets = new CgAnimVariable<?>[0], tagTargets = new CgAnimVariable<?>[0];
        private CgEntityVariable[] sources = new CgEntityVariable[0];
        private CgAnimGraph.TagBinding[] tagSources = new CgAnimGraph.TagBinding[0];

        Feed(Source source) {
            this.source = source;
        }

        @Override
        public void update(CgAnimInstance anim, float seconds) {
            CgAnimGraph graph = anim.graph().graph();
            if (graph != bound) bind(graph);
            CgEntityState state = source.state();
            CgTagCounts tags = source.tags();
            Object entity = source.entity();
            float[] served = source.replicated();
            for (int i = 0; i < targets.length; i++) {
                CgEntityVariable v = sources[i];
                int r = v.replicatedIndex;
                float value = r >= 0 && served != null ? (r < served.length ? served[r] : 0f) : v.reader().read(state, entity);
                set(anim, targets[i], value);
            }
            for (int i = 0; i < tagTargets.length; i++) {
                CgAnimGraph.TagBinding b = tagSources[i];
                set(anim, tagTargets[i], b.as().value(tags.count(b.tag())));
            }
        }

        @SuppressWarnings("unchecked")
        private static void set(CgAnimInstance anim, CgAnimVariable<?> t, float v) {
            switch (t.type()) {
                case FLOAT -> anim.set((CgAnimVariable<Float>) t, v);
                case BOOL -> anim.set((CgAnimVariable<Boolean>) t, v != 0f);
                case INT -> anim.set((CgAnimVariable<Integer>) t, (int) v);
            }
        }

        private void bind(CgAnimGraph graph) {
            bound = graph;
            Map<String, CgEntityVariable> entity = graph.entity();
            targets = new CgAnimVariable<?>[entity.size()];
            sources = new CgEntityVariable[entity.size()];
            int i = 0;
            for (Map.Entry<String, CgEntityVariable> e : entity.entrySet()) {
                targets[i] = graph.variables().get(graph.variable(e.getKey()));
                sources[i++] = e.getValue();
            }
            Map<String, CgAnimGraph.TagBinding> byTag = graph.tags();
            tagTargets = new CgAnimVariable<?>[byTag.size()];
            tagSources = new CgAnimGraph.TagBinding[byTag.size()];
            i = 0;
            for (Map.Entry<String, CgAnimGraph.TagBinding> e : byTag.entrySet()) {
                tagTargets[i] = graph.variables().get(graph.variable(e.getKey()));
                tagSources[i++] = e.getValue();
            }
        }
    }
}
