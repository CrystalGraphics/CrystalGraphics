package com.crystalgraphics.collision.query;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * A level's blocks and entities, asked what a shape meets: everyone in a blast, the first thing a bolt strikes, whether
 * a target can be seen. Each question is a {@link CgQuery}, started here and read left to right.
 *
 * <pre>{@code
 * CgCollisionWorld world = CgCollisionWorld.of(level);          // a server's or a client's level
 *
 * world.overlap(BLAST).at(x, y, z).except(caster).living().forEach(h -> damage(h.entity(), 10f * h.falloff(2, 6)));
 * world.ray().at(eyeX, eyeY, eyeZ).along(dirX, dirY, dirZ, 64).blocksOnly().first(hit);   // a stair's real step
 * boolean seen = world.lineOfSight(x, y, z, target);
 * float exposed = world.exposure(x, y, z, target);              // Minecraft's explosion sampling, 0 to 1
 * }</pre>
 *
 * Where the entities are drawn, on a client's frame:
 *
 * <pre>{@code
 * CgCollisionWorld world = CgCollisionWorld.client(partialTick);
 * }</pre>
 *
 * Plain shapes, with no Minecraft at all (tests, an arena, a mod's own hurtboxes): {@link #shapes()}.
 *
 * <ul>
 *   <li>A world is its thread's: {@link #of(Object)} and {@link #client} hand each thread its own, and nothing here is
 *       safe to share. Ask on the level's thread.</li>
 *   <li>{@link #of(Object)} reads entities where they are this tick; {@link #client} where the frame draws them.</li>
 *   <li>A query is the world's scratch: end one before starting the next (see {@link CgQuery}).</li>
 *   <li>An unloaded cell blocks line of sight; a block is never loaded to answer.</li>
 * </ul>
 */
public class CgCollisionWorld {

    /** How deep queries may nest, one inside another's visitor. */
    static final int MAX_DEPTH = 4;
    private static final int CACHED_LEVELS = 8;
    private static final ThreadLocal<PerThread> PER_THREAD = ThreadLocal.withInitial(PerThread::new);

    CgWorldQuery world;
    CgEntityQuery entities;
    float partialTick;
    /** Queries running on this world: the depth the next one starts at. */
    int running;

    private final CgQuery[] queries = new CgQuery[MAX_DEPTH];
    private final CgCells sightCells = new CgCells();
    private final Sight sight = new Sight();
    private final CgAABB box = new CgAABB();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private float[] boxes = new float[6 * 8];

    CgCollisionWorld(CgWorldQuery world, CgEntityQuery entities, float partialTick) {
        this.world = world;
        this.entities = entities;
        this.partialTick = partialTick;
    }

    /** A level of the host's, a server's or a client's, with its entities where they are this tick. */
    public static CgCollisionWorld of(Object level) {
        CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
        CgWorldQuery w = levels.world(level);
        PerThread t = PER_THREAD.get();
        for (CgCollisionWorld known : t.levels) {
            if (known != null && known.world == w) return known;
        }
        CgCollisionWorld made = new CgCollisionWorld(w, levels.entities(level), 1f);
        t.levels[t.next] = made;
        t.next = (t.next + 1) % CACHED_LEVELS;
        return made;
    }

    /** The client's level, its entities where the frame draws them: {@code partialTick} between ticks, 1 at one. */
    public static CgCollisionWorld client(float partialTick) {
        PerThread t = PER_THREAD.get();
        CgCollisionWorld c = t.client;
        if (c == null) t.client = c = new CgCollisionWorld(CgWorldQuery.NONE, CgEntityQuery.NONE, 1f);
        c.world = CgPlatform.get(CgWorldQuery.SERVICE);
        c.entities = CgPlatform.get(CgEntityQuery.SERVICE);
        c.partialTick = partialTick;
        return c;
    }

    /** Whatever answers the two queries, a test's fakes included, at the tick's positions. A new world each call. */
    public static CgCollisionWorld of(CgWorldQuery world, CgEntityQuery entities) {
        return new CgCollisionWorld(Objects.requireNonNull(world), Objects.requireNonNull(entities), 1f);
    }

    /** An empty world of plain placed shapes, filled by its owner. */
    public static CgShapeWorld shapes() {
        return new CgShapeWorld();
    }

    /** The blocks it asks: {@code CgWorldQuery.NONE} for a world of shapes. */
    public CgWorldQuery blocks() {
        return world;
    }

    /** The entities it asks: {@code CgEntityQuery.NONE} for a world of shapes. */
    public CgEntityQuery entities() {
        return entities;
    }

    /** Where between ticks it reads entities: 1 at the tick's positions. */
    public float partialTick() {
        return partialTick;
    }

    /** What {@code shape} overlaps, placed by {@link CgQuery#at} and {@link CgQuery#facing}. */
    public CgQuery overlap(CgShape shape) {
        return next().start(CgQuery.OVERLAP, Objects.requireNonNull(shape), null);
    }

    /** What a box overlaps, absolute in the world: an entity's own box, a region. */
    public CgQuery overlap(CgAABBc box) {
        return next().start(CgQuery.OVERLAP, null, Objects.requireNonNull(box));
    }

    /** What {@code shape} first meets moving {@link CgQuery#along} a line. */
    public CgQuery cast(CgShape shape) {
        return next().start(CgQuery.CAST, Objects.requireNonNull(shape), null);
    }

    /** What a ray first meets, from {@link CgQuery#at} {@link CgQuery#along} a line. */
    public CgQuery ray() {
        return next().start(CgQuery.RAY, null, null);
    }

    /** Whether no block stands between the point and {@code target}'s eyes; false for no such entity. */
    public boolean lineOfSight(double x, double y, double z, int target) {
        if (!entities.pose(target, partialTick, pose)) return false;
        return clear(x, y, z, pose[CgEntityQuery.X], pose[CgEntityQuery.Y] + pose[CgEntityQuery.EYE_HEIGHT],
                pose[CgEntityQuery.Z]);
    }

    /**
     * How much of {@code target} the point sees, 0 to 1: Minecraft's {@code Explosion.getSeenPercent}, a grid of points
     * over its box each looking back past blocks. 0 for no such entity.
     */
    public float exposure(double x, double y, double z, int target) {
        if (!entities.box(target, partialTick, box)) return 0f;
        // Explosion.getSeenPercent, 1.20.1
        double sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        double offX = (1.0 - Math.floor(1.0 / sx) * sx) / 2.0, offZ = (1.0 - Math.floor(1.0 / sz) * sz) / 2.0;
        if (sx < 0 || sy < 0 || sz < 0) return 0f;
        int seen = 0, samples = 0;
        for (double fx = 0; fx <= 1; fx += sx) {
            for (double fy = 0; fy <= 1; fy += sy) {
                for (double fz = 0; fz <= 1; fz += sz) {
                    double px = box.minX + fx * (box.maxX - box.minX) + offX;
                    double py = box.minY + fy * (box.maxY - box.minY);
                    double pz = box.minZ + fz * (box.maxZ - box.minZ) + offZ;
                    if (clear(px, py, pz, x, y, z)) seen++;
                    samples++;
                }
            }
        }
        return (float) seen / samples;
    }

    // ---- what a query asks of the world

    private CgQuery next() {
        if (running == MAX_DEPTH) throw new IllegalStateException("queries nest " + MAX_DEPTH + " deep at most");
        CgQuery q = queries[running];
        if (q == null) queries[running] = q = new CgQuery(this);
        return q;
    }

    /** Hands {@code visitor} every entity whose box touches {@code bounds}. */
    void candidates(CgQuery q, CgAABBc bounds, IntConsumer visitor) {
        entities.within(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                visitor);
    }

    boolean hasBlocks() {
        return true;
    }

    /** What {@code id} rides at the bottom: a query's source, and everyone riding with it, are never hit. */
    int rootOf(int id) {
        return entities.rootVehicle(id);
    }

    /** Whether the segment between the points passes no collision box and no unloaded cell. */
    boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        if (world.levelEpoch() == 0) return true;
        double mx = toX - fromX, my = toY - fromY, mz = toZ - fromZ;
        sight.start(fromX, fromY, fromZ, mx, my, mz);
        sightCells.set(fromX, fromY, fromZ, fromX, fromY, fromZ, mx, my, mz).walk(world.minY(), world.maxY(), sight);
        return !sight.blocked;
    }

    /** A segment through the cells, stopping at the first box or unloaded cell. */
    private final class Sight implements CgCells.Visitor {
        private final CgAABB cellBox = new CgAABB();
        private double ox, oy, oz, invX, invY, invZ;
        boolean blocked;

        void start(double x, double y, double z, double mx, double my, double mz) {
            ox = x;
            oy = y;
            oz = z;
            invX = 1 / mx;
            invY = 1 / my;
            invZ = 1 / mz;
            blocked = false;
        }

        @Override
        public boolean cell(int x, int y, int z) {
            if (!world.loaded(x, z)) {
                blocked = true;
                return false;
            }
            int n = world.collisionBoxes(x, y, z, boxes);
            if (n * 6 > boxes.length) {
                boxes = new float[n * 6];
                n = world.collisionBoxes(x, y, z, boxes);
            }
            for (int i = 0; i < n * 6; i += 6) {
                cellBox.set(x + boxes[i], y + boxes[i + 1], z + boxes[i + 2], x + boxes[i + 3], y + boxes[i + 4],
                        z + boxes[i + 5]);
                if (cellBox.rayHit(ox, oy, oz, invX, invY, invZ, 1) != Double.POSITIVE_INFINITY) {
                    blocked = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public double limit() {
            return Double.POSITIVE_INFINITY;
        }
    }

    private static final class PerThread {
        final CgCollisionWorld[] levels = new CgCollisionWorld[CACHED_LEVELS];
        int next;
        CgCollisionWorld client;
    }
}
