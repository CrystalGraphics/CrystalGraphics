package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
//? if <1.14 {
/*import net.minecraft.core.BlockPos;
*///?}
//? if >=1.16 {
import net.minecraft.world.level.storage.LevelResource;
//?} else {
/*import net.minecraft.world.level.dimension.DimensionType;
*///?}

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * {@link CgLevels} over any level, 1.13.2 to 26.3: one {@link LevelWorldQueryModern} and one
 * {@link LevelEntityQueryModern} per level, made at its first question and dropped with it, and the client's level
 * answered by the client's own queries once {@link #client} gave them. Server-safe; provided by {@code NetworkModern}
 * on both sides.
 */
public final class LevelsModern implements CgLevels {

    public static final LevelsModern INSTANCE = new LevelsModern();

    private final Map<Level, LevelWorldQueryModern> worlds = new WeakHashMap<>();
    private final Map<Level, LevelEntityQueryModern> entities = new WeakHashMap<>();
    /** Each level's id, asked per player per tick: the id's toString builds a new string. */
    private final Map<Level, String> dimensions = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile CgWorldQuery clientWorld = CgWorldQuery.NONE;
    private volatile CgEntityQuery clientEntities = CgEntityQuery.NONE;
    private volatile Supplier<Level> clientLevel = () -> null;

    private LevelsModern() {
    }

    /**
     * The client's half, from client code once: its queries, which follow the level it is in, and that level. Until
     * then the client is answered as no level.
     */
    public void client(CgWorldQuery world, CgEntityQuery entities, Supplier<Level> level) {
        clientWorld = world;
        clientEntities = entities;
        clientLevel = level;
    }

    @Override
    public CgWorldQuery world(Object handle) {
        Level level = levelOf(handle);
        if (level == null) return CgWorldQuery.NONE;
        if (level == clientLevel.get()) return clientWorld;
        synchronized (this) {
            return worlds.computeIfAbsent(level, LevelWorldQueryModern::new);
        }
    }

    @Override
    public CgEntityQuery entities(Object handle) {
        Level level = levelOf(handle);
        if (level == null) return CgEntityQuery.NONE;
        if (level == clientLevel.get()) return clientEntities;
        synchronized (this) {
            return entities.computeIfAbsent(level, LevelEntityQueryModern::new);
        }
    }

    @Override
    public CgWorldQuery clientWorld() {
        return clientWorld;
    }

    @Override
    public CgEntityQuery clientEntities() {
        return clientEntities;
    }

    @Override
    public Object clientLevel() {
        return clientLevel.get();
    }

    @Override
    public String dimension(Object handle) {
        Level level = levelOf(handle);
        if (level == null) return null;
        String id = dimensions.get(level);
        if (id == null) dimensions.put(level, id = dimensionOf(level));
        return id;
    }

    private static String dimensionOf(Level level) {
        //? if >=1.21.11 {
        /*return level.dimension().identifier().toString();
        *///?} elif >=1.16 {
        return level.dimension().location().toString();
        //?} else {
        /*return DimensionType.getName(level.getDimension().getType()).toString();
        *///?}
    }

    @Override
    public Object level(Object handle, String dimension) {
        MinecraftServer server = serverOf(handle);
        if (server == null) return null;
        // Object: 1.13.2's levels are an unmapped type.
        for (Object level : server.getAllLevels()) {
            if (dimension.equals(dimension(level))) return level;
        }
        return null;
    }

    @Override
    public boolean chunkLoaded(Object level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel)) return false;
        //? if >=1.14 {
        return ((ServerLevel) level).getChunkSource().hasChunk(chunkX, chunkZ);
        //?} else {
        /*// 1.13.2's getChunkSource answers an unmapped type.
        return ((ServerLevel) level).isLoaded(new BlockPos(chunkX << 4, 64, chunkZ << 4));
        *///?}
    }

    @Override
    public Path saveDirectory(Object handle) {
        MinecraftServer server = serverOf(handle);
        if (server == null) return null;
        //? if >=1.16 {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        //?} else {
        /*return server.getStorageSource().getFile(server.getLevelIdName(), "").toPath().toAbsolutePath().normalize();
        *///?}
    }

    @Override
    public int entityId(Object entity) {
        return entity instanceof Entity ? ((Entity) entity).getId() : -1;
    }

    @Override
    public boolean position(Object entity, double[] out) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        //? if >=1.15 {
        out[0] = e.getX();
        out[1] = e.getY();
        out[2] = e.getZ();
        //?} else {
        /*out[0] = e.x;
        out[1] = e.y;
        out[2] = e.z;
        *///?}
        return true;
    }

    @Override
    public boolean alive(Object entity) {
        return entity instanceof Entity && ((Entity) entity).isAlive();
    }

    static MinecraftServer serverOf(Object handle) {
        if (handle instanceof MinecraftServer) return (MinecraftServer) handle;
        Level level = levelOf(handle);
        return level == null ? null : level.getServer();
    }

    static Level levelOf(Object handle) {
        if (handle instanceof Level) return (Level) handle;
        if (!(handle instanceof Entity)) return null;
        //? if >=1.20 {
        return ((Entity) handle).level();
        //?} else {
        /*return ((Entity) handle).level;
        *///?}
    }
}
