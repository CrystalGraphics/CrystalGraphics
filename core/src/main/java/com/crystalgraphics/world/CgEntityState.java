package com.crystalgraphics.world;

import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;

/**
 * What one entity is doing this frame, for an animation to follow: how fast it moves, whether it is on the ground,
 * sneaking, swimming, gliding, riding or sleeping, what each hand holds and how its arm is posed, how far into a use or
 * a swing it is, and what just happened (a jump, a landing, a hit, a swing, a death). The same on every Minecraft
 * version and on both sides; an entity actor's is {@code CgEntity.state()}, refreshed before its tick.
 *
 * <pre>{@code
 * protected void tick(CgEntity fighter, float dt) {
 *     CgEntityState s = fighter.state();
 *     body.anim().set(SPEED, s.speed());
 *     if (s.happened(CgEntityState.LANDED) && s.landHeight() > 3) body.anim().montages().play(HARD_LANDING);
 *     if (s.happened(CgEntityState.SWING_STARTED)) body.anim().montages().play(SLASH);
 * }
 * }</pre>
 *
 * Any entity, kept by whoever watches it:
 * <pre>{@code
 * CgEntityState state = new CgEntityState();                     // one per entity, kept while it lives
 * double[] pose = new double[CgEntityQuery.POSE_LENGTH];
 * // each frame:
 * if (entities.pose(id, partialTick, pose)) state.update(entities, id, partialTick, dt, pose);
 * }</pre>
 *
 * <ul>
 *   <li>Events last one {@link #update}: read them in the frame they happen. The first update after construction or
 *       {@link #reset} raises none, so an entity seen mid-jump does not jump.</li>
 *   <li>A state a version lacks reads as not happening (no gliding before 1.10.2, no off hand before 1.10.2).</li>
 *   <li>The arm pose is Minecraft's own rule for a player's arms, the latest version's, on every version and for every
 *       entity: a mob's renderer may pose its arms its own way.</li>
 *   <li>It allocates nothing.</li>
 * </ul>
 */
public final class CgEntityState {

    /** {@link #happened} events. */
    public static final int JUMPED = 1, LANDED = 1 << 1, HURT = 1 << 2, SWING_STARTED = 1 << 3, USE_STARTED = 1 << 4,
            USE_STOPPED = 1 << 5, MOUNTED = 1 << 6, DISMOUNTED = 1 << 7, DIED = 1 << 8;

    /** How an arm is posed ({@link #armPose}): Minecraft's {@code HumanoidModel.ArmPose}. */
    public static final int ARM_EMPTY = 0, ARM_ITEM = 1, ARM_BLOCK = 2, ARM_BOW = 3, ARM_THROW = 4,
            ARM_CROSSBOW_CHARGE = 5, ARM_CROSSBOW_HOLD = 6, ARM_SPYGLASS = 7, ARM_TOOT_HORN = 8, ARM_BRUSH = 9,
            ARM_SPEAR = 10;

    /** The hands, as {@link CgEntityFacts} names them. */
    public static final int MAIN = CgEntityFacts.MAIN, OFF = CgEntityFacts.OFF;

    private static final float TICKS_PER_SECOND = 20f;
    /** Below this a tick's movement is standing still, blocks: what friction leaves of a stop. */
    private static final double MOVING = 0.01;
    /** What sneaking scales Minecraft's input by ({@code Input.tick}'s 0.3, the sneaking speed attribute's default). */
    private static final float SNEAK_INPUT = 0.3f;
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

