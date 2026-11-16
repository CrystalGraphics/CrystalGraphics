package com.crystalgraphics.world;

import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.tag.CgTagCounts;

/**
 * The values the engine derives for every entity each frame, on each side, named so anything can read them without
 * code: an animation graph's {@code entity:} block, a debugger, a mod. Everything an entity's {@link CgEntityState}
 * says, plus each {@link CgEntityEvents event} as a field true for its frame. A mod derives its own with
 * {@link #register}; data an entity stores is a networked type's field ({@link CgEntityNetField}), named
 * {@code <type>#<field>}, and {@link #binding} resolves either.
 *
 * <pre>{@code
 * # anim_graphs/mymod/hero.yaml
 * variables:
 *   speed: { type: float }
 *   in_air: { type: bool }
 *   ki: { type: float }
 * entity:
 *   speed: entity.speed           # derived, on each side
 *   in_air: entity.airborne
 *   ki: mymod:mana#current        # stored: the server's mymod:mana object on the entity
 * }</pre>
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .variables(SPEED, IN_AIR, KI)
 *         .entity(SPEED, CgEntityFields.SPEED)
 *         .entity(IN_AIR, CgEntityFields.AIRBORNE)
 *         .entity(KI, CgEntityNetField.of(Mana.TYPE, Mana.CURRENT));
 * }</pre>
 *
 * <ul>
 *   <li>Angles are degrees, speeds blocks a second, times seconds, as {@link CgEntityState} gives them.</li>
 *   <li>Events ({@link #JUMPED}, {@link #LANDED}, ...) are true for the one frame they happen in.</li>
 * </ul>
 */
public final class CgEntityFields {

    private static final Map<String, CgEntityField> BY_NAME = new LinkedHashMap<>();

    // ── motion ──────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#speed}: along the ground, blocks a second. */
    public static final CgEntityField SPEED = standard("entity.speed", FLOAT, CgEntityState::speed);
    public static final CgEntityField VERTICAL_SPEED = standard("entity.vertical_speed", FLOAT, CgEntityState::verticalSpeed);
    /** {@link CgEntityState#moveDirection}: degrees against the body's facing, 90 left. */
    public static final CgEntityField MOVE_DIRECTION = standard("entity.move_direction", FLOAT, CgEntityState::moveDirection);
    public static final CgEntityField ACCELERATION = standard("entity.acceleration", FLOAT, CgEntityState::acceleration);
    public static final CgEntityField TURN_RATE = standard("entity.turn_rate", FLOAT, CgEntityState::turnRate);

    // ── intent ──────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#input}: how hard it means to move, 0 to 1. */
    public static final CgEntityField INPUT = standard("entity.input", FLOAT, CgEntityState::input);
    public static final CgEntityField INPUT_DIRECTION = standard("entity.input_direction", FLOAT, CgEntityState::inputDirection);
    public static final CgEntityField SPRINT_HELD = standard("entity.sprint_held", BOOL, s -> bool(s.sprintHeld()));
    public static final CgEntityField JUMP_HELD = standard("entity.jump_held", BOOL, s -> bool(s.jumpHeld()));

    // ── ground and air ──────────────────────────────────────────────────────────────────

    public static final CgEntityField GROUNDED = standard("entity.grounded", BOOL, s -> bool(s.grounded()));
    public static final CgEntityField AIRBORNE = standard("entity.airborne", BOOL, s -> bool(!s.grounded() && !s.riding()));
    public static final CgEntityField AIR_TIME = standard("entity.air_time", FLOAT, CgEntityState::airSeconds);
    public static final CgEntityField GROUND_DISTANCE = standard("entity.ground_distance", FLOAT, CgEntityState::groundDistance);
    public static final CgEntityField LAND_HEIGHT = standard("entity.land_height", FLOAT, CgEntityState::landHeight);

    // ── stance ──────────────────────────────────────────────────────────────────────────

