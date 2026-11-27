package com.crystalgraphics.world;

import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.platform.world.CgEntityFacts;

/**
 * The engine's own properties of every entity: what it is doing this frame, worked out on each side from what the host
 * says of it, the same on every Minecraft version. How fast it moves, whether it is on the ground, sneaking, swimming,
 * riding or sleeping, its head against its body, each hand's item and arm pose, a use or a swing, and what just
 * happened. An entity's properties are a networked type on entities; these are one more such type, {@link #MOTION},
 * whose fields are all {@code local()}: never sent, since both sides already know what they come from.
 *
 * <pre>{@code
 * CgNetObject motion = CgEntityProperties.MOTION.of(entity);         // the server's entity, or the client's
 * float speed = motion.get(CgEntityProperties.SPEED);                 // blocks a second, smooth across the frame
 * if (motion.get(CgEntityProperties.LANDED)) playLanding(motion.get(CgEntityProperties.LAND_HEIGHT));
 *
 * CgNetObject seen = CgEntityProperties.MOTION.of(entityId);  // a client, by network id
 * }</pre>
 *
 * A graph reads them with no code, by {@code entity.} and the field's name:
 *
 * <pre>{@code
 * # anim_graphs/mymod/hero.yaml
 * entity:
 *   speed: entity.speed
 *   in_air: entity.airborne
 *   ki: mymod:ki#current           # a mod's own property, the same way
 * }</pre>
 *
 * <ul>
 *   <li>An entity's object is made the first time something asks for it, and from then worked out each tick on the
 *       server and each frame on a client, before actors and plays read it; it ends with the entity.</li>
 *   <li>Events ({@link #JUMPED}, {@link #LANDED}, ...) are bools true for the one frame they happen in; what one carries
 *       is the other fields that frame ({@link #SWING_HAND} for a swing, {@link #LAND_HEIGHT} for a landing).</li>
 *   <li>Angles are degrees, speeds blocks a second, times seconds. A state a version lacks reads as not happening.</li>
 *   <li>The first frame an entity is seen raises no event, so one seen in mid-air does not jump.</li>
 * </ul>
 */
public final class CgEntityProperties {

    /** How an arm is posed ({@link #MAIN_ARM}, {@link #OFF_ARM}): Minecraft's {@code HumanoidModel.ArmPose}. */
    public static final int ARM_EMPTY = 0, ARM_ITEM = 1, ARM_BLOCK = 2, ARM_BOW = 3, ARM_THROW = 4,
            ARM_CROSSBOW_CHARGE = 5, ARM_CROSSBOW_HOLD = 6, ARM_SPYGLASS = 7, ARM_TOOT_HORN = 8, ARM_BRUSH = 9,
            ARM_SPEAR = 10;

    /** The hands ({@link #SWING_HAND}, {@link #USE_HAND}): Minecraft's {@code InteractionHand}. */
    public static final int MAIN = CgEntityFacts.MAIN, OFF = CgEntityFacts.OFF;

    // ── motion, smooth across the frame ─────────────────────────────────────────────────

    /** Along the ground, blocks a second. */
    public static final CgNetField<Float> SPEED = CgNetField.ofFloat("speed").local();
    /** Up positive, blocks a second. */
    public static final CgNetField<Float> VERTICAL_SPEED = CgNetField.ofFloat("vertical_speed").local();
    /** Which way it moves against its body's facing, degrees: 0 ahead, 90 left, 180 back; 0 standing. */
    public static final CgNetField<Float> MOVE_DIRECTION = CgNetField.ofFloat("move_direction").local();
    /** How fast its ground speed changes, blocks a second a second, over its last two ticks. */
    public static final CgNetField<Float> ACCELERATION = CgNetField.ofFloat("acceleration").local();
    /** How fast its body turns, degrees a second, left positive. */
    public static final CgNetField<Float> TURN_RATE = CgNetField.ofFloat("turn_rate").local();

    // ── intent ──────────────────────────────────────────────────────────────────────────

    /** How hard it means to move, 0 to 1: the player's stick or keys where the host has them, else from its movement. */
    public static final CgNetField<Float> INPUT = CgNetField.ofFloat("input").local();
    /** Which way it means to move against its facing, degrees, 90 left. */
    public static final CgNetField<Float> INPUT_DIRECTION = CgNetField.ofFloat("input_direction").local();
    /** Whether {@link #INPUT} is the player's own input rather than read from its movement. */
    public static final CgNetField<Boolean> INPUT_FROM_PLAYER = CgNetField.ofBool("input_from_player").local();
    public static final CgNetField<Boolean> SPRINT_HELD = CgNetField.ofBool("sprint_held").local();
    public static final CgNetField<Boolean> JUMP_HELD = CgNetField.ofBool("jump_held").local();

