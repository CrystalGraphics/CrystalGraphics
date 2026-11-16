package com.crystalgraphics.world;

import static com.crystalgraphics.world.CgEntityProperties.*;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.host.CgStageHost;

/**
 * Works out {@link CgEntityProperties#MOTION}'s fields for one entity from what its host says of it: on the server each
 * tick, on a client each frame at the frame's partial tick. Its events last one update; the first update raises none.
 * Allocates nothing.
 */
final class CgEntityMotion implements CgNetBehaviour {

    /** Its events, as bits. */
    static final int JUMPED_BIT = 1, LANDED_BIT = 1 << 1, HURT_BIT = 1 << 2, SWING_STARTED_BIT = 1 << 3, USE_STARTED_BIT = 1 << 4,
            USE_STOPPED_BIT = 1 << 5, MOUNTED_BIT = 1 << 6, DISMOUNTED_BIT = 1 << 7, DIED_BIT = 1 << 8;

    private static final float TICKS_PER_SECOND = 20f;
    /** Below this a tick's movement is standing still, blocks: what friction leaves of a stop. */
    private static final double MOVING = 0.01;
    /** What sneaking scales Minecraft's input by ({@code Input.tick}'s 0.3, the sneaking speed attribute's default). */
    private static final float SNEAK_INPUT = 0.3f;
    /** How far down {@link #groundDistance} looks, blocks. */
    private static final int GROUND_REACH = 64;
    /** Minecraft eases its swim amount 0.09 a tick toward the swim pose ({@code LivingEntity.updateSwimAmount}). */
    private static final float SWIM_EASE = 0.09f * TICKS_PER_SECOND;

    /** This update's facts, the last one's, and the one the host writes into: rotated, so a failed read changes nothing. */
    private CgEntityFacts now = new CgEntityFacts().clear(), was = new CgEntityFacts().clear(),
            read = new CgEntityFacts();
    private final int[] arms = new int[2];
    private boolean primed;
    private int events;
    private float swimAmount, hurtSeconds = Float.POSITIVE_INFINITY, useSeconds, airSeconds, landHeight;
    private float headYaw, headPitch;
    private float input, inputDirection;
    private boolean inputFromPlayer, sprintHeld, jumpHeld;
    private double peakY;
    /** The movement of the tick drawn toward and of the one before, blocks: what the frame's motion is between. */
    private double tickX, tickY, tickZ, lastTickX, lastTickY, lastTickZ;
    private double velocityX, velocityY, velocityZ;
    private float lastPartialTick, bodyYaw, turnRate, groundDistance;

    /**
     * Reads entity {@code id} at {@code partialTick} and moves the state on by {@code dt} seconds; {@code pose} is its
     * pose at the same partial tick, as {@link CgEntityQuery#pose} wrote it, and {@code world} its level's blocks, which
     * {@link #groundDistance} looks down through ({@link CgWorldQuery#NONE}: never found). False, with the state
     * unchanged, where the host answers no facts for it.
     */
    boolean update(CgEntityQuery entities, CgWorldQuery world, int id, float partialTick, float dt,
                          double[] pose) {
        if (!entities.facts(id, partialTick, read.clear())) return false;
        CgEntityFacts spare = was;
        was = now;
        now = read;
        read = spare;
        events = 0;
        if (!primed) {
            // first seen: as if it had been doing this all along, so nothing changes and nothing happens
            primed = true;
            was.bits = now.bits;
            was.vehicle = now.vehicle;
            was.hurtTime = now.hurtTime;
            was.swing = now.swing;
            was.useHand = now.useHand;
            was.useTicks = now.useTicks;
            peakY = now.y;
            swimAmount = now.has(CgEntityFacts.SWIM_POSE) ? 1f : 0f;
            lastTickX = tickX = now.moveX;
            lastTickY = tickY = now.moveY;
            lastTickZ = tickZ = now.moveZ;
            bodyYaw = (float) pose[CgEntityQuery.BODY_YAW];
            turnRate = 0f;
        } else {
            swimAmount = now.has(CgEntityFacts.SWIM_POSE) ? Math.min(1f, swimAmount + SWIM_EASE * dt)
                    : Math.max(0f, swimAmount - SWIM_EASE * dt);
        }
        headYaw = wrap((float) (pose[CgEntityQuery.HEAD_YAW] - pose[CgEntityQuery.BODY_YAW]));
        headPitch = (float) pose[CgEntityQuery.PITCH];
        intent((float) pose[CgEntityQuery.YAW]);
        motion(partialTick, dt, (float) pose[CgEntityQuery.BODY_YAW]);
        air(dt);
        groundDistance = ground(world, pose);
        if (now.hurtTime > was.hurtTime) {
            events |= HURT_BIT;
            hurtSeconds = 0f;
        } else {
            hurtSeconds += dt;
        }
        if (now.has(CgEntityFacts.DEAD) && (was.bits & CgEntityFacts.DEAD) == 0) events |= DIED_BIT;
        if (now.vehicle != was.vehicle) {
            if (was.vehicle >= 0) events |= DISMOUNTED_BIT;
            if (now.vehicle >= 0) events |= MOUNTED_BIT;
        }
        boolean swinging = now.has(CgEntityFacts.SWINGING);
        if (swinging && ((was.bits & CgEntityFacts.SWINGING) == 0 || now.swing < was.swing)) events |= SWING_STARTED_BIT;
        use(partialTick, dt);
        arms();
        return true;
    }