    public static final CgEntityField SNEAKING = standard("entity.sneaking", BOOL, s -> bool(s.sneaking()));
    public static final CgEntityField SPRINTING = standard("entity.sprinting", BOOL, s -> bool(s.sprinting()));
    public static final CgEntityField SWIMMING = standard("entity.swimming", BOOL, s -> bool(s.swimming()));
    public static final CgEntityField CRAWLING = standard("entity.crawling", BOOL, s -> bool(s.crawling()));
    public static final CgEntityField SWIM_AMOUNT = standard("entity.swim_amount", FLOAT, CgEntityState::swimAmount);
    public static final CgEntityField GLIDING = standard("entity.gliding", BOOL, s -> bool(s.gliding()));
    public static final CgEntityField SPIN_ATTACK = standard("entity.spin_attack", BOOL, s -> bool(s.spinAttack()));
    public static final CgEntityField IN_WATER = standard("entity.in_water", BOOL, s -> bool(s.inWater()));
    public static final CgEntityField RIDING = standard("entity.riding", BOOL, s -> bool(s.riding()));
    public static final CgEntityField SLEEPING = standard("entity.sleeping", BOOL, s -> bool(s.sleeping()));

    // ── life ────────────────────────────────────────────────────────────────────────────

    public static final CgEntityField DEAD = standard("entity.dead", BOOL, s -> bool(s.dead()));
    public static final CgEntityField DEAD_TIME = standard("entity.dead_time", FLOAT, CgEntityState::secondsDead);
    /** {@link CgEntityState#secondsSinceHurt}: infinite before the first hit. */
    public static final CgEntityField HURT_TIME = standard("entity.hurt_time", FLOAT, CgEntityState::secondsSinceHurt);

    // ── look ────────────────────────────────────────────────────────────────────────────

    /** {@link CgEntityState#headYaw}: the head against the body, degrees, 90 left. */
    public static final CgEntityField HEAD_YAW = standard("entity.head_yaw", FLOAT, CgEntityState::headYaw);
    public static final CgEntityField HEAD_PITCH = standard("entity.head_pitch", FLOAT, CgEntityState::headPitch);

    // ── hands ───────────────────────────────────────────────────────────────────────────

    public static final CgEntityField LEFT_HANDED = standard("entity.left_handed", BOOL, s -> bool(s.leftHanded()));
    /** The main hand's arm pose: a {@code CgEntityState.ARM_} constant. */
    public static final CgEntityField MAIN_ARM = standard("entity.main_arm", INT, s -> s.armPose(CgEntityState.MAIN));
    public static final CgEntityField OFF_ARM = standard("entity.off_arm", INT, s -> s.armPose(CgEntityState.OFF));
    public static final CgEntityField USING = standard("entity.using", BOOL,
            s -> bool(s.using(CgEntityState.MAIN) || s.using(CgEntityState.OFF)));
    /** The item in use's look in use: a {@code CgEntityFacts.USE_} constant. */
    public static final CgEntityField USE_ANIMATION = standard("entity.use_animation", INT, CgEntityState::useAnimation);
    public static final CgEntityField USE_TIME = standard("entity.use_time", FLOAT, CgEntityState::useSeconds);
    public static final CgEntityField USE_LENGTH = standard("entity.use_length", FLOAT, CgEntityState::useLength);
    public static final CgEntityField SWINGING = standard("entity.swinging", BOOL, s -> bool(s.swinging()));
    /** {@link CgEntityState#swing}: the swing's progress, 0 to 1. */
    public static final CgEntityField SWING = standard("entity.swing", FLOAT, CgEntityState::swing);
    public static final CgEntityField SWING_HAND = standard("entity.swing_hand", INT, CgEntityState::swingHand);

    // ── events, true for their frame ────────────────────────────────────────────────────

    public static final CgEntityField JUMPED = event(CgEntityEvents.JUMPED);
    public static final CgEntityField LANDED = event(CgEntityEvents.LANDED);
    public static final CgEntityField HURT = event(CgEntityEvents.HURT);
    public static final CgEntityField SWING_STARTED = event(CgEntityEvents.SWING_STARTED);
    public static final CgEntityField USE_STARTED = event(CgEntityEvents.USE_STARTED);
    public static final CgEntityField USE_STOPPED = event(CgEntityEvents.USE_STOPPED);
    public static final CgEntityField MOUNTED = event(CgEntityEvents.MOUNTED);
    public static final CgEntityField DISMOUNTED = event(CgEntityEvents.DISMOUNTED);
    public static final CgEntityField DIED = event(CgEntityEvents.DIED);

    private CgEntityFields() {
    }

    /** A standard field's reader: the entity's state alone. */
    @FunctionalInterface
    private interface StateReader {
        float read(CgEntityState state);
    }

