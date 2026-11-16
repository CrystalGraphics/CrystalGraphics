package com.crystalgraphics.world;

import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.BOOL;
import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.FLOAT;
import static com.crystalgraphics.animation.graph.CgAnimVariable.Type.INT;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimVariable;

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
 * <ul>
 *   <li>A name no variable has, a graph variable the graph does not declare, or a type that cannot take the value
 *       fails the graph at load, naming them. A float takes any value; a bool or an int only its own type.</li>
 *   <li>Events ({@link #JUMPED}, {@link #LANDED}, ...) are true for the one frame they happen in.</li>
 *   <li>Angles are degrees, speeds blocks a second, times seconds, as {@link CgEntityState} gives them.</li>
 * </ul>
 */
public final class CgEntityVariables {

    private static final Map<String, CgEntityVariable> BY_NAME = new LinkedHashMap<>();

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

    private static CgEntityVariable standard(String name, CgAnimVariable.Type type, CgEntityVariable.Reader reader) {
        CgEntityVariable v = new CgEntityVariable(name, type, reader);
        BY_NAME.put(name, v);
        return v;
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

    /**
     * An update that sets {@code state}'s values into whatever its instance's graph binds, every frame: what an entity
     * actor gives each skeletal mesh it owns. One per instance; it allocates only when the instance's graph changes.
     *
     * <pre>{@code
     * body.anim().onUpdate(CgEntityVariables.feed(entity.state()));
     * }</pre>
     */
    public static CgAnimInstance.Update feed(CgEntityState state) {
        return new Feed(Objects.requireNonNull(state, "state"));
    }

    private static final class Feed implements CgAnimInstance.Update {

        private final CgEntityState state;
        @Nullable
        private CgAnimGraph bound;
        private CgAnimVariable<?>[] targets = new CgAnimVariable<?>[0];
        private CgEntityVariable[] sources = new CgEntityVariable[0];

        Feed(CgEntityState state) {
            this.state = state;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void update(CgAnimInstance anim, float seconds) {
            CgAnimGraph graph = anim.graph().graph();
            if (graph != bound) bind(graph);
            for (int i = 0; i < targets.length; i++) {
                float v = sources[i].reader().read(state);
                CgAnimVariable<?> t = targets[i];
                switch (t.type()) {
                    case FLOAT -> anim.set((CgAnimVariable<Float>) t, v);
                    case BOOL -> anim.set((CgAnimVariable<Boolean>) t, v != 0f);
                    case INT -> anim.set((CgAnimVariable<Integer>) t, (int) v);
                }
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
        }
    }
}
