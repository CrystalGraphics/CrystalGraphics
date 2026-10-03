package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
//? if >=1.20.3 {
/*import net.minecraft.world.entity.vehicle.VehicleEntity;
*///?} else {
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
//?}
//? if >=1.14 {
import net.minecraft.world.phys.Vec3;
//?}

import java.util.function.IntConsumer;

/**
 * {@link CgEntityQuery} over the client's level, 1.13.2 to 26.3. Client only: constructed by
 * {@code PlatformServiceModern.gl()}. Render thread; allocates nothing but the iterator of {@link #within}'s walk.
 *
 * <ul>
 *   <li>1.13.2 answers no velocity (zero), and walks a box through the level's own query, a list per call.</li>
 *   <li>Vehicles are minecarts and boats, as Minecraft's own vehicle class has them from 1.20.3.</li>
 * </ul>
 */
public final class EntityQueryModern implements CgEntityQuery {

    @Override
    public int localPlayer() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? -1 : mc.player.getId();
    }

    @Override
    public int cameraEntity() {
        Entity camera = Minecraft.getInstance().getCameraEntity();
        return camera == null ? -1 : camera.getId();
    }

    private static Entity entity(int id) {
        Level level = Minecraft.getInstance().level;
        return level == null || id < 0 ? null : level.getEntity(id);
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        Entity e = entity(id);
        if (e == null) return false;
        //? if >=1.15 {
        double x = e.getX(), y = e.getY(), z = e.getZ();
        //?} else {
        /*double x = e.x, y = e.y, z = e.z;
        *///?}
        out[X] = e.xo + (x - e.xo) * partialTick;
        out[Y] = e.yo + (y - e.yo) * partialTick;
        out[Z] = e.zo + (z - e.zo) * partialTick;
        //? if >=1.14 {
        Vec3 v = e.getDeltaMovement();
        out[VELOCITY_X] = v.x;
        out[VELOCITY_Y] = v.y;
        out[VELOCITY_Z] = v.z;
        //?} else {
        /*out[VELOCITY_X] = out[VELOCITY_Y] = out[VELOCITY_Z] = 0.0;
        *///?}
        AABB box = e.getBoundingBox();
        out[WIDTH] = box.maxX - box.minX;
        out[HEIGHT] = box.maxY - box.minY;
        out[EYE_HEIGHT] = e.getEyeHeight();
        //? if >=1.17 {
        float yaw = e.getYRot(), pitch = e.getXRot();
        //?} else {
        /*float yaw = e.yRot, pitch = e.xRot;
        *///?}
        out[YAW] = angle(e.yRotO, yaw, partialTick);
        out[PITCH] = e.xRotO + (pitch - e.xRotO) * partialTick;
        if (e instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) e;
            out[BODY_YAW] = angle(living.yBodyRotO, living.yBodyRot, partialTick);
            out[HEAD_YAW] = angle(living.yHeadRotO, living.yHeadRot, partialTick);
            //? if >=1.19.4 {
            out[LIMB_SWING] = living.walkAnimation.position(partialTick);
            out[LIMB_SWING_AMOUNT] = living.walkAnimation.speed(partialTick);
            //?} else {
            /*out[LIMB_SWING] = living.animationPosition - living.animationSpeed * (1f - partialTick);
            out[LIMB_SWING_AMOUNT] = living.animationSpeedOld + (living.animationSpeed - living.animationSpeedOld) * partialTick;
            *///?}
        } else {
            out[BODY_YAW] = out[HEAD_YAW] = out[YAW];
            out[LIMB_SWING] = out[LIMB_SWING_AMOUNT] = 0.0;
        }
        return true;
    }

    /** From {@code from} to {@code to} degrees by {@code t}, the short way round. */
    private static double angle(float from, float to, float t) {
        float d = to - from;
        d -= (float) Math.floor((d + 180f) / 360f) * 360f;
        return from + d * t;
    }

    @Override
    public int flags(int id) {
        Entity e = entity(id);
        if (e == null) return 0;
        int f = 0;
        if (e.isAlive()) f |= ALIVE;
        if (e instanceof LivingEntity) {
            f |= LIVING;
            if (((LivingEntity) e).isFallFlying()) f |= GLIDING;
        }
        if (e instanceof Player) f |= PLAYER;
        if (e == Minecraft.getInstance().player) f |= LOCAL_PLAYER;
        //? if >=1.20 {
        if (e.onGround()) f |= ON_GROUND;
        //?} elif >=1.16.5 {
        /*if (e.isOnGround()) f |= ON_GROUND;
        *///?} else {
        /*if (e.onGround) f |= ON_GROUND;
        *///?}
        //? if >=1.15 {
        if (e.isShiftKeyDown()) f |= SNEAKING;
        //?} else {
        /*if (e.isSneaking()) f |= SNEAKING;
        *///?}
        if (e.isSprinting()) f |= SPRINTING;
        if (e.isSwimming()) f |= SWIMMING;
        if (e.isInWater()) f |= IN_WATER;
        if (e.isOnFire()) f |= ON_FIRE;
        if (e.isInvisible()) f |= INVISIBLE;
        return f;
    }

    @Override
    public int kind(int id) {
        Entity e = entity(id);
        if (e instanceof Player) return KIND_PLAYER;
        if (e instanceof Enemy) return KIND_MONSTER;
        if (e instanceof Animal) return KIND_ANIMAL;
        if (e instanceof ItemEntity) return KIND_ITEM;
        if (e instanceof Projectile) return KIND_PROJECTILE;
        //? if >=1.20.3 {
        /*if (e instanceof VehicleEntity) return KIND_VEHICLE;
        *///?} else {
        if (e instanceof AbstractMinecart || e instanceof Boat) return KIND_VEHICLE;
        //?}
        return KIND_OTHER;
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        //? if >=1.14 {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getBoundingBox().intersects(minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(e.getId());
        }
        //?} else {
        /*// 1.13 lists no entities for rendering: the level's own box query, a list per call.
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        for (Entity e : level.getEntities((Entity) null, new AABB(minX, minY, minZ, maxX, maxY, maxZ))) {
            visitor.accept(e.getId());
        }
        *///?}
    }
}
