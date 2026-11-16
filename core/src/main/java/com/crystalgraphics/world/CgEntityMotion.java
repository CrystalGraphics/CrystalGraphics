package com.crystalgraphics.world;

import static com.crystalgraphics.world.CgEntityProperties.*;

import com.crystalgraphics.net.object.CgNetBehaviour;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.render.host.CgStageHost;

/**
 * Works out {@link CgEntityProperties#MOTION}'s fields for one entity from what its host says of it: on the server each
 * tick, on a client each frame at the frame's partial tick. The object is the state: each field is written where it is
 * worked out, and a field read from the host's facts only when those facts changed. An event is true for one update;
 * the first update raises none. Allocates nothing.
 */
final class CgEntityMotion implements CgNetBehaviour {

    private static final float TICKS_PER_SECOND = 20f;
    /** A server tick's seconds. */
    private static final float TICK = 1f / TICKS_PER_SECOND;
    /** Below this a tick's movement is standing still, blocks: what friction leaves of a stop. */
    private static final double MOVING = 0.01;
    /** What sneaking scales Minecraft's input by ({@code Input.tick}'s 0.3, the sneaking speed attribute's default). */
    private static final float SNEAK_INPUT = 0.3f;
    /** How far down the ground is looked for, blocks. */
    private static final int GROUND_REACH = 64;
    /** Minecraft eases its swim amount 0.09 a tick toward the swim pose ({@code LivingEntity.updateSwimAmount}). */
    private static final float SWIM_EASE = 0.09f * TICKS_PER_SECOND;

