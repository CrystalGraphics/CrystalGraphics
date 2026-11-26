package com.crystalgraphics.mc.v1710.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.crystalgraphics.mc.entity.CgClientEntities;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/** {@link CgClientEntities} on 1.7.10, client: an entity made from a ref's data or profile, never spawned. */
public final class ClientEntities1710 implements CgClientEntities.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object make(CgEntityRef ref) {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world == null) return null;
        switch (ref.kind()) {
            case PROFILE:
                return new EntityOtherPlayerMP(world, profile(ref.profile()));
            case DATA:
                NBTTagCompound tag = CgNbt.toHost(ref.data().toNbt());
                // Before 1.11 an entity's id is its name, unnamespaced, and capitalised: sheep is Sheep
                tag.setString("id", gameName(tag.getString("id").replaceFirst("^minecraft:", "")));
                Entity e = EntityList.createEntityFromNBT(tag, world);
                return e instanceof EntityLivingBase ? e : null;
            default:
                return null;
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
}
