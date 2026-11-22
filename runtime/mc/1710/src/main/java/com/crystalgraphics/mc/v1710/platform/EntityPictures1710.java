package com.crystalgraphics.mc.v1710.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgEntityPictures} on 1.7.10, client: the inventory's {@code func_147046_a}, which turns a model only toward a
 * pointer, so a pose is the pointer offset that gives it, as far as a pointer turns one (NR4 decision 2).
 */
public final class EntityPictures1710 implements CgEntityPictures.Host {

    /** The furthest a pointer turns a model, in vanilla's radians-times-twenty: about 60 degrees of body. */
    private static final double MAX_TURN = 1.4;

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        if (world == null) return null;
        switch (ref.kind()) {
            case PLAYER:
                return mc.thePlayer;
            case LIVE:
                if (previous instanceof Entity && ((Entity) previous).isEntityAlive()
                        && ((Entity) previous).getUniqueID().equals(ref.uuid())) {
                    return previous;
                }
                for (Object e : world.loadedEntityList) {
                    if (((Entity) e).getUniqueID().equals(ref.uuid())) return e instanceof EntityLivingBase ? e : null;
                }
                return null;
            case PROFILE:
                if (previous != null) return previous;
                return new EntityOtherPlayerMP(world, profile(ref.profile()));
            default:
                if (previous != null) return previous;
                NBTTagCompound tag = CgNbt.toHost(ref.data().toNbt());
                // Before 1.11 an entity's id is its name, unnamespaced
                tag.setString("id", tag.getString("id").replaceFirst("^minecraft:", ""));
                // Made, never spawned: a picture's entity never joins the level
                Entity e = EntityList.createEntityFromNBT(tag, world);
                return e instanceof EntityLivingBase ? e : null;
        }
    }

    private static GameProfile profile(String nameOrUuid) {
        try {
            return new GameProfile(UUID.fromString(nameOrUuid), nameOrUuid.substring(0, 8));
        } catch (IllegalArgumentException notUuid) {
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nameOrUuid).getBytes(StandardCharsets.UTF_8));
            return new GameProfile(id, nameOrUuid);
        }
    }

    @Override
    public float height(Object entity) {
        return ((Entity) entity).height;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h) {
        EntityLivingBase e = (EntityLivingBase) entity;
        double turn = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.bodyYaw() / 20.0));
        double lift = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.tilt() / 20.0));
        // Depth on, as in the inventory screen
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        // White, as the inventory screen leaves it: a tooltip's text colour would tint the model
        GL11.glColor4f(1, 1, 1, 1);
        GuiInventory.func_147046_a(Math.round(x + w / 2), Math.round(y + h / 2 + e.height * scale / 2),
                Math.round(scale), (float) (40 * Math.tan(turn)), (float) (40 * Math.tan(lift)), e);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
}
