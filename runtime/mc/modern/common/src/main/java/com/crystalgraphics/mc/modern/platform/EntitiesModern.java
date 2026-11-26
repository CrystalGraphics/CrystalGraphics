package com.crystalgraphics.mc.modern.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.entity.CgHostEntities;
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
 * {@link CgHostEntities} on the modern tree, client: a ref's entity made from its data or profile, never added to the
 * level, or found live in the client's level. Entities from data from 1.14; none on 1.13.2.
 */
public final class EntitiesModern implements CgHostEntities.Host {

    /**
     * Ids for entities a ref makes, counting down from -1 where the game's count up: from 26.2 one made outside a level
     * has none, and a renderer reading it (a sheep's item models) throws.
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
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        //? if <1.14 {
        /*return null;
        *///?} else {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        switch (ref.kind()) {
            case PLAYER:
                return mc.player;
            case LIVE:
                if (previous instanceof Entity && ((Entity) previous).isAlive() && ((Entity) previous).getUUID().equals(ref.uuid())) {
                    return previous;
                }
                // No list of the level's entities to look in before 1.15: a live entity is none there
                //? if >=1.15 {
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.getUUID().equals(ref.uuid())) return e instanceof LivingEntity ? e : null;
                }
                //?}
                return null;
            case PROFILE:
                if (previous != null) return previous;
                return identified(player(mc, ref.profile()));
            default:
                if (previous != null) return previous;
                return identified(fromData(mc, CgNbt.toHost(ref.data().toNbt())));
        }
        //?}
    }

    @Override
    public boolean isEntity(Object o) {
        return o instanceof Entity;
    }

    @Override
    public float height(Object entity) {
        //? if <1.14 {
        /*return 1;
        *///?} else {
        return ((Entity) entity).getBbHeight();
        //?}
    }

    @Override
    public String name(Object entity) {
        return ((Entity) entity).getDisplayName().getString();
    }

    @Override
    public UUID uuid(Object entity) {
        return ((Entity) entity).getUUID();
    }

    @Nullable
    private static Object identified(@Nullable Object made) {
        if (made instanceof Entity) ((Entity) made).setId(IDS.getAndDecrement());
        return made;
    }

    // A mannequin of a profile, made client-side: the default skin until the skin is fetched (NR4 open item)
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

    // Made, never added to the level: a ref's entity never joins it
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
