package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.stage.CgRenderStage;

import java.util.function.IntConsumer;

/**
 * Where the host's world is solid, asked in world coordinates: the ground under a point, the ceiling over it, and where
 * a ray first meets a block or an entity. Composed once from the per-block answers of {@link CgWorldQuery} and the
 * entity boxes of {@link CgEntityQuery}, so every host gets the same scans; NaN or false wherever there is no level or
 * the chunk is not loaded.
 *
 * <pre>{@code
 * double floor = CgWorldQueries.groundBelow(x, y, z, 64);     // NaN: nothing solid within 64 blocks down
 * if (!Double.isNaN(floor)) landAt(floor);
 *
 * CgWorldQueries.Hit hit = new CgWorldQueries.Hit();          // once, and reused
 * if (CgWorldQueries.raycast(eyeX, eyeY, eyeZ, dirX, dirY, dirZ, 100, hit)) {
 *     impactAt(hit.x, hit.y, hit.z, hit.normalX, hit.normalY, hit.normalZ);
 * }
 *
 * // a beam 0.6 thick, from a caster's hands: blocks and entities, the caster passed through
 * if (CgWorldQueries.raycastAll(handX, handY, handZ, dirX, dirY, dirZ, 100, 0.3, casterId, hit)) {
 *     if (hit.entity >= 0) struck(hit.entity);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Coordinates are absolute world coordinates in doubles, never camera-relative.</li>
 *   <li>Render thread only, like the slots it reads; the overloads given a {@link CgWorldQuery} and a
 *       {@link CgEntityQuery} run on the thread owning their level, a server's included ({@code CgLevelQueries}), and
 *       see entities where they are this tick.</li>
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

    /**
     * Where the ray from {@code (x, y, z)} along {@code (dx, dy, dz)} first meets an entity, within {@code maxDistance}
     * blocks, into {@code hit} ({@link Hit#entity} the entity): each entity's box as drawn this frame, grown by
     * {@code radius} on every side, so a thick beam meets what its edge touches. Passes through {@code ignore} (a
     * caster; -1 for none), the dead, dropped items and projectiles. False, with {@code hit} untouched, when it meets
     * none. A ray starting inside a box hits it at once, its normal against the ray.
     */
    public static boolean raycastEntities(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
                                          double radius, int ignore, Hit hit) {
        return raycastEntities(ENTITY_RAY, CgPlatform.get(CgEntityQuery.SERVICE),
                CgRenderStage.WORLD_OPAQUE.host().partialTick(), x, y, z, dx, dy, dz, maxDistance, radius, ignore, hit);
    }

    /**
     * As {@link #raycastEntities(double, double, double, double, double, double, double, double, int, Hit)}, against
     * {@code entities} at their positions this tick: on any thread owning their level, a server's included
     * ({@code CgLevelQueries}).
     */
    public static boolean raycastEntities(CgEntityQuery entities, double x, double y, double z, double dx, double dy,
                                          double dz, double maxDistance, double radius, int ignore, Hit hit) {
        return raycastEntities(THREAD_RAY.get(), entities, 1f, x, y, z, dx, dy, dz, maxDistance, radius, ignore, hit);
    }

    private static boolean raycastEntities(EntityRay ray, CgEntityQuery entities, float partialTick, double x, double y,
                                           double z, double dx, double dy, double dz, double maxDistance, double radius,
                                           int ignore, Hit hit) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0.0) return false;
        ray.begin(entities, partialTick, x, y, z, dx / length, dy / length, dz / length, radius, ignore, maxDistance);
        // A short box at a time: a host lists the entities of every chunk section a box covers.
        for (double from = 0.0; from < maxDistance; from += ENTITY_SEGMENT) {
            double to = Math.min(from + ENTITY_SEGMENT, maxDistance);
            double ax = x + ray.dx * from, ay = y + ray.dy * from, az = z + ray.dz * from;
            double bx = x + ray.dx * to, by = y + ray.dy * to, bz = z + ray.dz * to;
            entities.within(Math.min(ax, bx) - radius, Math.min(ay, by) - radius, Math.min(az, bz) - radius,
                    Math.max(ax, bx) + radius, Math.max(ay, by) + radius, Math.max(az, bz) + radius, ray);
            // A box is entered within the segment holding its entry point, so nothing in a later one is nearer.
            if (ray.best <= to) {
                ray.fill(hit);
                return true;
            }
        }
        return false;
    }

    /**
     * The nearer of {@link #raycast} and {@link #raycastEntities}: where a beam {@code radius} thick first meets a block
     * or an entity; {@link Hit#entity} is -1 for a block.
     */
    public static boolean raycastAll(double x, double y, double z, double dx, double dy, double dz, double maxDistance,
                                     double radius, int ignore, Hit hit) {
        boolean block = raycast(x, y, z, dx, dy, dz, maxDistance, hit);
        return raycastEntities(x, y, z, dx, dy, dz, block ? hit.distance : maxDistance, radius, ignore, hit) || block;
    }

    /**
     * As {@link #raycastAll(double, double, double, double, double, double, double, double, int, Hit)}, against one
     * level's blocks and entities: where a beam stops on the server.
     *
     * <pre>{@code
     * CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
     * if (CgWorldQueries.raycastAll(levels.world(caster), levels.entities(caster), x, y, z, dx, dy, dz, 64, 0.5,
     *         casterId, hit)) damage(hit.entity);
     * }</pre>
     */
    public static boolean raycastAll(CgWorldQuery world, CgEntityQuery entities, double x, double y, double z, double dx,
                                     double dy, double dz, double maxDistance, double radius, int ignore, Hit hit) {
        boolean block = raycast(world, x, y, z, dx, dy, dz, maxDistance, hit);
        return raycastEntities(entities, x, y, z, dx, dy, dz, block ? hit.distance : maxDistance, radius, ignore, hit)
                || block;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** How long a stretch of ray each entity box query covers, in blocks. */
    private static final double ENTITY_SEGMENT = 8.0;
    private static final EntityRay ENTITY_RAY = new EntityRay();
    /** For the overloads given their queries, which run on whichever thread owns the level. */
    private static final ThreadLocal<EntityRay> THREAD_RAY = ThreadLocal.withInitial(EntityRay::new);

    /** The entity test {@link #raycastEntities} hands the host's box walk; render thread, so one is shared. */
    private static final class EntityRay implements IntConsumer {
        private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        private CgEntityQuery entities;
        private double x, y, z, dx, dy, dz, radius, maxDistance;
        private int ignore, bestEntity, bestAxis;
        private float partialTick;
        double best;

        void begin(CgEntityQuery entities, float partialTick, double x, double y, double z, double dx, double dy, double dz,
                   double radius, int ignore, double maxDistance) {
            this.entities = entities;
            this.partialTick = partialTick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.radius = radius;
            this.ignore = ignore;
            this.maxDistance = maxDistance;
            best = Double.POSITIVE_INFINITY;
            bestEntity = -1;
        }

        @Override
        public void accept(int id) {
            if (id == ignore || (entities.flags(id) & CgEntityQuery.ALIVE) == 0) return;
            int kind = entities.kind(id);
            if (kind == CgEntityQuery.KIND_ITEM || kind == CgEntityQuery.KIND_PROJECTILE) return;
            if (!entities.pose(id, partialTick, pose)) return;
            // The box grown by the radius, met by slabs: where the ray is inside all three.
            double half = pose[CgEntityQuery.WIDTH] * 0.5 + radius;
            double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
            int axis = -1;
            for (int a = 0; a < 3; a++) {
                double o = a == 0 ? x : a == 1 ? y : z, d = a == 0 ? dx : a == 1 ? dy : dz;
                double at = pose[a == 0 ? CgEntityQuery.X : a == 1 ? CgEntityQuery.Y : CgEntityQuery.Z];
                double min = a == 1 ? at - radius : at - half;
                double max = a == 1 ? at + pose[CgEntityQuery.HEIGHT] + radius : at + half;
                if (d == 0.0) {
                    if (o < min || o > max) return;
                    continue;
                }
                double t0 = (min - o) / d, t1 = (max - o) / d;
                if (t0 > t1) {
                    double swap = t0;
                    t0 = t1;
                    t1 = swap;
                }
                if (t0 > near) {
                    near = t0;
                    axis = a;
                }
                far = Math.min(far, t1);
            }
            if (far < 0.0 || near > far) return;
            double t = Math.max(near, 0.0);
            if (t > maxDistance || t >= best) return;
            best = t;
            bestEntity = id;
            bestAxis = near < 0.0 ? -1 : axis;
        }

        void fill(Hit hit) {
            double hx = x + dx * best, hy = y + dy * best, hz = z + dz * best;
            hit.set(hx, hy, hz, floor(hx), floor(hy), floor(hz), best);
            hit.entity = bestEntity;
            hit.normalX = bestAxis == 0 ? (dx > 0 ? -1 : 1) : 0;
            hit.normalY = bestAxis == 1 ? (dy > 0 ? -1 : 1) : 0;
            hit.normalZ = bestAxis == 2 ? (dz > 0 ? -1 : 1) : 0;
            if (bestAxis < 0) {
                hit.normalX = (float) -dx;
                hit.normalY = (float) -dy;
                hit.normalZ = (float) -dz;
            }
        }
    }

    /** Where a ray met a block or an entity: filled by {@link #raycast} and its kin; keep one and reuse it. */
    public static final class Hit {
        /** The point where the ray entered the block's span, in world coordinates. */
        public double x, y, z;
        /** The block it entered. */
        public int blockX, blockY, blockZ;
        /** The face it entered through, a unit vector along an axis; against the ray when it started inside. */
        public float normalX, normalY, normalZ;
        /** How far along the ray, in blocks. */
        public double distance;
        /** The entity it met ({@link #raycastEntities}, {@link #raycastAll}); -1 for a block. */
        public int entity = -1;

        void set(double x, double y, double z, int blockX, int blockY, int blockZ, double distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockX = blockX;
            this.blockY = blockY;
            this.blockZ = blockZ;
            this.distance = distance;
            entity = -1;
        }
    }
}
