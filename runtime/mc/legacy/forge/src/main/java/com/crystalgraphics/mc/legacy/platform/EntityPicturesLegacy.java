package com.crystalgraphics.mc.legacy.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgEntityPictures} on legacy Forge, client: the inventory's {@code drawEntityOnScreen}, which turns a model only
 * toward a pointer, so a pose is the pointer offset that gives it, as far as a pointer turns one (NR4 decision 2).
 */
public final class EntityPicturesLegacy implements CgEntityPictures.Host {

    /** The furthest a pointer turns a model, in vanilla's radians-times-twenty: about 60 degrees of body. */
    private static final double MAX_TURN = 1.4;

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return null;
        switch (ref.kind()) {
            case PLAYER:
                return ClientLegacy.player();
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
                //? if <1.11 {
                /*tag.setString("id", tag.getString("id").replaceFirst("^minecraft:", ""));
                *///?}
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
    public String name(Object entity) {
        return ((Entity) entity).getDisplayName().getUnformattedText();
    }

    @Override
    public float height(Object entity) {
        return ((Entity) entity).height;
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h,
                     float centreY) {
        EntityLivingBase e = (EntityLivingBase) entity;
        double turn = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.bodyYaw() / 20.0));
        double lift = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.tilt() / 20.0));
        // Depth on, as in the inventory screen: with it off a model's far side shows
        GlStateManager.enableDepth();
        // White, as the inventory screen leaves it: a tooltip's text colour would tint the model
        GlStateManager.color(1, 1, 1, 1);
        GuiInventory.drawEntityOnScreen(Math.round(x + w / 2), Math.round(y + centreY + e.height * scale / 2),
                Math.round(scale), (float) (40 * Math.tan(turn)), (float) (40 * Math.tan(lift)), e);
        GlStateManager.disableDepth();
    }

    // GuiInventory's: the player's feet at (51, 75) of the panel, 3 units over its 70-unit box's bottom, the pointer
    // measured from 50 units over them
    @Override
    public float inventoryCentreY(float h, float entityHeight, float scale) {
        return h - 3 - entityHeight * scale / 2;
    }

    @Override
    public float inventoryLookY(float h) {
        return h - 53;
    }
}