    /** Forgets what it saw: the next {@link #update} starts it afresh, raising no events. */
    void reset() {
        now.clear();
        was.clear();
        primed = false;
        events = 0;
        swimAmount = useSeconds = airSeconds = landHeight = input = inputDirection = 0f;
        lastPartialTick = turnRate = groundDistance = 0f;
        tickX = tickY = tickZ = lastTickX = lastTickY = lastTickZ = velocityX = velocityY = velocityZ = 0;
        inputFromPlayer = sprintHeld = jumpHeld = false;
        hurtSeconds = Float.POSITIVE_INFINITY;
        arms[MAIN] = arms[OFF] = ARM_EMPTY;
    }

    /**
     * What it means to do: the player's own input where the host has it, else its movement against its facing, read back
     * through Minecraft's {@code moveRelative} (strafe s and forward f become x = s cos - f sin, z = f cos + s sin).
     */
    private void intent(float yawDegrees) {
        float strafe, forward;
        inputFromPlayer = now.has(CgEntityFacts.INPUT);
        if (inputFromPlayer) {
            strafe = now.inputStrafe;
            forward = now.inputForward;
            sprintHeld = now.has(CgEntityFacts.INPUT_SPRINT);
            jumpHeld = now.has(CgEntityFacts.INPUT_JUMP);
        } else {
            double yaw = Math.toRadians(yawDegrees), c = Math.cos(yaw), sn = Math.sin(yaw);
            double mx = now.moveX, mz = now.moveZ, length = Math.sqrt(mx * mx + mz * mz);
            sprintHeld = now.has(CgEntityFacts.SPRINTING);
            jumpHeld = false;
            if (length < MOVING) {
                strafe = forward = 0f;
            } else {
                float scale = (now.has(CgEntityFacts.SNEAKING) ? SNEAK_INPUT : 1f) / (float) length;
                strafe = (float) (mx * c + mz * sn) * scale;
                forward = (float) (-mx * sn + mz * c) * scale;
            }
        }
        input = Math.min(1f, (float) Math.sqrt(strafe * strafe + forward * forward));
        inputDirection = input == 0f ? 0f : (float) Math.toDegrees(Math.atan2(strafe, forward));
    }

    /**
     * The frame's velocity, between the last two ticks' movement as the drawn position is between the last two
     * positions. A tick has passed when the partial tick wraps, or on a server (always at 1) at each update.
     */
    private void motion(float partialTick, float dt, float yaw) {
        if (partialTick < lastPartialTick || partialTick >= 1f || now.moveX != tickX || now.moveY != tickY
                || now.moveZ != tickZ) {
            lastTickX = tickX;
            lastTickY = tickY;
            lastTickZ = tickZ;
            tickX = now.moveX;
            tickY = now.moveY;
            tickZ = now.moveZ;
        }
        lastPartialTick = partialTick;
        velocityX = (lastTickX + (tickX - lastTickX) * partialTick) * TICKS_PER_SECOND;
        velocityY = (lastTickY + (tickY - lastTickY) * partialTick) * TICKS_PER_SECOND;
        velocityZ = (lastTickZ + (tickZ - lastTickZ) * partialTick) * TICKS_PER_SECOND;
        if (dt > 0f) turnRate = wrap(yaw - bodyYaw) / dt;
        bodyYaw = yaw;
    }