    /**
     * Reads entity {@code id} at {@code partialTick} and moves the state on by {@code dt} seconds; {@code pose} is its
     * pose at the same partial tick, as {@link CgEntityQuery#pose} wrote it. False, with the state unchanged, where the
     * host answers no facts for it.
     */
    public boolean update(CgEntityQuery entities, int id, float partialTick, float dt, double[] pose) {
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
        } else {
            swimAmount = now.has(CgEntityFacts.SWIM_POSE) ? Math.min(1f, swimAmount + SWIM_EASE * dt)
                    : Math.max(0f, swimAmount - SWIM_EASE * dt);
        }
        headYaw = wrap((float) (pose[CgEntityQuery.HEAD_YAW] - pose[CgEntityQuery.BODY_YAW]));
        headPitch = (float) pose[CgEntityQuery.PITCH];
        intent((float) pose[CgEntityQuery.YAW]);
        air(dt);
        if (now.hurtTime > was.hurtTime) {
            events |= HURT;
            hurtSeconds = 0f;
        } else {
            hurtSeconds += dt;
        }
        if (now.has(CgEntityFacts.DEAD) && (was.bits & CgEntityFacts.DEAD) == 0) events |= DIED;
        if (now.vehicle != was.vehicle) {
            if (was.vehicle >= 0) events |= DISMOUNTED;
            if (now.vehicle >= 0) events |= MOUNTED;
        }
        boolean swinging = now.has(CgEntityFacts.SWINGING);
        if (swinging && ((was.bits & CgEntityFacts.SWINGING) == 0 || now.swing < was.swing)) events |= SWING_STARTED;
        use(partialTick, dt);
        arms();
        return true;
    }

    /** Forgets what it saw: the next {@link #update} starts it afresh, raising no events. */
    public void reset() {
        now.clear();
        was.clear();
        primed = false;
        events = 0;
        swimAmount = useSeconds = airSeconds = landHeight = input = inputDirection = 0f;
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

    /** Leaving the ground and touching it again, with the highest point between: what a landing's height is from. */
    private void air(float dt) {
        boolean grounded = now.has(CgEntityFacts.GROUNDED), wasGrounded = (was.bits & CgEntityFacts.GROUNDED) != 0;
        if (now.vehicle >= 0) {
            airSeconds = 0f;
            peakY = now.y;
            return;
        }
        if (wasGrounded && !grounded) {
            if (now.moveY > 0) events |= JUMPED;
            airSeconds = 0f;
            peakY = now.y;
        } else if (!grounded) {
            airSeconds += dt;
            peakY = Math.max(peakY, now.y);
        } else if (!wasGrounded) {
            events |= LANDED;
            landHeight = (float) Math.max(0, peakY - now.y);
        }
        if (grounded) peakY = now.y;
    }

    private void use(float partialTick, float dt) {
        boolean using = now.has(CgEntityFacts.USING), wasUsing = (was.bits & CgEntityFacts.USING) != 0;
        boolean restarted = wasUsing && using && (now.useHand != was.useHand
                || now.useTicks >= 0 && was.useTicks >= 0 && now.useTicks < was.useTicks);
        boolean started = using && (!wasUsing || restarted);
        if (wasUsing && (!using || restarted)) events |= USE_STOPPED;
        if (started) events |= USE_STARTED;
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

    /** Whether {@code event} ({@link #JUMPED}, {@link #LANDED}, ...) happened in the last {@link #update}. */
    public boolean happened(int event) {
        return (events & event) != 0;
    }

    /** Every event of the last {@link #update}, as bits. */
    public int events() {
        return events;
    }

    /** Its speed along the ground, blocks a second, over its last tick. */
    public float speed() {
        return (float) Math.sqrt(now.moveX * now.moveX + now.moveZ * now.moveZ) * TICKS_PER_SECOND;
    }

    /** Its vertical speed, blocks a second, over its last tick: up positive. */
    public float verticalSpeed() {
        return (float) now.moveY * TICKS_PER_SECOND;
    }

    public boolean living() {
        return now.has(CgEntityFacts.LIVING);
    }

    public boolean grounded() {
        return now.has(CgEntityFacts.GROUNDED);
    }

    public boolean sneaking() {
        return now.has(CgEntityFacts.SNEAKING);
    }

    public boolean sprinting() {
        return now.has(CgEntityFacts.SPRINTING);
    }

    /** In the swimming pose: swimming in water, or crawling out of it. */
    public boolean swimming() {
        return now.has(CgEntityFacts.SWIM_POSE) && now.has(CgEntityFacts.IN_WATER);
    }

    /** In the swimming pose out of water: under a block too low to stand in. */
    public boolean crawling() {
        return now.has(CgEntityFacts.SWIM_POSE) && !now.has(CgEntityFacts.IN_WATER);
    }

    /** 0 standing to 1 lying in the swimming pose, eased as Minecraft eases it, 0.45 s each way. */
    public float swimAmount() {
        return swimAmount;
    }

    /** Gliding on elytra. */
    public boolean gliding() {
        return now.has(CgEntityFacts.GLIDING);
    }

    /** Spinning from a riptide trident. */
    public boolean spinAttack() {
        return now.has(CgEntityFacts.SPIN_ATTACK);
    }

    public boolean inWater() {
        return now.has(CgEntityFacts.IN_WATER);
    }

    public boolean sleeping() {
        return now.has(CgEntityFacts.SLEEPING);
    }

    public boolean riding() {
        return now.vehicle >= 0;
    }

    /** The network id of what it rides; -1 for nothing. */
    public int vehicle() {
        return now.vehicle;
    }

    public boolean dead() {
        return now.has(CgEntityFacts.DEAD);
    }

    /** Seconds since it died: Minecraft's death time, which runs to its body's removal at 1 s. 0 while alive. */
    public float secondsDead() {
        return now.deathTime / TICKS_PER_SECOND;
    }

    /** Seconds since it was last hurt; infinite before the first hit seen. */
    public float secondsSinceHurt() {
        return hurtSeconds;
    }

    /** Seconds off the ground since it left it; 0 on the ground and riding. */
    public float airSeconds() {
        return grounded() ? 0f : airSeconds;
    }

    /** How far it fell to its last landing, blocks: from the highest point it reached in the air. */
    public float landHeight() {
        return landHeight;
    }

    /** Its head's yaw against its body's, degrees, -180 to 180: positive to its left. */
    public float headYaw() {
        return headYaw;
    }

    /** Its head's pitch, degrees: down positive. */
    public float headPitch() {
        return headPitch;
    }

    /**
     * How hard it means to move, 0 to 1: the player's stick or keys (a sneak's 0.3 included) where the host has them,
     * else 1 while it moves on its own (0.3 sneaking) and 0 standing. What a locomotion graph keys its gait on, rather
     * than its speed, which a slope or a shove changes.
     */
    public float input() {
        return input;
    }

    /** Which way it means to move against its facing, degrees: 0 ahead, 90 to its left, -90 to its right, 180 back. */
    public float inputDirection() {
        return inputDirection;
    }

    /** Whether {@link #input} is the player's own input rather than read from its movement. */
    public boolean inputFromPlayer() {
        return inputFromPlayer;
    }

    /** The sprint key held, where the player's input says; else whether it sprints. */
    public boolean sprintHeld() {
        return sprintHeld;
    }

    /** The jump key held, where the player's input says; else false. */
    public boolean jumpHeld() {
        return jumpHeld;
    }

    /** Whether its main hand is its left. */
    public boolean leftHanded() {
        return now.has(CgEntityFacts.LEFT_HANDED);
    }

    // ── its hands ───────────────────────────────────────────────────────────────

    /** What {@code hand} holds, by the host's raw item id: 0 for nothing. */
    public int item(int hand) {
        return now.item[hand];
    }

    /** How {@code hand}'s arm is posed: an {@code ARM_} constant. */
    public int armPose(int hand) {
        return arms[hand];
    }

    /** Whether it is using the item in {@code hand}: eating, drawing a bow, blocking. */
    public boolean using(int hand) {
        return now.has(CgEntityFacts.USING) && now.useHand == hand;
    }

    /** How the item in use looks in use: a {@code CgEntityFacts.USE_} constant; {@code USE_NONE} when using nothing. */
    public int useAnimation() {
        return now.has(CgEntityFacts.USING) ? now.use[now.useHand] : CgEntityFacts.USE_NONE;
    }

    /** Seconds into the current use; 0 when using nothing. */
    public float useSeconds() {
        return useSeconds;
    }

    /** How long the current use lasts at most, seconds; 0 when using nothing, or where the host cannot say. */
    public float useLength() {
        return now.has(CgEntityFacts.USING) ? now.useLength / TICKS_PER_SECOND : 0f;
    }

    /** Whether an arm is swinging, and which hand's. */
    public boolean swinging() {
        return now.has(CgEntityFacts.SWINGING);
    }

    public int swingHand() {
        return now.swingHand;
    }

    /** The swing's progress, 0 to 1: what Minecraft's own arm swing follows. */
    public float swing() {
        return now.swing;
    }
}
