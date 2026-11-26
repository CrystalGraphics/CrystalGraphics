package com.crystalgraphics.mc.v1710.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.entity.CgHostEntities;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/**
 * {@link CgHostEntities} on 1.7.10, client: a ref's entity made from its data or profile, never spawned, or found live
 * in the client's world.
 */
public final class Entities1710 implements CgHostEntities.Host {

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
                // Before 1.11 an entity's id is its name, unnamespaced, and capitalised: sheep is Sheep
                tag.setString("id", gameName(tag.getString("id").replaceFirst("^minecraft:", "")));
                // Made, never spawned: a ref's entity never joins the level
                Entity e = EntityList.createEntityFromNBT(tag, world);
                return e instanceof EntityLivingBase ? e : null;
        }
    }

    // The registered name a modern id stands for, its case aside (1.11 lowered them all); the id as given where none matches
    private static String gameName(String id) {
        if (EntityList.stringToClassMapping.containsKey(id)) return id;
        for (Object name : EntityList.stringToClassMapping.keySet()) {
            if (((String) name).equalsIgnoreCase(id)) return (String) name;
        }
        return id;
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
        return ((Entity) entity).getCommandSenderName();
    }

    @Override
    public UUID uuid(Object entity) {
        return ((Entity) entity).getUniqueID();
    }
}