    // ── ground and air ──────────────────────────────────────────────────────────────────

    public static final CgNetField<Boolean> GROUNDED = CgNetField.ofBool("grounded").local();
    /** Off the ground and riding nothing. */
    public static final CgNetField<Boolean> AIRBORNE = CgNetField.ofBool("airborne").local();
    /** Seconds off the ground; 0 on it. */
    public static final CgNetField<Float> AIR_TIME = CgNetField.ofFloat("air_time").local();
    /** Its feet over the first surface under them, blocks: 0 on the ground, infinite where none is found in 64. */
    public static final CgNetField<Float> GROUND_DISTANCE = CgNetField.ofFloat("ground_distance").local()
            .fallback(Float.POSITIVE_INFINITY);
    /** How far it fell to its last landing, blocks, from the highest point it reached. */
    public static final CgNetField<Float> LAND_HEIGHT = CgNetField.ofFloat("land_height").local();

    // ── stance ──────────────────────────────────────────────────────────────────────────

    public static final CgNetField<Boolean> LIVING = CgNetField.ofBool("living").local();
    public static final CgNetField<Boolean> SNEAKING = CgNetField.ofBool("sneaking").local();
    public static final CgNetField<Boolean> SPRINTING = CgNetField.ofBool("sprinting").local();
    /** In the swimming pose in water. */
    public static final CgNetField<Boolean> SWIMMING = CgNetField.ofBool("swimming").local();
    /** In the swimming pose out of water: under a block too low to stand in. */
    public static final CgNetField<Boolean> CRAWLING = CgNetField.ofBool("crawling").local();
    /** 0 standing to 1 lying in the swimming pose, eased as Minecraft eases it. */
    public static final CgNetField<Float> SWIM_AMOUNT = CgNetField.ofFloat("swim_amount").local();
    public static final CgNetField<Boolean> GLIDING = CgNetField.ofBool("gliding").local();
    public static final CgNetField<Boolean> SPIN_ATTACK = CgNetField.ofBool("spin_attack").local();
    public static final CgNetField<Boolean> IN_WATER = CgNetField.ofBool("in_water").local();
    public static final CgNetField<Boolean> RIDING = CgNetField.ofBool("riding").local();
    /** The network id of what it rides; -1 for nothing. */
    public static final CgNetField<Integer> VEHICLE = CgNetField.ofInt("vehicle").local().fallback(-1);
    public static final CgNetField<Boolean> SLEEPING = CgNetField.ofBool("sleeping").local();

    // ── life ────────────────────────────────────────────────────────────────────────────

    public static final CgNetField<Boolean> DEAD = CgNetField.ofBool("dead").local();
    /** Seconds since it died; 0 while alive. */
    public static final CgNetField<Float> DEAD_TIME = CgNetField.ofFloat("dead_time").local();
    /** Seconds since it was last hurt; infinite before the first hit seen. */
    public static final CgNetField<Float> HURT_TIME = CgNetField.ofFloat("hurt_time").local()
            .fallback(Float.POSITIVE_INFINITY);

    // ── look ────────────────────────────────────────────────────────────────────────────

    /** The head's yaw against the body, degrees, left positive. */
    public static final CgNetField<Float> HEAD_YAW = CgNetField.ofFloat("head_yaw").local();
    /** The head's pitch, degrees, down positive. */
    public static final CgNetField<Float> HEAD_PITCH = CgNetField.ofFloat("head_pitch").local();

    // ── hands ───────────────────────────────────────────────────────────────────────────