    /** Its feet's height over the first surface under them; 0 on the ground, infinite where none is found. */
    private float ground(CgWorldQuery world, double[] pose) {
        if (now.has(CgEntityFacts.GROUNDED)) return 0f;
        double feet = pose[CgEntityQuery.Y];
        double floor = CgWorldQueries.groundBelow(world, pose[CgEntityQuery.X], feet, pose[CgEntityQuery.Z], GROUND_REACH);
        return Double.isNaN(floor) ? Float.POSITIVE_INFINITY : (float) Math.max(0, feet - floor);
    }

    /** Leaving the ground and touching it again, with the highest point between: what a landing's height is from. */
    private void air(float dt) {
        boolean grounded = now.has(CgEntityFacts.GROUNDED), wasGrounded = (was.bits & CgEntityFacts.GROUNDED) != 0;
        if (now.vehicle >= 0) {
            airSeconds = 0f;
            peakY = now.y;
            return;
        }
        if (wasGrounded && !grounded) {
            if (now.moveY > 0) events |= JUMPED_BIT;
            airSeconds = 0f;
            peakY = now.y;
        } else if (!grounded) {
            airSeconds += dt;
            peakY = Math.max(peakY, now.y);
        } else if (!wasGrounded) {
            events |= LANDED_BIT;
            landHeight = (float) Math.max(0, peakY - now.y);
        }
        if (grounded) peakY = now.y;
    }

    private void use(float partialTick, float dt) {
        boolean using = now.has(CgEntityFacts.USING), wasUsing = (was.bits & CgEntityFacts.USING) != 0;
        boolean restarted = wasUsing && using && (now.useHand != was.useHand
                || now.useTicks >= 0 && was.useTicks >= 0 && now.useTicks < was.useTicks);
        boolean started = using && (!wasUsing || restarted);
        if (wasUsing && (!using || restarted)) events |= USE_STOPPED_BIT;
        if (started) events |= USE_STARTED_BIT;
        // a host that cannot say how far in (1.7.10 and 1.8.9 servers) is counted from the start seen
        if (!using || started && now.useTicks < 0) useSeconds = 0f;
        else if (now.useTicks >= 0) useSeconds = (now.useTicks + partialTick) / TICKS_PER_SECOND;
        else useSeconds += dt;
    }

    // Minecraft's player arm rule (26.3's AvatarRenderer.getArmPose): a two-handed pose leaves the other hand only
    // holding what it holds.
    private void arms() {
        arms[MAIN] = arm(MAIN);
        arms[OFF] = arm(OFF);
        int main = arms[MAIN];
        if (main == ARM_BOW || main == ARM_CROSSBOW_CHARGE || main == ARM_CROSSBOW_HOLD) {
            arms[OFF] = now.item[OFF] == 0 ? ARM_EMPTY : ARM_ITEM;
        }
    }

    private int arm(int hand) {
        if (now.item[hand] == 0) return ARM_EMPTY;
        int charged = hand == MAIN ? CgEntityFacts.CHARGED_MAIN : CgEntityFacts.CHARGED_OFF;
        if (!now.has(CgEntityFacts.SWINGING) && now.has(charged)) return ARM_CROSSBOW_HOLD;
        if (now.has(CgEntityFacts.USING) && now.useHand == hand) {
            switch (now.use[hand]) {
                case CgEntityFacts.USE_BLOCK: return ARM_BLOCK;
                case CgEntityFacts.USE_BOW: return ARM_BOW;
                case CgEntityFacts.USE_TRIDENT: return ARM_THROW;
                case CgEntityFacts.USE_CROSSBOW: return ARM_CROSSBOW_CHARGE;
                case CgEntityFacts.USE_SPYGLASS: return ARM_SPYGLASS;
                case CgEntityFacts.USE_TOOT_HORN: return ARM_TOOT_HORN;
                case CgEntityFacts.USE_BRUSH: return ARM_BRUSH;
                case CgEntityFacts.USE_SPEAR: return ARM_SPEAR;
                default: break;
            }
        }
        return ARM_ITEM;
    }

