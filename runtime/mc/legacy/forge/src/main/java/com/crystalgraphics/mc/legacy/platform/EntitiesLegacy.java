package com.crystalgraphics.mc.legacy.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.entity.CgHostEntities;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgHostEntities} on legacy Forge, client: a ref's entity made from its data or profile, never spawned, or found
 * live in the client's world.
 */
public final class EntitiesLegacy implements CgHostEntities.Host {

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
                // Made, never spawned: a ref's entity never joins the level
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
    public boolean isEntity(Object o) {
        return o instanceof Entity;
    }

    @Override
    public float height(Object entity) {
        return ((Entity) entity).height;
    }

    @Override
    public String name(Object entity) {
        return ((Entity) entity).getDisplayName().getUnformattedText();
    }

    @Override
    public UUID uuid(Object entity) {
        return ((Entity) entity).getUniqueID();
    }
}
