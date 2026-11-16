package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgEntityMovement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import java.util.function.Predicate;
//? if >=1.12 {
import net.minecraft.entity.MoverType;
//?}

/** {@link CgEntityMovement} on Forge 1.8.9 to 1.12.2: the entity's own members, both sides. */
public final class EntityMovementLegacy implements CgEntityMovement.Host {

    public static final EntityMovementLegacy INSTANCE = new EntityMovementLegacy();

    /** The client's input hold, from its client half; none on a server. */
    private volatile Predicate<Boolean> inputs = held -> false;

    private EntityMovementLegacy() {
    }

    @Override
    public boolean move(Object entity, double dx, double dy, double dz) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        //? if >=1.12 {
        e.move(MoverType.SELF, dx, dy, dz);
        //?} else if >=1.10 {
        /*e.move(dx, dy, dz);
        *///?} else {
        /*e.moveEntity(dx, dy, dz);
        *///?}
        return true;
    }

    @Override
    public boolean teleport(Object entity, double x, double y, double z) {
        if (!(entity instanceof Entity)) return false;
        // a server player's sends its client the move
        ((Entity) entity).setPositionAndUpdate(x, y, z);
        return true;
    }

    @Override
    public boolean rotate(Object entity, float yaw, float pitch) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        e.rotationYaw = yaw;
        e.rotationPitch = pitch;
        if (e instanceof EntityLivingBase) {
            ((EntityLivingBase) e).renderYawOffset = yaw;
            ((EntityLivingBase) e).rotationYawHead = yaw;
        }
        return true;
    }

    @Override
    public float yaw(Object entity) {
        return entity instanceof Entity ? ((Entity) entity).rotationYaw : Float.NaN;
    }

    @Override
    public float pitch(Object entity) {
        return entity instanceof Entity ? ((Entity) entity).rotationPitch : Float.NaN;
    }

    @Override
    public boolean velocity(Object entity, double vx, double vy, double vz) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        e.motionX = vx;
        e.motionY = vy;
        e.motionZ = vz;
        e.velocityChanged = true;
        return true;
    }

    @Override
    public boolean push(Object entity, double vx, double vy, double vz) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        e.addVelocity(vx, vy, vz);
        e.velocityChanged = true;
        return true;
    }

    @Override
    public boolean gravity(Object entity, boolean on) {
        if (!(entity instanceof Entity)) return false;
        //? if >=1.10 {
        ((Entity) entity).setNoGravity(!on);
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    /** The client's half: holding its own player's input. From {@code ClientLegacy.install}. */
    public void client(Predicate<Boolean> inputs) {
        this.inputs = inputs;
    }

    @Override
    public boolean gravityOn(Object entity) {
        //? if >=1.10 {
        return !(entity instanceof Entity) || !((Entity) entity).hasNoGravity();
        //?} else {
        /*return true;
        *///?}
    }

    @Override
    public boolean ai(Object entity, boolean on) {
        if (!(entity instanceof EntityLiving)) return false;
        ((EntityLiving) entity).setNoAI(!on);
        return true;
    }

    @Override
    public boolean aiOn(Object entity) {
        return entity instanceof EntityLiving && !((EntityLiving) entity).isAIDisabled();
    }

    @Override
    public boolean holdInput(boolean held) {
        return inputs.test(held);
    }

    @Override
    public boolean resetFall(Object entity) {
        if (!(entity instanceof Entity)) return false;
        ((Entity) entity).fallDistance = 0f;
        return true;
    }
}