    private static float wrap(float degrees) {
        return degrees - (float) Math.floor((degrees + 180f) / 360f) * 360f;
    }

    // ── what it is doing ────────────────────────────────────────────────────────

    /** Whether any event happened in the last {@link #update}. */
    boolean firedAny() {
        return events != 0;
    }

    boolean fired(int bit) {
        return (events & bit) != 0;
    }

    /** Its speed along the ground, blocks a second, smooth across the frame. */
    float speed() {
        return (float) Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
    }

    /** Its vertical speed, blocks a second, smooth across the frame: up positive. */
    float verticalSpeed() {
        return (float) velocityY;
    }

    /**
     * Which way it moves against its body's facing, degrees: 0 ahead, 90 to its left, 180 back; 0 standing. What
     * orientation warping turns the legs by, and what picks a start or stop clip's direction.
     */
    float moveDirection() {
        double still = MOVING * TICKS_PER_SECOND;
        if (velocityX * velocityX + velocityZ * velocityZ < still * still) return 0f;
        double yaw = Math.toRadians(bodyYaw), c = Math.cos(yaw), sn = Math.sin(yaw);
        return (float) Math.toDegrees(Math.atan2(velocityX * c + velocityZ * sn, -velocityX * sn + velocityZ * c));
    }

    /**
     * How fast its ground speed changes, blocks a second a second: positive speeding up, negative slowing. From its last
     * two ticks, so it holds for a tick: what tells a start from a run and a stop from a walk.
     */
    float acceleration() {
        double latest = Math.sqrt(tickX * tickX + tickZ * tickZ);
        double before = Math.sqrt(lastTickX * lastTickX + lastTickZ * lastTickZ);
        return (float) (latest - before) * TICKS_PER_SECOND * TICKS_PER_SECOND;
    }

    /** How fast its body turns, degrees a second: positive to its left. Minecraft's yaw grows turning right. */
    float turnRate() {
        return -turnRate;
    }

    /**
     * How high its feet are over the first surface under them, blocks: 0 on the ground, infinite where nothing is found
     * within 64 blocks or its level has no blocks to ask. What a landing anticipates by.
     */
    float groundDistance() {
        return groundDistance;
    }

    boolean living() {
        return now.has(CgEntityFacts.LIVING);
    }

    boolean grounded() {
        return now.has(CgEntityFacts.GROUNDED);
    }

    boolean sneaking() {
        return now.has(CgEntityFacts.SNEAKING);
    }

    boolean sprinting() {
        return now.has(CgEntityFacts.SPRINTING);
    }

    /** In the swimming pose: swimming in water, or crawling out of it. */
    boolean swimming() {
        return now.has(CgEntityFacts.SWIM_POSE) && now.has(CgEntityFacts.IN_WATER);
    }

    /** In the swimming pose out of water: under a block too low to stand in. */
    boolean crawling() {
        return now.has(CgEntityFacts.SWIM_POSE) && !now.has(CgEntityFacts.IN_WATER);
    }

    /** 0 standing to 1 lying in the swimming pose, eased as Minecraft eases it, 0.45 s each way. */
    float swimAmount() {
        return swimAmount;
    }

    /** Gliding on elytra. */
    boolean gliding() {
        return now.has(CgEntityFacts.GLIDING);
    }

    /** Spinning from a riptide trident. */
    boolean spinAttack() {
        return now.has(CgEntityFacts.SPIN_ATTACK);
    }

    boolean inWater() {
        return now.has(CgEntityFacts.IN_WATER);
    }

    boolean sleeping() {
        return now.has(CgEntityFacts.SLEEPING);
    }

    boolean riding() {
        return now.vehicle >= 0;
    }

    /** The network id of what it rides; -1 for nothing. */
    int vehicle() {
        return now.vehicle;
    }

