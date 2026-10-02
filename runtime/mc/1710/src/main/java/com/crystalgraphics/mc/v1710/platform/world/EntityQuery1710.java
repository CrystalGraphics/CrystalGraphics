package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * {@link CgEntityQuery} over the client's level on Minecraft 1.7.10. Client only: provided by
 * {@code PlatformService1710.onPreInit}. Render thread; allocates nothing.
 *
 * <ul>
 *   <li>No entity swims or glides on this version.</li>
 * </ul>
 */
public final class EntityQuery1710 implements CgEntityQuery {

    @Override
    public int localPlayer() {
        Entity player = Minecraft.getMinecraft().thePlayer;
        return player == null ? -1 : player.getEntityId();
    }

    @Override
    public int cameraEntity() {
        Entity camera = Minecraft.getMinecraft().renderViewEntity;
        return camera == null ? -1 : camera.getEntityId();
    }

    private static Entity entity(int id) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
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
        out[WIDTH] = e.boundingBox.maxX - e.boundingBox.minX;
        out[HEIGHT] = e.boundingBox.maxY - e.boundingBox.minY;
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
        if (e instanceof EntityLivingBase) f |= LIVING;
        if (e instanceof EntityPlayer) f |= PLAYER;
        if (e == Minecraft.getMinecraft().thePlayer) f |= LOCAL_PLAYER;
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
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) return;
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            AxisAlignedBB b = e.boundingBox;
            if (b.maxX > minX && b.minX < maxX && b.maxY > minY && b.minY < maxY && b.maxZ > minZ && b.minZ < maxZ) {
                visitor.accept(e.getEntityId());
            }
        }
    }
}