    private static CgEntityField standard(String name, CgValueType type, StateReader reader) {
        CgEntityField f = new CgEntityField(name, type, (state, entity) -> reader.read(state));
        BY_NAME.put(name, f);
        return f;
    }

    private static CgEntityField event(CgEntityEvent<?> event) {
        int bit = event.bit;
        return standard(event.name(), BOOL, s -> bool(s.fired(bit)));
    }

    private static float bool(boolean b) {
        return b ? 1f : 0f;
    }

    /** Whether a field may hold {@code type}: a float, an int or a bool. */
    static boolean isScalar(CgValueType type) {
        return FLOAT.equals(type) || INT.equals(type) || BOOL.equals(type);
    }

    /**
     * A mod's own derived field, {@code namespace:path}, read off the host's entity each frame on each side. At init,
     * before a graph naming it loads. A value only the server knows is a networked type's field instead
     * ({@link CgEntityNetField}).
     *
     * <pre>{@code
     * public static final CgEntityField FLIGHT_GEAR = CgEntityFields.register("mymod:flight_gear", CgValueType.INT,
     *         (state, entity) -> Flight.gear(entity));
     * }</pre>
     *
     * @throws IllegalArgumentException for a name not {@code namespace:path}, one taken, or a type no float, int or bool
     */
    public static synchronized CgEntityField register(String name, CgValueType type, CgEntityField.Reader reader) {
        int colon = name.indexOf(':');
        if (colon <= 0 || colon == name.length() - 1 || name.indexOf(':', colon + 1) >= 0 || name.indexOf('#') >= 0) {
            throw new IllegalArgumentException("a mod's entity field is namespace:path, not " + name);
        }
        if (BY_NAME.containsKey(name)) throw new IllegalArgumentException("entity field " + name + " is registered twice");
        CgEntityField f = new CgEntityField(name, type, reader);
        BY_NAME.put(name, f);
        return f;
    }

    /** The derived field named {@code name}; null for none. */
    @Nullable
    public static synchronized CgEntityField of(String name) {
        return BY_NAME.get(name);
    }

    /**
     * What {@code name} names: a derived field ({@code entity.speed}, {@code mymod:flight_gear}) or a networked type's
     * field ({@code mymod:mana#current}). What a graph's file is read with.
     *
     * @throws IllegalArgumentException naming what is missing: no such field, no such type, a type not per holder, a
     *                                  field it does not list or one no number or bool
     */
    public static CgEntityBinding binding(String name) {
        int hash = name.indexOf('#');
        if (hash < 0) {
            CgEntityField f = of(name);
            if (f == null) throw new IllegalArgumentException(name + " is no entity field");
            return f;
        }
        String typeName = name.substring(0, hash), fieldName = name.substring(hash + 1);
        CgNetType type = CgNetType.named(typeName);
        if (type == null) throw new IllegalArgumentException(name + ": no networked type " + typeName + " is declared");
        for (CgNetField<?> field : type.fields()) {
            if (field.name().equals(fieldName)) return CgEntityNetField.of(type, field);
        }
        throw new IllegalArgumentException(name + ": " + typeName + " has no field " + fieldName);
    }

    /** Every derived one, the engine's in the order above, then mods' as registered: what a debugger shows. */
    public static synchronized Collection<CgEntityField> all() {
        return Collections.unmodifiableList(new ArrayList<>(BY_NAME.values()));
    }

    /**
     * An entity as a reader of its values sees it on one side: what a graph's feed reads. An actor's entity is one
     * ({@code CgEntity}); {@link #of(CgEntityState, CgTagCounts)} makes one over a bare state.
     */
    public interface Source {

        CgEntityState state();

        /** Its owned tags, on this side. */
        CgTagCounts tags();

        /** The host's entity on this side, what a mod's reader reads; null where it has none. */
        @Nullable
        Object entity();

        /** Its network id; -1 for none. */
        int id();

        /** Whether this is the server's side: a stored field is read off the server's object, else a client's copy by id. */
        boolean onServer();
    }

    /** A source over {@code state} and {@code tags} alone: no host entity, so mods' and stored fields read as for none. */
    public static Source of(CgEntityState state, CgTagCounts tags) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tags, "tags");
        return new Source() {
            @Override public CgEntityState state() { return state; }
            @Override public CgTagCounts tags() { return tags; }
            @Override @Nullable public Object entity() { return null; }
            @Override public int id() { return -1; }
            @Override public boolean onServer() { return false; }
        };
    }
}
