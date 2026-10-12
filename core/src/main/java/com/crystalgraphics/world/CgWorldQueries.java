package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;

/**
 * Where the host's world is solid under or over a point, asked in world coordinates: the ground beneath it and the
 * ceiling above it. Composed once from the per-block answers of {@link CgWorldQuery}, so every host gets the same scans;
 * NaN wherever there is no level or the chunk is not loaded. What a ray, a cast or a volume meets is
 * {@code CgCollisionWorld}'s.
 *
 * <pre>{@code
 * double floor = CgWorldQueries.groundBelow(x, y, z, 64);     // NaN: nothing solid within 64 blocks down
 * if (!Double.isNaN(floor)) landAt(floor);
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are absolute world coordinates in doubles, never camera-relative.</li>
 *   <li>Render thread only, like the slot it reads; the overloads given a {@link CgWorldQuery} run on the thread owning
 *       its level, a server's included ({@code CgLevelQueries}).</li>
 *   <li>A block counts by its collision span in y ({@code collisionTop}, {@code collisionBottom}), so a slab or a layer
 *       of snow is stood on at its own height; across x and z every solid block fills its cell.</li>
 * </ul>
 */
public final class CgWorldQueries {

    private CgWorldQueries() {
    }

    /**
     * The world y of the first surface at or under {@code (x, y, z)}, looking at most {@code maxDepth} blocks down: the
     * top of the highest solid span not above {@code y}. A point inside a block's span is under that block's top, which
     * is answered. NaN when nothing is found, the column is not loaded or there is no level.
     */
    public static double groundBelow(double x, double y, double z, int maxDepth) {
        return groundBelow(CgPlatform.get(CgWorldQuery.SERVICE), x, y, z, maxDepth);
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
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
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
        return ceilingAbove(CgPlatform.get(CgWorldQuery.SERVICE), x, y, z, maxHeight);
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