    boolean dead() {
        return now.has(CgEntityFacts.DEAD);
    }

    /** Seconds since it died: Minecraft's death time, which runs to its body's removal at 1 s. 0 while alive. */
    float secondsDead() {
        return now.deathTime / TICKS_PER_SECOND;
    }

    /** Seconds since it was last hurt; infinite before the first hit seen. */
    float secondsSinceHurt() {
        return hurtSeconds;
    }

    /** Seconds off the ground since it left it; 0 on the ground and riding. */
    float airSeconds() {
        return grounded() ? 0f : airSeconds;
    }

    /** How far it fell to its last landing, blocks: from the highest point it reached in the air. */
    float landHeight() {
        return landHeight;
    }

    /** Its head's yaw against its body's, degrees, -180 to 180: positive to its left. */
    float headYaw() {
        return headYaw;
    }

    /** Its head's pitch, degrees: down positive. */
    float headPitch() {
        return headPitch;
    }

    /**
     * How hard it means to move, 0 to 1: the player's stick or keys (a sneak's 0.3 included) where the host has them,
     * else 1 while it moves on its own (0.3 sneaking) and 0 standing. What a locomotion graph keys its gait on, rather
     * than its speed, which a slope or a shove changes.
     */
    float input() {
        return input;
    }

    /** Which way it means to move against its facing, degrees: 0 ahead, 90 to its left, -90 to its right, 180 back. */
    float inputDirection() {
        return inputDirection;
    }

    /** Whether {@link #input} is the player's own input rather than read from its movement. */
    boolean inputFromPlayer() {
        return inputFromPlayer;
    }

    /** The sprint key held, where the player's input says; else whether it sprints. */
    boolean sprintHeld() {
        return sprintHeld;
    }

    /** The jump key held, where the player's input says; else false. */
    boolean jumpHeld() {
        return jumpHeld;
    }

    /** Whether its main hand is its left. */
    boolean leftHanded() {
        return now.has(CgEntityFacts.LEFT_HANDED);
    }

    // ── its hands ───────────────────────────────────────────────────────────────

    /** What {@code hand} holds, by the host's raw item id: 0 for nothing. */
    int item(int hand) {
        return now.item[hand];
    }

    /** How {@code hand}'s arm is posed: an {@code ARM_} constant. */
    int armPose(int hand) {
        return arms[hand];
    }

    /** Whether it is using the item in {@code hand}: eating, drawing a bow, blocking. */
    boolean using(int hand) {
        return now.has(CgEntityFacts.USING) && now.useHand == hand;
    }

    /** How the item in use looks in use: a {@code CgEntityFacts.USE_} constant; {@code USE_NONE} when using nothing. */
    int useAnimation() {
        return now.has(CgEntityFacts.USING) ? now.use[now.useHand] : CgEntityFacts.USE_NONE;
    }

    /** Seconds into the current use; 0 when using nothing. */
    float useSeconds() {
        return useSeconds;
    }

    /** How long the current use lasts at most, seconds; 0 when using nothing, or where the host cannot say. */
    float useLength() {
        return now.has(CgEntityFacts.USING) ? now.useLength / TICKS_PER_SECOND : 0f;
    }

    /** Whether an arm is swinging, and which hand's. */
    boolean swinging() {
        return now.has(CgEntityFacts.SWINGING);
    }

    int swingHand() {
        return now.swingHand;
    }

    /** The hand in use, or last in use: {@link #MAIN} or {@link #OFF}. */
    int useHand() {
        return now.useHand;
    }


    /** The swing's progress, 0 to 1: what Minecraft's own arm swing follows. */
    float swing() {
        return now.swing;
    }
    // ── as the motion type's behaviour ──────────────────────────────────────────────────

