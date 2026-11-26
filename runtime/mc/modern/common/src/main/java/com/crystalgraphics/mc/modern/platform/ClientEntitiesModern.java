package com.crystalgraphics.mc.modern.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgClientEntities;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
//? if >=1.14 {
import net.minecraft.client.player.RemotePlayer;
//?}
//? if >=1.21.3 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.world.entity.EntityProcessor;
*///?}
//? if >=26.2 {
/*import net.minecraft.world.entity.EntitySpawnRequest;
*///?}

/**
 * {@link CgClientEntities} on the modern tree, client: an entity made from a ref's data or profile in the client's level,
 * never added to it. From 1.14; none on 1.13.2.
 */
public final class ClientEntitiesModern implements CgClientEntities.Host {

    /**
     * Ids for the entities made here, counting down from -1 where the game's count up: from 26.2 one made outside a
     * level has none, and a renderer reading it (a sheep's item models) throws.
     */
    private static final AtomicInteger IDS = new AtomicInteger(-1);

    @Override
    public String absent() {
        //? if <1.14 {
        /*return "no entities from data before 1.14";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public Object make(CgEntityRef ref) {
        //? if <1.14 {
        /*return null;
        *///?} else {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        switch (ref.kind()) {
            case PROFILE: return identified(player(mc, ref.profile()));
            case DATA: return identified(fromData(mc, CgNbt.toHost(ref.data().toNbt())));
            default: return null;
        }
        //?}
    }

    @Nullable
    private static Object identified(@Nullable Object made) {
        if (made instanceof Entity) ((Entity) made).setId(IDS.getAndDecrement());
        return made;
    }

    // A mannequin of a profile: the default skin until the skin is fetched (NR4 open item)
    @Nullable
    private static Object player(Minecraft mc, String nameOrUuid) {
        UUID id;
        String name;
        try {
            id = UUID.fromString(nameOrUuid);
            name = nameOrUuid.substring(0, 8);
        } catch (IllegalArgumentException notUuid) {
            id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nameOrUuid).getBytes(StandardCharsets.UTF_8));
            name = nameOrUuid;
        }
        //? if <1.14 {
        /*return null;
        *///?} elif >=1.19 <1.19.3 {
        /*return new RemotePlayer(mc.level, new GameProfile(id, name), null);
        *///?} else {
        return new RemotePlayer(mc.level, new GameProfile(id, name));
        //?}
    }

    @Nullable
    private static Object fromData(Minecraft mc, CompoundTag tag) {
        //? if <1.14 {
        /*Entity e = null;
        *///?} elif <1.21.3 {
        Entity e = EntityType.loadEntityRecursive(tag, mc.level, x -> x);
        //?} elif <1.21.11 {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, EntitySpawnReason.LOAD, x -> x);
        *///?} elif <26.2 {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
        *///?} else {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                EntityProcessor.NOP);
        *///?}
        return e instanceof LivingEntity ? e : null;
    }
}
