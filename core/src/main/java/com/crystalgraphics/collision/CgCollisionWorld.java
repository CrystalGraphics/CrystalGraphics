package com.crystalgraphics.collision;

import com.crystalgraphics.job.CgJob;
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
 * Plain shapes, with no Minecraft at all (tests, an arena): {@link #shapes()}.
 *
 * Off the level's thread, a {@link #snapshot}: the blocks and entities in a box, copied now, which any number of threads
 * may query at once while the level moves on.
 *
 * <pre>{@code
 * CgCollisionWorld area = CgCollisionWorld.of(level).snapshot(x, y, z, 24);    // the level's thread
 * CgJob.run(() -> simulate(area)).then(hits -> hits.forEach(this::hurt));      // queried on a worker
 * }</pre>
 *
 * Many queries in one tick share what they learn of the entities inside a {@link #batch}, as Unity's batched query
 * commands do: past its first 160 asks, each 16-block cell is asked of the host once, and each entity's box and flags
 * read once.
 *
 * <pre>{@code
 * try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
 *     for (Spell s : spells) world.overlap(s.shape).at(s.x, s.y, s.z).forEach(s::hit);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A world is its thread's: {@link #of(Object)} and {@link #client} hand each thread its own, and asking one on
 *       another thread throws. Ask on the level's thread; a worker asks a {@link #snapshot}.</li>
 *   <li>{@link #of(Object)} reads entities where they are this tick; {@link #client} where the frame draws them.</li>
 *   <li>A query is the world's scratch: end one before starting the next (see {@link CgQuery}).</li>
 *   <li>An unloaded cell blocks line of sight; a block is never loaded to answer.</li>
 *   <li>Inside a batch, an entity a hitbox's listener or {@link CgQuery#forEach} was handed is asked of the host again
 *       for the rest of it, so what a hit damages, knocks back or kills is seen as it now is. Code that changes any
 *       other entity tells the batch with {@link #changed(int)}. Hitboxes the engine ticks, and effects, run in one
 *       already.</li>
 * </ul>
 */
public class CgCollisionWorld {

    /** How deep queries may nest, one inside another's visitor. */
    static final int MAX_DEPTH = 4;
    private static final int CACHED_LEVELS = 8;
    /** Snapshots a thread keeps a lane for; each lane holds its snapshot until replaced. */
    private static final int LANES = 4;
    private static final ThreadLocal<PerThread> PER_THREAD = ThreadLocal.withInitial(PerThread::new);

    CgWorldQuery world;
    /** The host's: read through {@link #view}, which is the batch's cache while one is open. */
    CgEntityQuery entities;
    float partialTick;
    /** Queries running on this world: the depth the next one starts at. */
    int running;
    /** A snapshot or one of its lanes: nothing it reads changes, so there is no batch and nothing is watched. */
    boolean frozen;
    /** A snapshot: its queries run on the calling thread's lane. */
    private boolean shared;
    /** On a lane, the snapshot it answers for. */
    private CgCollisionWorld source;
    /** The thread whose world this is; null for one any thread may ask. */
    private Thread owner;
    /** Whether the first thread to query it owns it: a shape world. */
    boolean claims;
    /** {@link #client}'s: the client's one level, whichever query object a host hands out for it. */
    private boolean clientLevel;
    /** A snapshot's hittable hitboxes, frozen; null for none, and for a live world. */
    CgFrozenHittables frozenHittables;

    private final CgQuery[] queries = new CgQuery[MAX_DEPTH];
    private final CgCells sightCells = new CgCells();
    private final Sight sight = new Sight();
    private final CgAABB box = new CgAABB();
    private final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private float[] boxes = new float[6 * 8];
    private CgEntityCache cache;

    CgCollisionWorld(CgWorldQuery world, CgEntityQuery entities, float partialTick) {
        this.world = world;
        this.entities = entities;
        this.partialTick = partialTick;
    }

    /** A level of the host's, a server's or a client's, with its entities where they are this tick. */
    public static CgCollisionWorld of(Object level) {
        refuseWorker("CgCollisionWorld.of(level)");
        CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
        CgWorldQuery w = levels.world(level);
        PerThread t = PER_THREAD.get();
        for (CgCollisionWorld known : t.levels) {
            if (known != null && known.world == w) return known;
        }
        CgCollisionWorld made = new CgCollisionWorld(w, levels.entities(level), 1f);
        made.owner = Thread.currentThread();
        t.levels[t.next] = made;
        t.next = (t.next + 1) % CACHED_LEVELS;
        return made;
    }

    /** The client's level, its entities where the frame draws them: {@code partialTick} between ticks, 1 at one. */
    public static CgCollisionWorld client(float partialTick) {
        refuseWorker("CgCollisionWorld.client(partialTick)");
        PerThread t = PER_THREAD.get();
        CgCollisionWorld c = t.client;
        if (c == null) {
            t.client = c = new CgCollisionWorld(CgWorldQuery.NONE, CgEntityQuery.NONE, 1f);
            c.owner = Thread.currentThread();
            c.clientLevel = true;
        }
        c.world = CgPlatform.get(CgWorldQuery.SERVICE);
        c.entities = CgPlatform.get(CgEntityQuery.SERVICE);
        c.partialTick = partialTick;
        return c;
    }

    /**
     * Whatever answers the two queries, a test's fakes included, at the tick's positions. A new world each call, which
     * any thread may ask if the queries allow it.
     */
    public static CgCollisionWorld of(CgWorldQuery world, CgEntityQuery entities) {
        return new CgCollisionWorld(Objects.requireNonNull(world), Objects.requireNonNull(entities), 1f);
    }

    /** An empty world of plain placed shapes, filled by its owner. */
    public static CgShapeWorld shapes() {
        return new CgShapeWorld();
    }

    /**
     * The blocks and entities touching {@code radius} of a point, copied now: a world any number of threads may query
     * at once, frozen as it was. On the thread this world belongs to.
     *
     * <pre>{@code
     * CgCollisionWorld area = CgCollisionWorld.of(level).snapshot(x, y, z, 24);
     * CgJob.forEach(shards, s -> s.step(area));    // every worker queries it together
     * }</pre>
     *
     * <ul>
     *   <li>Outside the box it holds nothing, and every cell is loaded: a cast leaving it flies on through air.</li>
     *   <li>It knows only the entities in the box, and whole entities their parts belong to. Another reads as
     *       none: flags 0, no box, no pose.</li>
     *   <li>Entities are where this world reads them ({@link #partialTick}), whatever a query asks later.</li>
     *   <li>Copying is the host's calls for every block: on Forge 1.20.1 about 2 ms for a radius of 8, 16 ms for 24 and
     *       45 ms for 32. Snapshot only what the work reaches; past 128 blocks a side it throws.</li>
     *   <li>A snapshot of a snapshot is itself.</li>
     * </ul>
     */
    public CgCollisionWorld snapshot(double x, double y, double z, double radius) {
        return snapshot(new CgAABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius));
    }

    /** The blocks and entities touching {@code bounds}, copied now: {@link #snapshot(double, double, double, double)}. */
    public CgCollisionWorld snapshot(CgAABBc bounds) {
        Objects.requireNonNull(bounds);
        if (frozen) return this;
        checkThread();
        CgCollisionWorld s = new CgCollisionWorld(CgBlockSnapshot.of(world, bounds),
                CgEntitySnapshot.of(entities, partialTick, bounds), partialTick);
        s.frozenHittables = CgFrozenHittables.of(this, bounds);
        s.share();
        return s;
    }

    /** Freezes a new snapshot, its queries run on each thread's lane. */
    void share() {
        frozen = true;
        shared = true;
    }

    /** The blocks it asks: {@code CgWorldQuery.NONE} for a world of shapes. */
    public CgWorldQuery blocks() {
        return world;
    }

    /** The entities it asks, through the batch's cache while one is open: {@code CgEntityQuery.NONE} for shapes. */
    public CgEntityQuery entities() {
        return view();
    }

    /**
     * Opens this thread's batch, until {@link Batch#close}; batches nest, the outermost one deciding. Allocates nothing:
     * the batch is the thread's own.
     *
     * <pre>{@code
     * try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
     *     for (CgHitbox h : mine) h.tick(world, 1);
     * }
     * }</pre>
     */
    public static Batch batch() {
        Batch b = PER_THREAD.get().batch;
        if (b.depth++ == 0) b.generation++;
        return b;
    }

    /** A thread's open batch: {@link #batch()}. */
    public static final class Batch implements AutoCloseable {
        private int depth, generation;

        private Batch() {
        }

        @Override
        public void close() {
            if (depth == 0) throw new IllegalStateException("closed more batches than were opened");
            depth--;
        }
    }

    /**
     * Tells this thread's open batch that {@code entity} moved, died or changed, so its later queries ask the host;
     * nothing outside a batch. An entity a hit was handed out for needs none.
     *
     * <pre>{@code
     * try (CgCollisionWorld.Batch ignored = CgCollisionWorld.batch()) {
     *     world.overlap(PULL).at(x, y, z).forEach(h -> pull(h.entity()));   // hits are re-read already
     *     teleport(bystander);
     *     world.changed(bystander);
     *     world.overlap(BLAST).at(x, y, z).forEach(this::hurt);             // sees the bystander where it went
     * }
     * }</pre>
     */
    public void changed(int entity) {
        if (entity < 0 || frozen) return;
        CgEntityCache c = batchCache();
        if (c != null) c.touch(entity);
    }

    /** Tells this thread's open batch that every entity may have changed, or one was added: it reads them again. */
    public void changed() {
        if (frozen) return;
        CgEntityCache c = batchCache();
        if (c != null) c.forget();
    }

    /** A hit about to reach user code: its entity, and the whole it is part of, are the host's again. */
    void handed(CgHit h) {
        changed(h.entity());
        if (h.parent() != h.entity()) changed(h.parent());
    }

    /** The cache of this thread's open batch, if it holds anything yet. */
    private CgEntityCache batchCache() {
        Batch b = PER_THREAD.get().batch;
        return b.depth > 0 && cache != null && cache.answersFor(entities, partialTick, b.generation) ? cache : null;
    }

    /** The entities as queries read them: the batch's cache while one is open on this thread, else the host's. */
    CgEntityQuery view() {
        if (frozen || entities == CgEntityQuery.NONE) return entities;
        Batch b = PER_THREAD.get().batch;
        if (b.depth == 0) return entities;
        if (cache == null) cache = new CgEntityCache();
        if (!cache.answersFor(entities, partialTick, b.generation)) cache.reset(entities, partialTick, b.generation);
        return cache.warm() ? cache : entities;
    }

    /** Where between ticks it reads entities: 1 at the tick's positions. */
    public float partialTick() {
        return partialTick;
    }

    /** What {@code shape} overlaps, placed by {@link CgQuery#at} and {@link CgQuery#facing}. */
    public CgQuery overlap(CgShape shape) {
        if (shared) return lane().overlap(shape);
        return next().start(CgQuery.OVERLAP, Objects.requireNonNull(shape), null);
    }

    /** What a box overlaps, absolute in the world: an entity's own box, a region. */
    public CgQuery overlap(CgAABBc box) {
        if (shared) return lane().overlap(box);
        return next().start(CgQuery.OVERLAP, null, Objects.requireNonNull(box));
    }

    /** What {@code shape} first meets moving {@link CgQuery#along} a line. */
    public CgQuery cast(CgShape shape) {
        if (shared) return lane().cast(shape);
        return next().start(CgQuery.CAST, Objects.requireNonNull(shape), null);
    }

    /** What a ray first meets, from {@link CgQuery#at} {@link CgQuery#along} a line. */
    public CgQuery ray() {
        if (shared) return lane().ray();
        return next().start(CgQuery.RAY, null, null);
    }

    /** Whether no block stands between the point and {@code target}'s eyes; false for no such entity. */
    public boolean lineOfSight(double x, double y, double z, int target) {
        if (shared) return lane().lineOfSight(x, y, z, target);
        if (!view().pose(target, partialTick, pose)) return false;
        return clear(x, y, z, pose[CgEntityQuery.X], pose[CgEntityQuery.Y] + pose[CgEntityQuery.EYE_HEIGHT],
                pose[CgEntityQuery.Z]);
    }

    /**
     * How much of {@code target} the point sees, 0 to 1: Minecraft's {@code Explosion.getSeenPercent}, a grid of points
     * over its box each looking back past blocks. 0 for no such entity.
     */
    public float exposure(double x, double y, double z, int target) {
        if (shared) return lane().exposure(x, y, z, target);
        if (!view().box(target, partialTick, box)) return 0f;
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
        checkThread();
        if (running == MAX_DEPTH) throw new IllegalStateException("queries nest " + MAX_DEPTH + " deep at most");
        CgQuery q = queries[running];
        if (q == null) queries[running] = q = new CgQuery(this);
        return q;
    }

    void checkThread() {
        if (frozen) return;
        Thread me = Thread.currentThread();
        if (owner == null) {
            if (claims) owner = me;
        } else if (owner != me) {
            throw new IllegalStateException("this world belongs to " + owner.getName() + "; on another thread, query a "
                    + "snapshot taken on it: world.snapshot(x, y, z, radius)");
        }
    }

    private static void refuseWorker(String call) {
        if (CgJob.isWorkerThread()) {
            throw new IllegalStateException(call + " on a job's worker: the level is its own thread's. Take a snapshot "
                    + "there and hand it to the job: CgCollisionWorld.of(level).snapshot(x, y, z, radius)");
        }
    }

    /** This thread's lane over this snapshot: a world of its own over the same frozen data. */
    private CgCollisionWorld lane() {
        PerThread t = PER_THREAD.get();
        CgCollisionWorld[] lanes = t.lanes;
        for (CgCollisionWorld l : lanes) {
            if (l != null && l.source == this) return l;
        }
        int slot = t.nextLane;
        t.nextLane = (slot + 1) % LANES;
        CgCollisionWorld l = lanes[slot];
        // a lane still running a query keeps it, out of the cache
        if (l == null || l.running > 0 || l.getClass() != getClass()) {
            l = blank();
            l.frozen = true;
            lanes[slot] = l;
        }
        l.answerFor(this);
        return l;
    }

    /** An empty world of this class, to be a lane. */
    CgCollisionWorld blank() {
        return new CgCollisionWorld(CgWorldQuery.NONE, CgEntityQuery.NONE, 1f);
    }

    /** Makes this lane answer for {@code snapshot}. */
    void answerFor(CgCollisionWorld snapshot) {
        source = snapshot;
        world = snapshot.world;
        entities = snapshot.entities;
        partialTick = snapshot.partialTick;
        frozenHittables = snapshot.frozenHittables;
    }

    /** Hands {@code visitor} every entity of {@code view} whose box touches {@code bounds}. */
    void candidates(CgQuery q, CgEntityQuery view, CgAABBc bounds, IntConsumer visitor) {
        view.within(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                visitor);
    }

    /**
     * Whether a hitbox ticking in {@code other} stands in this world's level: the same host level, or the client's
     * one level however it was reached, or the same shape world.
     */
    boolean sameLevel(CgCollisionWorld other) {
        if (other == this) return true;
        if (!hasBlocks() || !other.hasBlocks()) return false;
        return world == other.world && entities == other.entities || clientLevel || other.clientLevel;
    }

    boolean hasBlocks() {
        return true;
    }

    /** What {@code id} rides at the bottom: a query's source, and everyone riding with it, are never hit. */
    int rootOf(CgEntityQuery view, int id) {
        return view.rootVehicle(id);
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
        final CgCollisionWorld[] lanes = new CgCollisionWorld[LANES];
        final Batch batch = new Batch();
        int next, nextLane;
        CgCollisionWorld client;
    }
}