    /** A server tick's seconds. */
    private static final float TICK = 1f / TICKS_PER_SECOND;

    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];

    /** Server: reads its entity on its level, a tick on. */
    @Override
    public void tick(CgNetObject obj) {
        Object entity = obj.entity();
        if (entity == null) return;
        CgEntityQuery entities = CgEntityQueries.of(entity);
        if (entities.pose(obj.entityId(), 1f, pose) && update(entities, CgWorldQueries.of(entity), obj.entityId(), 1f, TICK, pose)) {
            publish(obj);
        } else {
            quiet(obj);
        }
    }

    /** A client: reads its entity at the frame's partial tick, {@code seconds} on. */
    @Override
    public void frame(CgNetObject obj, float seconds) {
        CgEntityQuery entities = CgEntityQueries.client();
        float partialTick = CgStageHost.frame(CgStageHost.WORLD_OPAQUE).partialTick();
        if (entities.pose(obj.entityId(), partialTick, pose)
                && update(entities, CgWorldQueries.client(), obj.entityId(), partialTick, seconds, pose)) {
            publish(obj);
        } else {
            quiet(obj);
        }
    }

    /** A frame the host said nothing of: the last values stand, and nothing happened in it. */
    private static void quiet(CgNetObject obj) {
        obj.setBool(JUMPED, false).setBool(LANDED, false).setBool(HURT, false).setBool(SWING_STARTED, false)
                .setBool(USE_STARTED, false).setBool(USE_STOPPED, false).setBool(MOUNTED, false)
                .setBool(DISMOUNTED, false).setBool(DIED, false);
    }

    /** Writes every field of {@code obj} from the last update. */
    void publish(CgNetObject obj) {
        obj.setFloat(SPEED, speed()).setFloat(VERTICAL_SPEED, verticalSpeed()).setFloat(MOVE_DIRECTION, moveDirection())
                .setFloat(ACCELERATION, acceleration()).setFloat(TURN_RATE, turnRate());
        obj.setFloat(INPUT, input()).setFloat(INPUT_DIRECTION, inputDirection()).setBool(INPUT_FROM_PLAYER, inputFromPlayer())
                .setBool(SPRINT_HELD, sprintHeld()).setBool(JUMP_HELD, jumpHeld());
        obj.setBool(GROUNDED, grounded()).setBool(AIRBORNE, !grounded() && !riding()).setFloat(AIR_TIME, airSeconds())
                .setFloat(GROUND_DISTANCE, groundDistance()).setFloat(LAND_HEIGHT, landHeight());
        obj.setBool(LIVING, living()).setBool(SNEAKING, sneaking()).setBool(SPRINTING, sprinting())
                .setBool(SWIMMING, swimming()).setBool(CRAWLING, crawling()).setFloat(SWIM_AMOUNT, swimAmount())
                .setBool(GLIDING, gliding()).setBool(SPIN_ATTACK, spinAttack()).setBool(IN_WATER, inWater())
                .setBool(RIDING, riding()).setInt(VEHICLE, vehicle()).setBool(SLEEPING, sleeping());
        obj.setBool(DEAD, dead()).setFloat(DEAD_TIME, secondsDead()).setFloat(HURT_TIME, secondsSinceHurt())
                .setFloat(HEAD_YAW, headYaw()).setFloat(HEAD_PITCH, headPitch());
        obj.setBool(LEFT_HANDED, leftHanded()).setInt(MAIN_ITEM, item(MAIN)).setInt(OFF_ITEM, item(OFF))
                .setInt(MAIN_ARM, armPose(MAIN)).setInt(OFF_ARM, armPose(OFF)).setBool(USING, using(MAIN) || using(OFF))
                .setInt(USE_HAND, useHand()).setInt(USE_ANIMATION, useAnimation()).setFloat(USE_TIME, useSeconds())
                .setFloat(USE_LENGTH, useLength()).setBool(SWINGING, swinging()).setFloat(SWING, swing())
                .setInt(SWING_HAND, swingHand());
        obj.setBool(JUMPED, fired(JUMPED_BIT)).setBool(LANDED, fired(LANDED_BIT)).setBool(HURT, fired(HURT_BIT))
                .setBool(SWING_STARTED, fired(SWING_STARTED_BIT)).setBool(USE_STARTED, fired(USE_STARTED_BIT))
                .setBool(USE_STOPPED, fired(USE_STOPPED_BIT)).setBool(MOUNTED, fired(MOUNTED_BIT))
                .setBool(DISMOUNTED, fired(DISMOUNTED_BIT)).setBool(DIED, fired(DIED_BIT));
    }
}
