package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
//? if >=1.9 {
import net.minecraft.util.math.AxisAlignedBB;
//?} else {
/*import net.minecraft.util.AxisAlignedBB;
*///?}

import java.util.List;
import java.util.function.IntConsumer;

/**
 * {@link CgEntityQuery} over one level on Forge 1.8.9 to 1.12.2, a server's included. Server-safe;
 * {@link LevelQueriesLegacy} hands one out per level, and the client's {@link EntityQueryLegacy} extends it with its
 * local and camera entity. The level's thread.
 *
 * <ul>
 *   <li>No entity swims before 1.13, and none glides before 1.9.</li>
 *   <li>{@link #within} walks the level's own box query: a list per call.</li>
 * </ul>
 */
public class LevelEntityQueryLegacy implements CgEntityQuery {

    private final World bound;

    LevelEntityQueryLegacy(World bound) {
        this.bound = bound;
    }

    /** The level it answers for; null for none. */
    World world() {
        return bound;
    }

    /** Whether {@code e} is the player at this client's keyboard. */
    boolean isLocal(Entity e) {
        return false;
    }

    @Override
    public int localPlayer() {
        return -1;
    }

    @Override
    public int cameraEntity() {
        return -1;
    }

    Entity entity(int id) {
        World world = world();
        return world == null || id < 0 ? null : world.getEntityByID(id);
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        Entity e = entity(id);
        if (e == null) return false;
        out[X] = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTick;
        out[Y] = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick;
        out[Z] = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTick;
        out[VELOCITY_X] = e.motionX;
        out[VELOCITY_Y] = e.motionY;
        out[VELOCITY_Z] = e.motionZ;
        AxisAlignedBB box = e.getEntityBoundingBox();
        out[WIDTH] = box.maxX - box.minX;
        out[HEIGHT] = box.maxY - box.minY;
        out[EYE_HEIGHT] = e.getEyeHeight();
        out[YAW] = angle(e.prevRotationYaw, e.rotationYaw, partialTick);
        out[PITCH] = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * partialTick;
        if (e instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) e;
            out[BODY_YAW] = angle(living.prevRenderYawOffset, living.renderYawOffset, partialTick);
            out[HEAD_YAW] = angle(living.prevRotationYawHead, living.rotationYawHead, partialTick);
            out[LIMB_SWING] = living.limbSwing - living.limbSwingAmount * (1f - partialTick);
            out[LIMB_SWING_AMOUNT] = living.prevLimbSwingAmount + (living.limbSwingAmount - living.prevLimbSwingAmount) * partialTick;
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
        if (e.isEntityAlive()) f |= ALIVE;
        if (e instanceof EntityLivingBase) {
            f |= LIVING;
            //? if >=1.9 {
            if (((EntityLivingBase) e).isElytraFlying()) f |= GLIDING;
            //?}
        }
        if (e instanceof EntityPlayer) f |= PLAYER;
        if (isLocal(e)) f |= LOCAL_PLAYER;
        if (e.onGround) f |= ON_GROUND;
        if (e.isSneaking()) f |= SNEAKING;
        if (e.isSprinting()) f |= SPRINTING;
        if (e.isInWater()) f |= IN_WATER;
        if (e.isBurning()) f |= ON_FIRE;
        if (e.isInvisible()) f |= INVISIBLE;
        return f;
    }

    @Override
    public int kind(int id) {
        Entity e = entity(id);
        if (e instanceof EntityPlayer) return KIND_PLAYER;
        if (e instanceof IMob) return KIND_MONSTER;
        if (e instanceof EntityAnimal) return KIND_ANIMAL;
        if (e instanceof EntityItem) return KIND_ITEM;
        if (e instanceof IProjectile) return KIND_PROJECTILE;
        if (e instanceof EntityMinecart || e instanceof EntityBoat) return KIND_VEHICLE;
        return KIND_OTHER;
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        World world = world();
        if (world == null) return;
        List<Entity> found = world.getEntitiesWithinAABBExcludingEntity(null,
                new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
        for (int i = 0; i < found.size(); i++) visitor.accept(found.get(i).getEntityId());
    }
}
