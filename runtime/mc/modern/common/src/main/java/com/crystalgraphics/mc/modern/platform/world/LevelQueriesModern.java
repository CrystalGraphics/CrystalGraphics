package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link CgLevelQueries} over any level, 1.13.2 to 26.3: one {@link LevelWorldQueryModern} and one
 * {@link LevelEntityQueryModern} per level, made at its first question and dropped with it. Server-safe; provided by
 * {@code NetworkModern} on both sides.
 */
public final class LevelQueriesModern implements CgLevelQueries {

    private final Map<Level, LevelWorldQueryModern> worlds = new WeakHashMap<>();
    private final Map<Level, LevelEntityQueryModern> entities = new WeakHashMap<>();

    @Override
    public synchronized CgWorldQuery world(Object handle) {
        Level level = levelOf(handle);
        return level == null ? CgWorldQuery.NONE : worlds.computeIfAbsent(level, LevelWorldQueryModern::new);
    }

    @Override
    public synchronized CgEntityQuery entities(Object handle) {
        Level level = levelOf(handle);
        return level == null ? CgEntityQuery.NONE : entities.computeIfAbsent(level, LevelEntityQueryModern::new);
    }

    private static Level levelOf(Object handle) {
        if (handle instanceof Level) return (Level) handle;
        if (!(handle instanceof Entity)) return null;
        //? if >=1.20 {
        return ((Entity) handle).level();
        //?} else {
        /*return ((Entity) handle).level;
        *///?}
    }
}
