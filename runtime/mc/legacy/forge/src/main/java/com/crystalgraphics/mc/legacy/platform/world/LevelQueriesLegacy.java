package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link CgLevelQueries} over any level on Forge 1.8.9 to 1.12.2: one {@link LevelWorldQueryLegacy} and one
 * {@link LevelEntityQueryLegacy} per level, made at its first question and dropped with it. Server-safe; provided by
 * {@code NetworkLegacy} on both sides.
 */
public final class LevelQueriesLegacy implements CgLevelQueries {

    private final Map<World, LevelWorldQueryLegacy> worlds = new WeakHashMap<>();
    private final Map<World, LevelEntityQueryLegacy> entities = new WeakHashMap<>();

    @Override
    public synchronized CgWorldQuery world(Object handle) {
        World world = worldOf(handle);
        return world == null ? CgWorldQuery.NONE : worlds.computeIfAbsent(world, LevelWorldQueryLegacy::new);
    }

    @Override
    public synchronized CgEntityQuery entities(Object handle) {
        World world = worldOf(handle);
        return world == null ? CgEntityQuery.NONE : entities.computeIfAbsent(world, LevelEntityQueryLegacy::new);
    }

    private static World worldOf(Object handle) {
        if (handle instanceof World) return (World) handle;
        if (!(handle instanceof Entity)) return null;
        //? if <1.9 {
        /*return ((Entity) handle).worldObj;
        *///?} else {
        return ((Entity) handle).world;
        //?}
    }
}
