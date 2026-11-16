package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.world.CgEntityMovement;
import net.minecraft.world.entity.Entity;
//? if >=1.14.3 {
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
//?}

/** {@link CgEntityMovement} on Forge 1.13.2+, NeoForge and Fabric: the entity's own members, both sides. */
public final class EntityMovementModern implements CgEntityMovement.Host {

    public static final EntityMovementModern INSTANCE = new EntityMovementModern();

    private EntityMovementModern() {
    }

    @Override
    public boolean move(Object entity, double dx, double dy, double dz) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        //? if >=1.14.3 {
        e.move(MoverType.SELF, new Vec3(dx, dy, dz));
        //?} else {
        /*// 1.13.2's move is not reachable through its mapped names: placed, unswept
        e.absMoveTo(e.x + dx, e.y + dy, e.z + dz, e.yRot, e.xRot);
        *///?}
        return true;
    }

    @Override
    public boolean teleport(Object entity, double x, double y, double z) {
        if (!(entity instanceof Entity)) return false;
        // a server player's sends its client the move
        ((Entity) entity).teleportTo(x, y, z);
        return true;
    }

    @Override
    public boolean rotate(Object entity, float yaw, float pitch) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        //? if >=1.17.1 {
        e.setYRot(yaw);
        e.setXRot(pitch);
        //?} else {
        /*e.yRot = yaw;
        e.xRot = pitch;
        *///?}
        e.setYBodyRot(yaw);
        e.setYHeadRot(yaw);
        return true;
    }

    @Override
    public float yaw(Object entity) {
        if (!(entity instanceof Entity)) return Float.NaN;
        //? if >=1.17.1 {
        return ((Entity) entity).getYRot();
        //?} else {
        /*return ((Entity) entity).yRot;
        *///?}
    }

    @Override
    public float pitch(Object entity) {
        if (!(entity instanceof Entity)) return Float.NaN;
        //? if >=1.17.1 {
        return ((Entity) entity).getXRot();
        //?} else {
        /*return ((Entity) entity).xRot;
        *///?}
    }

    @Override
    public boolean velocity(Object entity, double vx, double vy, double vz) {
        if (!(entity instanceof Entity)) return false;
        //? if >=1.14.3 {
        Entity e = (Entity) entity;
        e.setDeltaMovement(vx, vy, vz);
        sync(e);
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public boolean push(Object entity, double vx, double vy, double vz) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        e.push(vx, vy, vz);
        sync(e);
        return true;
    }

    /** Its velocity sent to its clients at the tracker's next update, as a hit's knockback is. */
    private static void sync(Entity e) {
        //? if >=26.3 {
        /*e.syncVelocity = true;
        *///?} else {
        e.hurtMarked = true;
        //?}
    }

    @Override
    public boolean gravity(Object entity, boolean on) {
        if (!(entity instanceof Entity)) return false;
        ((Entity) entity).setNoGravity(!on);
        return true;
    }

    @Override
    public boolean resetFall(Object entity) {
        if (!(entity instanceof Entity)) return false;
        // a float to 1.21.4, a double after: the literal serves both
        ((Entity) entity).fallDistance = 0;
        return true;
    }
}
