package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.render.entity.CgEntityPose;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
//? if >=1.10 {
import net.minecraft.util.EnumHand;
//?}

/**
 * The one pose step of legacy Forge's entity draws, pictures and the world's: a {@link CgEntityPose} set on the game's
 * entity for one draw and every field it touched put back after, so a live entity keeps its own. Allocation-free; one
 * per host, render thread.
 *
 * <pre>{@code
 * poses.apply(entity, pose.bodyYaw(), pose.headYaw(), pose.pitch(), pose, ageTicks, false);
 * try { draw(entity); } finally { poses.restore(entity); }
 * }</pre>
 */
final class EntityPosesLegacy {

    private float yaw, yawO, pitch, pitchO, body, bodyO, head, headO, limb, limbAmount, limbAmountO, swing, swingO;
    private int age;
    //? if >=1.10 {
    private EnumHand hand;
    //?}
    private boolean living, aged, walked, swung;

    /**
     * Sets {@code entity}'s angles and {@code pose}'s motion: a walking pose's limbs at {@code ageTicks} times its speed.
     * {@code setAge} also sets its {@code ticksExisted}, as a child entity's idle animation runs from its spawn.
     */
    void apply(Entity entity, float bodyYaw, float headYaw, float pitchDegrees, CgEntityPose pose, float ageTicks,
               boolean setAge) {
        yaw = entity.rotationYaw;
        yawO = entity.prevRotationYaw;
        pitch = entity.rotationPitch;
        pitchO = entity.prevRotationPitch;
        entity.rotationYaw = entity.prevRotationYaw = headYaw;
        entity.rotationPitch = entity.prevRotationPitch = pitchDegrees;
        aged = setAge;
        if (setAge) {
            age = entity.ticksExisted;
            entity.ticksExisted = (int) ageTicks;
        }
        living = entity instanceof EntityLivingBase;
        walked = swung = false;
        if (!living) return;
        EntityLivingBase l = (EntityLivingBase) entity;
        body = l.renderYawOffset;
        bodyO = l.prevRenderYawOffset;
        head = l.rotationYawHead;
        headO = l.prevRotationYawHead;
        l.renderYawOffset = l.prevRenderYawOffset = bodyYaw;
        l.rotationYawHead = l.prevRotationYawHead = headYaw;
        if (!pose.ownWalk()) {
            walked = true;
            limb = l.limbSwing;
            limbAmount = l.limbSwingAmount;
            limbAmountO = l.prevLimbSwingAmount;
            l.limbSwing = ageTicks * pose.walkSpeed();
            l.limbSwingAmount = l.prevLimbSwingAmount = pose.walkSpeed();
        }
        if (!pose.ownSwing()) {
            swung = true;
            swing = l.swingProgress;
            swingO = l.prevSwingProgress;
            l.swingProgress = l.prevSwingProgress = pose.swing();
            //? if >=1.10 {
            // The main arm: one never swung reads as its off hand's (ModelBiped.getMainHand); 1.8.9 has the right only
            hand = l.swingingHand;
            l.swingingHand = EnumHand.MAIN_HAND;
            //?}
        }
    }

    /** Puts back everything {@link #apply} set on {@code entity}. */
    void restore(Entity entity) {
        entity.rotationYaw = yaw;
        entity.prevRotationYaw = yawO;
        entity.rotationPitch = pitch;
        entity.prevRotationPitch = pitchO;
        if (aged) entity.ticksExisted = age;
        if (!living) return;
        EntityLivingBase l = (EntityLivingBase) entity;
        l.renderYawOffset = body;
        l.prevRenderYawOffset = bodyO;
        l.rotationYawHead = head;
        l.prevRotationYawHead = headO;
        if (walked) {
            l.limbSwing = limb;
            l.limbSwingAmount = limbAmount;
            l.prevLimbSwingAmount = limbAmountO;
        }
        if (swung) {
            l.swingProgress = swing;
            l.prevSwingProgress = swingO;
            //? if >=1.10 {
            l.swingingHand = hand;
            //?}
        }
    }
}
