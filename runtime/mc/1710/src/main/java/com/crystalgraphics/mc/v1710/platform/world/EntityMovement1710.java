package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.world.CgEntityMovement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import java.util.function.Predicate;

/** {@link CgEntityMovement} on 1.7.10: the entity's own members, both sides. It has no gravity or AI switch. */
public final class EntityMovement1710 implements CgEntityMovement.Host {

    public static final EntityMovement1710 INSTANCE = new EntityMovement1710();

    /** The client's input hold, from its client half; none on a server. */
    private volatile Predicate<Boolean> inputs = held -> false;

    private EntityMovement1710() {
    }

    @Override
    public boolean move(Object entity, double dx, double dy, double dz) {
        if (!(entity instanceof Entity)) return false;
        ((Entity) entity).moveEntity(dx, dy, dz);
        return true;
    }

    @Override
    public boolean teleport(Object entity, double x, double y, double z) {
        if (!(entity instanceof Entity)) return false;
        // a living entity's sends a player's client the move; anything else is placed and tracked as it moves
        if (entity instanceof EntityLivingBase) ((EntityLivingBase) entity).setPositionAndUpdate(x, y, z);
        else ((Entity) entity).setPosition(x, y, z);
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
        return false;
    }

    /** The client's half: holding its own player's input. From {@code Client1710.install}. */
    public void client(Predicate<Boolean> inputs) {
        this.inputs = inputs;
    }

    @Override
    public boolean gravityOn(Object entity) {
        return true;
    }

    @Override
    public boolean ai(Object entity, boolean on) {
        return false;
    }

    @Override
    public boolean aiOn(Object entity) {
        return false;
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
