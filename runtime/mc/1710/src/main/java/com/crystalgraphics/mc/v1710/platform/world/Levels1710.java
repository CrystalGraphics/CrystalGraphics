package com.crystalgraphics.mc.v1710.platform.world;

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
 * {@link CgLevels} over any level on Minecraft 1.7.10: one {@link LevelWorldQuery1710} and one
 * {@link LevelEntityQuery1710} per level, made at its first question and dropped with it, and the client's level
 * answered by the client's own queries once {@link #client} gave them. Server-safe; provided by {@code Network1710} on
 * both sides.
 */
public final class Levels1710 implements CgLevels {

    public static final Levels1710 INSTANCE = new Levels1710();

    private final Map<World, LevelWorldQuery1710> worlds = new WeakHashMap<World, LevelWorldQuery1710>();
    private final Map<World, LevelEntityQuery1710> entities = new WeakHashMap<World, LevelEntityQuery1710>();
    private volatile CgWorldQuery clientWorld = CgWorldQuery.NONE;
    private volatile CgEntityQuery clientEntities = CgEntityQuery.NONE;
    private volatile Supplier<? extends World> clientLevel = () -> null;

    private Levels1710() {
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
            return worlds.computeIfAbsent(world, LevelWorldQuery1710::new);
        }
    }

    @Override
    public CgEntityQuery entities(Object handle) {
        World world = worldOf(handle);
        if (world == null) return CgEntityQuery.NONE;
        if (world == clientLevel.get()) return clientEntities;
        synchronized (this) {
            return entities.computeIfAbsent(world, LevelEntityQuery1710::new);
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
        return handle instanceof World ? CgLevels.dimensionId(((World) handle).provider.dimensionId) : null;
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
        return level instanceof WorldServer && ((WorldServer) level).theChunkProviderServer.chunkExists(chunkX, chunkZ);
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
        return handle instanceof Entity ? ((Entity) handle).worldObj : null;
    }
}
