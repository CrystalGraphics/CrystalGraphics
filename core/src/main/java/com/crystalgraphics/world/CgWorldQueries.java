package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;

/**
 * Where the host's world is solid, asked in world coordinates: the ground under a point, the ceiling over it, and where
 * a ray first meets a block. Composed once from the per-block answers of {@link CgWorldQuery}, so every host gets the
 * same scans; NaN or false wherever there is no level or the chunk is not loaded.
 *
 * <pre>{@code
 * double floor = CgWorldQueries.groundBelow(x, y, z, 64);     // NaN: nothing solid within 64 blocks down
 * if (!Double.isNaN(floor)) landAt(floor);
 *
 * CgWorldQueries.Hit hit = new CgWorldQueries.Hit();          // once, and reused
 * if (CgWorldQueries.raycast(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, 100, hit)) {
 *     impactAt(hit.x, hit.y, hit.z, hit.normalX, hit.normalY, hit.normalZ);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are absolute world coordinates in doubles, never camera-relative.</li>
 *   <li>Render thread only, like the slot it reads.</li>
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

    /**
     * Where the ray from {@code (x, y, z)} along {@code (dx, dy, dz)} (any length but zero) first enters a solid block,
     * within {@code maxDistance} blocks, into {@code hit}. False, with {@code hit} untouched, when it meets none, leaves
     * the loaded world or there is no level. A ray starting inside a block hits it at once, its normal against the ray.
     */
    public static boolean raycast(double x, double y, double z, double dx, double dy, double dz, double maxDistance, Hit hit) {
        return raycast(CgPlatform.get(CgWorldQuery.SERVICE), x, y, z, dx, dy, dz, maxDistance, hit);
    }

    public static boolean raycast(CgWorldQuery world, double x, double y, double z, double dx, double dy, double dz,
                                  double maxDistance, Hit hit) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0.0 || world.levelEpoch() == 0) return false;
        dx /= length;
        dy /= length;
        dz /= length;
        // Amanatides and Woo: step cell to cell, always across the nearest boundary.
        int bx = floor(x), by = floor(y), bz = floor(z);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double nextX = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? bx + 1 - x : x - bx) * deltaX);
        double nextY = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? by + 1 - y : y - by) * deltaY);
        double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? bz + 1 - z : z - bz) * deltaZ);
        double entered = 0.0;
        int face = -1;     // the axis crossed into this cell: 0 x, 1 y, 2 z; -1 for the starting cell
        while (entered <= maxDistance) {
            if (by < world.minY() && dy <= 0 || by >= world.maxY() && dy >= 0) return false;
            if (!world.loaded(bx, bz)) return false;
            float top = world.collisionTop(bx, by, bz);
            if (!Float.isNaN(top)) {
                float bottom = world.collisionBottom(bx, by, bz);
                double exit = Math.min(nextX, Math.min(nextY, nextZ));
                // The span may be a slab: where the ray is inside [bottom, top] within this cell.
                double t0 = entered, t1 = exit;
                int axis = face;
                if (dy != 0) {
                    double ta = (by + bottom - y) / dy, tb = (by + top - y) / dy;
                    double near = Math.min(ta, tb), far = Math.max(ta, tb);
                    if (near > t0) {
                        t0 = near;
                        axis = 1;
                    }
                    t1 = Math.min(t1, far);
                } else if (y < by + bottom || y > by + top) {
                    t1 = -1.0;
                }
                if (t0 <= t1 && t0 <= maxDistance) {
                    hit.set(x + dx * t0, y + dy * t0, z + dz * t0, bx, by, bz, t0);
                    hit.normalX = axis == 0 ? -stepX : 0;
                    hit.normalY = axis == 1 ? (dy > 0 ? -1 : 1) : 0;
                    hit.normalZ = axis == 2 ? -stepZ : 0;
                    if (axis < 0) {
                        hit.normalX = (float) -dx;
                        hit.normalY = (float) -dy;
                        hit.normalZ = (float) -dz;
                    }
                    return true;
                }
            }
            if (nextX <= nextY && nextX <= nextZ) {
                entered = nextX;
                nextX += deltaX;
                bx += stepX;
                face = 0;
            } else if (nextY <= nextZ) {
                entered = nextY;
                nextY += deltaY;
                by += stepY;
                face = 1;
            } else {
                entered = nextZ;
                nextZ += deltaZ;
                bz += stepZ;
                face = 2;
            }
        }
        return false;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Where a ray met a block: filled by {@link #raycast}; keep one and reuse it. */
    public static final class Hit {
        /** The point where the ray entered the block's span, in world coordinates. */
        public double x, y, z;
        /** The block it entered. */
        public int blockX, blockY, blockZ;
        /** The face it entered through, a unit vector along an axis; against the ray when it started inside. */
        public float normalX, normalY, normalZ;
        /** How far along the ray, in blocks. */
        public double distance;

        void set(double x, double y, double z, int blockX, int blockY, int blockZ, double distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.distance = distance;
        }
    }
}