    public static final CgNetField<Boolean> LEFT_HANDED = CgNetField.ofBool("left_handed").local();
    /** What each hand holds, by the host's raw item id: 0 for nothing. */
    public static final CgNetField<Integer> MAIN_ITEM = CgNetField.ofInt("main_item").local(),
            OFF_ITEM = CgNetField.ofInt("off_item").local();
    /** How each arm is posed: an {@code ARM_} constant, Minecraft's own rule for a player's arms. */
    public static final CgNetField<Integer> MAIN_ARM = CgNetField.ofInt("main_arm").local(),
            OFF_ARM = CgNetField.ofInt("off_arm").local();
    public static final CgNetField<Boolean> USING = CgNetField.ofBool("using").local();
    /** The hand in use, or last in use: {@link #MAIN} or {@link #OFF}. */
    public static final CgNetField<Integer> USE_HAND = CgNetField.ofInt("use_hand").local();
    /** How the item in use looks in use: a {@code CgEntityFacts.USE_} constant. */
    public static final CgNetField<Integer> USE_ANIMATION = CgNetField.ofInt("use_animation").local();
    /** Seconds into the current use; 0 using nothing. */
    public static final CgNetField<Float> USE_TIME = CgNetField.ofFloat("use_time").local();
    /** How long the current use lasts at most, seconds; 0 where the host cannot say. */
    public static final CgNetField<Float> USE_LENGTH = CgNetField.ofFloat("use_length").local();
    public static final CgNetField<Boolean> SWINGING = CgNetField.ofBool("swinging").local();
    /** The swing's progress, 0 to 1: what Minecraft's own arm swing follows. */
    public static final CgNetField<Float> SWING = CgNetField.ofFloat("swing").local();
    public static final CgNetField<Integer> SWING_HAND = CgNetField.ofInt("swing_hand").local();

    // ── events, true for their frame ────────────────────────────────────────────────────

    /** It left the ground going up (walking off a ledge is not a jump). */
    public static final CgNetField<Boolean> JUMPED = CgNetField.ofBool("jumped").local();
    /** It touched the ground again; how far it fell is {@link #LAND_HEIGHT}. */
    public static final CgNetField<Boolean> LANDED = CgNetField.ofBool("landed").local();
    /** It was hurt, a second hit before the first wore off included. */
    public static final CgNetField<Boolean> HURT = CgNetField.ofBool("hurt").local();
    /** An arm began a swing, or began it again; which is {@link #SWING_HAND}. */
    public static final CgNetField<Boolean> SWING_STARTED = CgNetField.ofBool("swing_started").local();
    /** It began using an item; with which hand is {@link #USE_HAND}. */
    public static final CgNetField<Boolean> USE_STARTED = CgNetField.ofBool("use_started").local();
    public static final CgNetField<Boolean> USE_STOPPED = CgNetField.ofBool("use_stopped").local();
    /** It got on something, {@link #VEHICLE}; changing mounts raises this and {@link #DISMOUNTED} together. */
    public static final CgNetField<Boolean> MOUNTED = CgNetField.ofBool("mounted").local();
    public static final CgNetField<Boolean> DISMOUNTED = CgNetField.ofBool("dismounted").local();
    public static final CgNetField<Boolean> DIED = CgNetField.ofBool("died").local();

    /**
     * Every field above, an entity's own: made the first time it is asked for ({@code MOTION.of(entity)},
     * {@code MOTION.of(entityId)}), worked out each server tick and client frame. A graph names its fields
     * {@code entity.<field>}.
     */
    public static final CgNetType<CgNetObject> MOTION = CgNetType.define("crystalgraphics:entity_motion").fields(
            SPEED, VERTICAL_SPEED, MOVE_DIRECTION, ACCELERATION, TURN_RATE,
            INPUT, INPUT_DIRECTION, INPUT_FROM_PLAYER, SPRINT_HELD, JUMP_HELD,
            GROUNDED, AIRBORNE, AIR_TIME, GROUND_DISTANCE, LAND_HEIGHT,
            LIVING, SNEAKING, SPRINTING, SWIMMING, CRAWLING, SWIM_AMOUNT, GLIDING, SPIN_ATTACK, IN_WATER, RIDING, VEHICLE,
            SLEEPING, DEAD, DEAD_TIME, HURT_TIME, HEAD_YAW, HEAD_PITCH,
            LEFT_HANDED, MAIN_ITEM, OFF_ITEM, MAIN_ARM, OFF_ARM, USING, USE_HAND, USE_ANIMATION, USE_TIME, USE_LENGTH,
            SWINGING, SWING, SWING_HAND,
            JUMPED, LANDED, HURT, SWING_STARTED, USE_STARTED, USE_STOPPED, MOUNTED, DISMOUNTED, DIED)
            .perHolder().build()
            .onServer(obj -> new CgEntityMotion())
            .onClient(obj -> new CgEntityMotion());

    /** What a graph's {@code entity.<field>} names: one of {@link #MOTION}'s fields. */
    public static final String PREFIX = "entity.";

    private CgEntityProperties() {
    }
}
