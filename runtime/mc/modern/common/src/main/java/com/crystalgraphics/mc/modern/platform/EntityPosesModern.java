package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.render.entity.CgEntityPose;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
//? if >=1.21.10 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
*///?}
//? if >=1.21.10 <26.3 {
/*import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
*///?} elif >=26.3 {
/*import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
*///?}

/**
 * The one pose step of the modern tree's entity draws, pictures and the world's: a {@link CgEntityPose} set on the
 * game's entity for one draw and every field it touched put back after, so a live entity keeps its own. Allocation-free;
 * one per host, render thread. Where a draw goes through a render state (pictures from 1.21.11, the world from
 * 1.21.10), {@link #state} sets the state instead and nothing needs putting back.
 *
 * <pre>{@code
 * poses.apply(entity, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
 * try { draw(entity); } finally { poses.restore(entity); }
 * }</pre>
 */
final class EntityPosesModern {

    //? if <1.21.11 {
    private float yaw, yawO, pitch, pitchO, body, bodyO, head, headO, walkPosition, walkSpeed, walkSpeedOld, attack,
            attackOld;
    private int age;
    private boolean living, aged, walked, swung;

    /**
     * Sets {@code entity}'s angles, in the game's degrees, and {@code pose}'s motion: a walking pose's cycle at
     * {@code ageTicks} times its speed. {@code setAge} also sets its {@code tickCount} to {@code ageTicks}, as a child
     * entity's idle animation runs from its spawn; a picture keeps the entity's own, as the inventory does.
     */
    void apply(Entity entity, float bodyYaw, float headYaw, float pitchDegrees, CgEntityPose pose, float ageTicks,
               boolean setAge) {
        yaw = yaw(entity);
        yawO = entity.yRotO;
        pitch = pitch(entity);
        pitchO = entity.xRotO;
        yaw(entity, headYaw);
        entity.yRotO = headYaw;
        pitch(entity, pitchDegrees);
        entity.xRotO = pitchDegrees;
        aged = setAge;
        if (setAge) {
            age = entity.tickCount;
            entity.tickCount = (int) ageTicks;
        }
        living = entity instanceof LivingEntity;
        walked = swung = false;
        if (!living) return;
        LivingEntity l = (LivingEntity) entity;
        body = l.yBodyRot;
        bodyO = l.yBodyRotO;
        head = l.yHeadRot;
        headO = l.yHeadRotO;
        l.yBodyRot = l.yBodyRotO = bodyYaw;
        l.yHeadRot = l.yHeadRotO = headYaw;
        if (!pose.ownWalk()) walked = walk(l, ageTicks * pose.walkSpeed(), pose.walkSpeed());
        if (!pose.ownSwing()) {
            swung = true;
            attack = l.attackAnim;
            attackOld = l.oAttackAnim;
            l.attackAnim = l.oAttackAnim = pose.swing();
        }
    }

    /** Puts back everything {@link #apply} set on {@code entity}. */
    void restore(Entity entity) {
        yaw(entity, yaw);
        entity.yRotO = yawO;
        pitch(entity, pitch);
        entity.xRotO = pitchO;
        if (aged) entity.tickCount = age;
        if (!living) return;
        LivingEntity l = (LivingEntity) entity;
        l.yBodyRot = body;
        l.yBodyRotO = bodyO;
        l.yHeadRot = head;
        l.yHeadRotO = headO;
        if (walked) unwalk(l);
        if (swung) {
            l.attackAnim = attack;
            l.oAttackAnim = attackOld;
        }
    }

    // The walk cycle's fields: public to 1.19.3, WalkAnimationState's private ones after
    private boolean walk(LivingEntity l, float position, float speed) {
        //? if <1.19.4 {
        /*walkPosition = l.animationPosition;
        walkSpeed = l.animationSpeed;
        walkSpeedOld = l.animationSpeedOld;
        l.animationPosition = position;
        l.animationSpeed = l.animationSpeedOld = speed;
        return true;
        *///?} else {
        if (!((Object) l.walkAnimation instanceof HostWalk)) return false;
        walkPosition = l.walkAnimation.position();
        walkSpeed = l.walkAnimation.speed();
        walkSpeedOld = l.walkAnimation.speed(0);
        ((HostWalk) (Object) l.walkAnimation).crystalgraphics$walk(position, speed, speed);
        return true;
        //?}
    }

    private void unwalk(LivingEntity l) {
        //? if <1.19.4 {
        /*l.animationPosition = walkPosition;
        l.animationSpeed = walkSpeed;
        l.animationSpeedOld = walkSpeedOld;
        *///?} else {
        ((HostWalk) (Object) l.walkAnimation).crystalgraphics$walk(walkPosition, walkSpeed, walkSpeedOld);
        //?}
    }

    private static float yaw(Entity e) {
        //? if >=1.17 {
        return e.getYRot();
        //?} else {
        /*return e.yRot;
        *///?}
    }

    private static void yaw(Entity e, float yaw) {
        //? if >=1.17 {
        e.setYRot(yaw);
        //?} else {
        /*e.yRot = yaw;
        *///?}
    }

    private static float pitch(Entity e) {
        //? if >=1.17 {
        return e.getXRot();
        //?} else {
        /*return e.xRot;
        *///?}
    }

    private static void pitch(Entity e, float pitch) {
        //? if >=1.17 {
        e.setXRot(pitch);
        //?} else {
        /*e.xRot = pitch;
        *///?}
    }
    //?}

    //? if >=1.21.10 {
    /*// A render state's angles, in its own terms, and the pose's motion: made for this draw, so nothing goes back
    static void state(EntityRenderState state, float bodyRot, float yRot, float xRot, CgEntityPose pose, float ageTicks) {
        if (!(state instanceof LivingEntityRenderState)) return;
        LivingEntityRenderState l = (LivingEntityRenderState) state;
        l.bodyRot = bodyRot;
        l.yRot = yRot;
        l.xRot = xRot;
        if (!pose.ownWalk()) {
            l.walkAnimationPos = ageTicks * pose.walkSpeed();
            l.walkAnimationSpeed = pose.walkSpeed();
        }
        if (!pose.ownSwing()) swing(state, pose.swing());
    }
    *///?}
    //? if >=1.21.10 <26.3 {
    /*// A humanoid's arm swing: the field moved from HumanoidRenderState to its parent in 1.21.11, read through either
    private static void swing(EntityRenderState state, float progress) {
        if (state instanceof HumanoidRenderState) ((HumanoidRenderState) state).attackTime = progress;
    }
    *///?} elif >=26.3 {
    /*// 26.3's name for it, on every armed entity
    private static void swing(EntityRenderState state, float progress) {
        if (state instanceof ArmedEntityRenderState) ((ArmedEntityRenderState) state).swingAnimation = progress;
    }
    *///?}
}