    /** This update's facts, the last one's, and the one the host writes into: rotated, so a failed read changes nothing. */
    private CgEntityFacts now = new CgEntityFacts().clear(), was = new CgEntityFacts().clear(),
            read = new CgEntityFacts();
    private boolean primed;
    /** An event was raised in the last update, so this one lowers the events. */
    private boolean raised;
    /** The highest point since leaving the ground: what a landing's height is from. */
    private double peakY;
    /** The movement of the tick drawn toward and of the one before, blocks: what the frame's motion is between. */
    private double tickX, tickY, tickZ, lastTickX, lastTickY, lastTickZ;
    private float lastPartialTick, bodyYaw;
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];

    /** Server: reads its entity on its level, a tick on. */
    @Override
    public void tick(CgNetObject obj) {
        Object entity = obj.entity();
        if (entity == null) return;
        CgEntityQuery entities = CgEntityQueries.of(entity);
        if (!entities.pose(obj.entityId(), 1f, pose)
                || !update(obj, entities, CgWorldQueries.of(entity), obj.entityId(), 1f, TICK, pose)) {
            lower(obj);
        }
    }

    /** A client: reads its entity at the frame's partial tick, {@code seconds} on. */
    @Override
    public void frame(CgNetObject obj, float seconds) {
        CgEntityQuery entities = CgEntityQueries.client();
        float partialTick = CgStageHost.frame(CgStageHost.WORLD_OPAQUE).partialTick();
        if (!entities.pose(obj.entityId(), partialTick, pose)
                || !update(obj, entities, CgWorldQueries.client(), obj.entityId(), partialTick, seconds, pose)) {
            lower(obj);
        }
    }

    /**
     * Reads entity {@code id} at {@code partialTick} into {@code obj}, its state moved on by {@code dt} seconds;
     * {@code pose} is its pose at the same partial tick, as {@link CgEntityQuery#pose} wrote it, and {@code world} its
     * level's blocks, which the ground is looked for through ({@link CgWorldQuery#NONE}: never found). False, with
     * {@code obj} unchanged, where the host answers no facts for it.
     */
    boolean update(CgNetObject obj, CgEntityQuery entities, CgWorldQuery world, int id, float partialTick, float dt,
                   double[] pose) {
        if (!entities.facts(id, partialTick, read.clear())) return false;
        CgEntityFacts spare = was;
        was = now;
        now = read;
        read = spare;
        lower(obj);
        boolean first = !primed;
        if (first) {
            // first seen: as if it had been doing this all along, so nothing changes and nothing happens
            primed = true;
            was.bits = now.bits;
            was.vehicle = now.vehicle;
            was.hurtTime = now.hurtTime;
            was.swing = now.swing;
            was.useHand = now.useHand;
            was.useTicks = now.useTicks;
            peakY = now.y;
            lastTickX = tickX = now.moveX;
            lastTickY = tickY = now.moveY;
            lastTickZ = tickZ = now.moveZ;
            bodyYaw = (float) pose[CgEntityQuery.BODY_YAW];
            obj.setFloat(SWIM_AMOUNT, now.has(CgEntityFacts.SWIM_POSE) ? 1f : 0f);
        } else {
            float swim = obj.getFloat(SWIM_AMOUNT);
            obj.setFloat(SWIM_AMOUNT, now.has(CgEntityFacts.SWIM_POSE) ? Math.min(1f, swim + SWIM_EASE * dt)
                    : Math.max(0f, swim - SWIM_EASE * dt));
        }
        if (first || now.bits != was.bits || now.vehicle != was.vehicle) stance(obj);
        obj.setFloat(HEAD_YAW, wrap((float) (pose[CgEntityQuery.HEAD_YAW] - pose[CgEntityQuery.BODY_YAW])))
                .setFloat(HEAD_PITCH, (float) pose[CgEntityQuery.PITCH]);
        intent(obj, (float) pose[CgEntityQuery.YAW]);
        motion(obj, partialTick, dt, (float) pose[CgEntityQuery.BODY_YAW]);
        air(obj, dt);
        obj.setFloat(GROUND_DISTANCE, ground(world, pose));
        life(obj, dt, first);
        use(obj, partialTick, dt);
        hands(obj, first);
        return true;
    }

    /** Ends the last update's events: they last one update. */
    private void lower(CgNetObject obj) {
        if (!raised) return;
        raised = false;
        obj.setBool(JUMPED, false).setBool(LANDED, false).setBool(HURT, false).setBool(SWING_STARTED, false)
                .setBool(USE_STARTED, false).setBool(USE_STOPPED, false).setBool(MOUNTED, false)
                .setBool(DISMOUNTED, false).setBool(DIED, false);
    }

    private void raise(CgNetObject obj, CgNetField<Boolean> event) {
        obj.setBool(event, true);
        raised = true;
    }

    /** What its host's flags and its vehicle say: written when either changed. */
    private void stance(CgNetObject obj) {
        boolean grounded = now.has(CgEntityFacts.GROUNDED), riding = now.vehicle >= 0;
        boolean swimPose = now.has(CgEntityFacts.SWIM_POSE), water = now.has(CgEntityFacts.IN_WATER);
        obj.setBool(LIVING, now.has(CgEntityFacts.LIVING)).setBool(GROUNDED, grounded)
                .setBool(AIRBORNE, !grounded && !riding).setBool(SNEAKING, now.has(CgEntityFacts.SNEAKING))
                .setBool(SPRINTING, now.has(CgEntityFacts.SPRINTING)).setBool(SWIMMING, swimPose && water)
                .setBool(CRAWLING, swimPose && !water).setBool(GLIDING, now.has(CgEntityFacts.GLIDING))
                .setBool(SPIN_ATTACK, now.has(CgEntityFacts.SPIN_ATTACK)).setBool(IN_WATER, water)
                .setBool(RIDING, riding).setInt(VEHICLE, now.vehicle).setBool(SLEEPING, now.has(CgEntityFacts.SLEEPING))
                .setBool(DEAD, now.has(CgEntityFacts.DEAD)).setBool(LEFT_HANDED, now.has(CgEntityFacts.LEFT_HANDED))
                .setBool(SWINGING, now.has(CgEntityFacts.SWINGING));
    }

    /**
     * What it means to do: the player's own input where the host has it, else its movement against its facing, read back
     * through Minecraft's {@code moveRelative} (strafe s and forward f become x = s cos - f sin, z = f cos + s sin).
     */
    private void intent(CgNetObject obj, float yawDegrees) {
        float strafe, forward;
        boolean fromPlayer = now.has(CgEntityFacts.INPUT);
        if (fromPlayer) {
            strafe = now.inputStrafe;
            forward = now.inputForward;
            obj.setBool(SPRINT_HELD, now.has(CgEntityFacts.INPUT_SPRINT)).setBool(JUMP_HELD, now.has(CgEntityFacts.INPUT_JUMP));
        } else {
            double yaw = Math.toRadians(yawDegrees), c = Math.cos(yaw), sn = Math.sin(yaw);
            double mx = now.moveX, mz = now.moveZ, length = Math.sqrt(mx * mx + mz * mz);
            obj.setBool(SPRINT_HELD, now.has(CgEntityFacts.SPRINTING)).setBool(JUMP_HELD, false);
            if (length < MOVING) {
                strafe = forward = 0f;
            } else {
                float scale = (now.has(CgEntityFacts.SNEAKING) ? SNEAK_INPUT : 1f) / (float) length;
                strafe = (float) (mx * c + mz * sn) * scale;
                forward = (float) (-mx * sn + mz * c) * scale;
            }
        }
        float input = Math.min(1f, (float) Math.sqrt(strafe * strafe + forward * forward));
        obj.setBool(INPUT_FROM_PLAYER, fromPlayer).setFloat(INPUT, input)
                .setFloat(INPUT_DIRECTION, input == 0f ? 0f : (float) Math.toDegrees(Math.atan2(strafe, forward)));
    }

    /**
     * The frame's velocity, between the last two ticks' movement as the drawn position is between the last two
     * positions. A tick has passed when the partial tick wraps, or on a server (always at 1) at each update; the
     * acceleration, from those two ticks, changes only then.
     */
    private void motion(CgNetObject obj, float partialTick, float dt, float yaw) {
        if (partialTick < lastPartialTick || partialTick >= 1f || now.moveX != tickX || now.moveY != tickY
                || now.moveZ != tickZ) {
            lastTickX = tickX;
            lastTickY = tickY;
            lastTickZ = tickZ;
            tickX = now.moveX;
            tickY = now.moveY;
            tickZ = now.moveZ;
            double latest = Math.sqrt(tickX * tickX + tickZ * tickZ);
            double before = Math.sqrt(lastTickX * lastTickX + lastTickZ * lastTickZ);
            obj.setFloat(ACCELERATION, (float) (latest - before) * TICKS_PER_SECOND * TICKS_PER_SECOND);
        }
        lastPartialTick = partialTick;
        double vx = (lastTickX + (tickX - lastTickX) * partialTick) * TICKS_PER_SECOND;
        double vy = (lastTickY + (tickY - lastTickY) * partialTick) * TICKS_PER_SECOND;
        double vz = (lastTickZ + (tickZ - lastTickZ) * partialTick) * TICKS_PER_SECOND;
        // Minecraft's yaw grows turning right; the field is left positive
        if (dt > 0f) obj.setFloat(TURN_RATE, -wrap(yaw - bodyYaw) / dt);
        bodyYaw = yaw;
        double still = MOVING * TICKS_PER_SECOND, ground = vx * vx + vz * vz;
        float direction = 0f;
        if (ground >= still * still) {
            double r = Math.toRadians(yaw), c = Math.cos(r), sn = Math.sin(r);
            direction = (float) Math.toDegrees(Math.atan2(vx * c + vz * sn, -vx * sn + vz * c));
        }
        obj.setFloat(SPEED, (float) Math.sqrt(ground)).setFloat(VERTICAL_SPEED, (float) vy).setFloat(MOVE_DIRECTION, direction);
    }

    /** Its feet's height over the first surface under them; 0 on the ground, infinite where none is found. */
    private float ground(CgWorldQuery world, double[] pose) {
        if (now.has(CgEntityFacts.GROUNDED)) return 0f;
        double feet = pose[CgEntityQuery.Y];
        double floor = CgWorldQueries.groundBelow(world, pose[CgEntityQuery.X], feet, pose[CgEntityQuery.Z], GROUND_REACH);
        return Double.isNaN(floor) ? Float.POSITIVE_INFINITY : (float) Math.max(0, feet - floor);
    }

    /** Leaving the ground and touching it again, with the highest point between: what a landing's height is from. */
    private void air(CgNetObject obj, float dt) {
        boolean grounded = now.has(CgEntityFacts.GROUNDED), wasGrounded = (was.bits & CgEntityFacts.GROUNDED) != 0;
        if (now.vehicle >= 0 || grounded || wasGrounded) {
            if (!grounded && wasGrounded && now.vehicle < 0 && now.moveY > 0) raise(obj, JUMPED);
            if (grounded && !wasGrounded && now.vehicle < 0) {
                raise(obj, LANDED);
                obj.setFloat(LAND_HEIGHT, (float) Math.max(0, peakY - now.y));
            }
            obj.setFloat(AIR_TIME, 0f);
            peakY = now.y;
        } else {
            obj.setFloat(AIR_TIME, obj.getFloat(AIR_TIME) + dt);
            peakY = Math.max(peakY, now.y);
        }
    }

    /** Hurt, death, mounting and a swing's start. */
    private void life(CgNetObject obj, float dt, boolean first) {
        if (now.hurtTime > was.hurtTime) {
            raise(obj, HURT);
            obj.setFloat(HURT_TIME, 0f);
        } else {
            float hurt = obj.getFloat(HURT_TIME);
            if (hurt != Float.POSITIVE_INFINITY) obj.setFloat(HURT_TIME, hurt + dt);
        }
        if (now.has(CgEntityFacts.DEAD) && (was.bits & CgEntityFacts.DEAD) == 0) raise(obj, DIED);
        if (first || now.deathTime != was.deathTime) obj.setFloat(DEAD_TIME, now.deathTime / TICKS_PER_SECOND);
        if (now.vehicle != was.vehicle) {
            if (was.vehicle >= 0) raise(obj, DISMOUNTED);
            if (now.vehicle >= 0) raise(obj, MOUNTED);
        }
        if (now.has(CgEntityFacts.SWINGING)
                && ((was.bits & CgEntityFacts.SWINGING) == 0 || now.swing < was.swing)) raise(obj, SWING_STARTED);
    }

    private void use(CgNetObject obj, float partialTick, float dt) {
        boolean using = now.has(CgEntityFacts.USING), wasUsing = (was.bits & CgEntityFacts.USING) != 0;
        boolean restarted = wasUsing && using && (now.useHand != was.useHand
                || now.useTicks >= 0 && was.useTicks >= 0 && now.useTicks < was.useTicks);
        boolean started = using && (!wasUsing || restarted);
        if (wasUsing && (!using || restarted)) raise(obj, USE_STOPPED);
        if (started) raise(obj, USE_STARTED);
        // a host that cannot say how far in (1.7.10 and 1.8.9 servers) is counted from the start seen
        if (!using || started && now.useTicks < 0) obj.setFloat(USE_TIME, 0f);
        else if (now.useTicks >= 0) obj.setFloat(USE_TIME, (now.useTicks + partialTick) / TICKS_PER_SECOND);
        else obj.setFloat(USE_TIME, obj.getFloat(USE_TIME) + dt);
    }

    /** What each hand holds and how its arm is posed: written when what they come from changed. */
    private void hands(CgNetObject obj, boolean first) {
        if (first || now.swing != was.swing) obj.setFloat(SWING, now.swing);
        if (first || now.swingHand != was.swingHand) obj.setInt(SWING_HAND, now.swingHand);
        if (!first && now.bits == was.bits && now.useHand == was.useHand && now.useLength == was.useLength
                && now.item[MAIN] == was.item[MAIN] && now.item[OFF] == was.item[OFF]
                && now.use[MAIN] == was.use[MAIN] && now.use[OFF] == was.use[OFF]) {
            return;
        }
        boolean using = now.has(CgEntityFacts.USING);
        // Minecraft's player arm rule (26.3's AvatarRenderer.getArmPose): a two-handed pose leaves the other hand only
        // holding what it holds
        int main = arm(MAIN), off = arm(OFF);
        if (main == ARM_BOW || main == ARM_CROSSBOW_CHARGE || main == ARM_CROSSBOW_HOLD) {
            off = now.item[OFF] == 0 ? ARM_EMPTY : ARM_ITEM;
        }
        obj.setInt(MAIN_ITEM, now.item[MAIN]).setInt(OFF_ITEM, now.item[OFF]).setInt(MAIN_ARM, main).setInt(OFF_ARM, off)
                .setBool(USING, using).setInt(USE_HAND, now.useHand)
                .setInt(USE_ANIMATION, using ? now.use[now.useHand] : CgEntityFacts.USE_NONE)
                .setFloat(USE_LENGTH, using ? now.useLength / TICKS_PER_SECOND : 0f);
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
}
