package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * {@link CgLevels} over any level on Forge 1.8.9 to 1.12.2: one {@link LevelWorldQueryLegacy} and one
 * {@link LevelEntityQueryLegacy} per level, made at its first question and dropped with it, and the client's level
 * answered by the client's own queries once {@link #client} gave them. Server-safe; provided by {@code NetworkLegacy}
 * on both sides.
 */
public final class LevelsLegacy implements CgLevels {

    public static final LevelsLegacy INSTANCE = new LevelsLegacy();

    private final Map<World, LevelWorldQueryLegacy> worlds = new WeakHashMap<>();
    private final Map<World, LevelEntityQueryLegacy> entities = new WeakHashMap<>();
    private volatile CgWorldQuery clientWorld = CgWorldQuery.NONE;
    private volatile CgEntityQuery clientEntities = CgEntityQuery.NONE;
    private volatile Supplier<? extends World> clientLevel = () -> null;

    private LevelsLegacy() {
    }

    /** The client's half, from client code once: its queries, which follow the level it is in, and that level. */
    public void client(CgWorldQuery world, CgEntityQuery entities, Supplier<? extends World> level) {
        clientWorld = world;
        clientEntities = entities;
        clientLevel = level;
    }

    @Override
    public CgWorldQuery world(Object handle) {
        World world = worldOf(handle);
        if (world == null) return CgWorldQuery.NONE;
        if (world == clientLevel.get()) return clientWorld;
        synchronized (this) {
            return worlds.computeIfAbsent(world, LevelWorldQueryLegacy::new);
        }
    }

    @Override
    public CgEntityQuery entities(Object handle) {
        World world = worldOf(handle);
        if (world == null) return CgEntityQuery.NONE;
        if (world == clientLevel.get()) return clientEntities;
        synchronized (this) {
            return entities.computeIfAbsent(world, LevelEntityQueryLegacy::new);
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
        if (handle instanceof Entity) return CgLevels.dimensionId(((Entity) handle).dimension);
        if (!(handle instanceof World)) return null;
        //? if <1.9 {
        /*return CgLevels.dimensionId(((World) handle).provider.getDimensionId());
        *///?} else {
        return CgLevels.dimensionId(((World) handle).provider.getDimension());
        //?}
    }

    @Override
    public Object level(Object handle, String dimension) {
        WorldServer[] worlds = DimensionManager.getWorlds();
        if (worlds == null) return null;
        for (WorldServer world : worlds) {
            if (dimension.equals(dimension(world))) return world;
        }
        return null;
    }

    @Override
    public boolean chunkLoaded(Object level, int chunkX, int chunkZ) {
        if (!(level instanceof WorldServer)) return false;
        //? if <1.9 {
        /*return ((WorldServer) level).theChunkProviderServer.chunkExists(chunkX, chunkZ);
        *///?} else {
        return ((WorldServer) level).getChunkProvider().chunkExists(chunkX, chunkZ);
        //?}
    }

    @Override
    public Path saveDirectory(Object handle) {
        File root = DimensionManager.getCurrentSaveRootDirectory();
        return root == null ? null : root.toPath().toAbsolutePath().normalize();
    }

    @Override
    public int entityId(Object entity) {
        return entity instanceof Entity ? ((Entity) entity).getEntityId() : -1;
    }

    @Override
    public boolean position(Object entity, double[] out) {
        if (!(entity instanceof Entity)) return false;
        Entity e = (Entity) entity;
        out[0] = e.posX;
        out[1] = e.posY;
        out[2] = e.posZ;
        return true;
    }

    @Override
    public boolean alive(Object entity) {
        return entity instanceof Entity && ((Entity) entity).isEntityAlive();
    }

    static World worldOf(Object handle) {
        if (handle instanceof World) return (World) handle;
        if (!(handle instanceof Entity)) return null;
        //? if <1.9 {
        /*return ((Entity) handle).worldObj;
        *///?} else {
        return ((Entity) handle).world;
        //?}
    }
}
