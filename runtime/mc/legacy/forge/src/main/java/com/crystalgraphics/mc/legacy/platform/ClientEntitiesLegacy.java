package com.crystalgraphics.mc.legacy.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.crystalgraphics.mc.entity.CgClientEntities;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.nbt.NBTTagCompound;

/** {@link CgClientEntities} on legacy Forge, client: an entity made from a ref's data or profile, never spawned. */
public final class ClientEntitiesLegacy implements CgClientEntities.Host {

    @Override
    public String absent() {
        return null;
    }

    @Override
    public Object make(CgEntityRef ref) {
        WorldClient world = ClientLegacy.world();
        if (world == null) return null;
        switch (ref.kind()) {
            case PROFILE:
                return new EntityOtherPlayerMP(world, profile(ref.profile()));
            case DATA:
                NBTTagCompound tag = CgNbt.toHost(ref.data().toNbt());
                //? if <1.11 {
                /*tag.setString("id", tag.getString("id").replaceFirst("^minecraft:", ""));
                *///?}
                Entity e = EntityList.createEntityFromNBT(tag, world);
                return e instanceof EntityLivingBase ? e : null;
            default:
                return null;
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
}
