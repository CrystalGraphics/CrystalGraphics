package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link CgLevelQueries} over any level on Minecraft 1.7.10: one {@link LevelWorldQuery1710} and one
 * {@link LevelEntityQuery1710} per level, made at its first question and dropped with it. Server-safe; provided by
 * {@code Network1710} on both sides.
 */
public final class LevelQueries1710 implements CgLevelQueries {

    private final Map<World, LevelWorldQuery1710> worlds = new WeakHashMap<World, LevelWorldQuery1710>();
    private final Map<World, LevelEntityQuery1710> entities = new WeakHashMap<World, LevelEntityQuery1710>();

    @Override
    public synchronized CgWorldQuery world(Object handle) {
        World world = worldOf(handle);
        return world == null ? CgWorldQuery.NONE : worlds.computeIfAbsent(world, LevelWorldQuery1710::new);
    }

    @Override
    public synchronized CgEntityQuery entities(Object handle) {
        World world = worldOf(handle);
        return world == null ? CgEntityQuery.NONE : entities.computeIfAbsent(world, LevelEntityQuery1710::new);
    }

    private static World worldOf(Object handle) {
        if (handle instanceof World) return (World) handle;
        return handle instanceof Entity ? ((Entity) handle).worldObj : null;
    }
}
