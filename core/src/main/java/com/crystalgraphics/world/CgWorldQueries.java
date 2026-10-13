package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;

import java.nio.file.Path;

/**
 * The host's levels and their blocks: the {@link CgWorldQuery} of any level, the client's or a server's, what a level is
 * (its dimension, its loaded chunks, where it saves), and where it is solid under or over a point. The only reader of
 * {@link CgLevels}; the scans are composed once from the per-block answers, so every host gets the same, NaN wherever
 * there is no level or the chunk is not loaded. What a ray, a cast or a volume meets is {@code CgCollisionWorld}'s.
 *
 * <pre>{@code
 * CgWorldQuery here = CgWorldQueries.client();                // the client's level, following it; NONE on a server
 * CgWorldQuery there = CgWorldQueries.of(caster);             // a level, or an entity in one: a server's too
 * double floor = CgWorldQueries.groundBelow(x, y, z, 64);     // the client's: NaN for nothing solid 64 blocks down
 * if (!Double.isNaN(floor)) landAt(floor);
 *
 * // a level's facts, server side
 * String dim = CgWorldQueries.dimension(player);              // "minecraft:the_nether"
 * Object nether = CgWorldQueries.level(server, dim);
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are absolute world coordinates in doubles, never camera-relative.</li>
 *   <li>A query is asked on its level's thread: {@link #client()} and the scans without one on the render thread,
 *       {@link #of} a server level's on the server thread. Hold a query while its level lives, not across levels.</li>
 *   <li>A block counts by its collision span in y ({@code collisionTop}, {@code collisionBottom}), so a slab or a layer
 *       of snow is stood on at its own height; across x and z every solid block fills its cell.</li>
 * </ul>
 */
public final class CgWorldQueries {

    private CgWorldQueries() {
    }

    /** The blocks of {@code level}: the loader's level, or an entity in it. {@code NONE} for anything else. */
    public static CgWorldQuery of(Object level) {
        return levels().world(level);
    }

    /** The blocks of the level the client is in, following it from level to level; {@code NONE} on a server. */
    public static CgWorldQuery client() {
        return levels().clientWorld();
    }

    /** The loader's level the client is in; null on a server or between levels. */
    public static Object clientLevel() {
        return levels().clientLevel();
    }

    /** The dimension a level, an entity or a player is in ({@code "minecraft:overworld"}); null for anything else. */
    public static String dimension(Object handle) {
        return levels().dimension(handle);
    }

    /** The server's level for a dimension id, {@code handle} being the server or anything on it; null for none. */
    public static Object level(Object handle, String dimension) {
        return levels().level(handle, dimension);
    }

    /** Whether chunk ({@code chunkX}, {@code chunkZ}) of a server level is loaded. Never loads it. */
    public static boolean chunkLoaded(Object level, int chunkX, int chunkZ) {
        return levels().chunkLoaded(level, chunkX, chunkZ);
    }

    /** Where the world a level, entity or player belongs to saves; null without a running server. */
    public static Path saveDirectory(Object handle) {
        return levels().saveDirectory(handle);
    }

    /** The slot, for {@link CgEntityQueries}: one reader of {@link CgLevels} in core. */
    static CgLevels levels() {
        return CgPlatform.get(CgLevels.SERVICE);
    }

    /**
     * The world y of the first surface at or under {@code (x, y, z)}, looking at most {@code maxDepth} blocks down: the
     * top of the highest solid span not above {@code y}. A point inside a block's span is under that block's top, which
     * is answered. NaN when nothing is found, the column is not loaded or there is no level.
     */
    public static double groundBelow(double x, double y, double z, int maxDepth) {
        return groundBelow(client(), x, y, z, maxDepth);
    }

    /**
     * As {@link #groundBelow(double, double, double, int)}, starting no higher than the column's surface (the host's
     * heightmap, {@link CgWorldQuery#HEIGHT_TOP}): from high in the air it finds the terrain however far down, and under
     * a roof it still starts at {@code y}, finding the floor, not the roof.
     *
     * <pre>{@code
     * double floor = CgWorldQueries.groundUnder(x, eyeY + 16, z, 48);   // 48 blocks under the surface or the point
     * }</pre>
     */
    public static double groundUnder(double x, double y, double z, int maxDepth) {
        CgWorldQuery world = client();
        int surface = world.surfaceY(floor(x), floor(z), CgWorldQuery.HEIGHT_TOP);
        return groundBelow(world, x, surface == Integer.MIN_VALUE ? y : Math.min(y, surface), z, maxDepth);
    }

    /** As {@link #groundBelow(double, double, double, int)}, against {@code world}: for a loop asking many times. */
    public static double groundBelow(CgWorldQuery world, double x, double y, double z, int maxDepth) {
        int bx = floor(x), by = floor(y), bz = floor(z);
        if (world.levelEpoch() == 0 || !world.loaded(bx, bz)) return Double.NaN;
        int lowest = Math.max(by - maxDepth, world.minY());
        for (int b = Math.min(by, world.maxY() - 1); b >= lowest; b--) {
            float top = world.collisionTop(bx, b, bz);
            if (Float.isNaN(top)) continue;
            // In this block's own cell, a span starting above the point is a ceiling, not ground.
            if (b == by && b + world.collisionBottom(bx, b, bz) > y) continue;
            return b + top;
        }
        return Double.NaN;
    }

    /**
     * The world y of the first surface at or over {@code (x, y, z)}, looking at most {@code maxHeight} blocks up: the
     * bottom of the lowest solid span not below {@code y}. NaN as {@link #groundBelow}.
     */
    public static double ceilingAbove(double x, double y, double z, int maxHeight) {
        return ceilingAbove(client(), x, y, z, maxHeight);
    }

    public static double ceilingAbove(CgWorldQuery world, double x, double y, double z, int maxHeight) {
        int bx = floor(x), by = floor(y), bz = floor(z);
        if (world.levelEpoch() == 0 || !world.loaded(bx, bz)) return Double.NaN;
        int highest = Math.min(by + maxHeight, world.maxY() - 1);
        for (int b = Math.max(by, world.minY()); b <= highest; b++) {
            float bottom = world.collisionBottom(bx, b, bz);
            if (Float.isNaN(bottom)) continue;
            if (b == by && b + world.collisionTop(bx, b, bz) < y) continue;
            return b + bottom;
        }
        return Double.NaN;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
