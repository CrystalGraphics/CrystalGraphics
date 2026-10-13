package com.crystalgraphics.collision;

import com.crystalgraphics.collision.block.CgBlockCells;
import com.crystalgraphics.collision.block.CgBlockSnapshot;
import com.crystalgraphics.collision.entity.CgEntityCache;
import com.crystalgraphics.collision.entity.CgEntitySnapshot;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
import com.crystalgraphics.collision.mirror.CgLevelMirror;
import com.crystalgraphics.collision.mirror.CgMirrorVersion;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;

/**
 * A level's blocks and entities, asked what a shape meets: everyone in a blast, the first thing a bolt strikes, whether
 * a target can be seen. Each question is a {@link CgCollisionQuery}, started here and read left to right.
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
 * Only your own shapes, with no level's blocks or entities: an {@link #empty()} world, met only by the hittable
 * hitboxes ticked in it. It works the same in the game, beside a level's world, as in the harness or a test.
 *
 * <pre>{@code
 * CgCollisionWorld arena = CgCollisionWorld.empty();
 * CgHitbox wall = CgHitbox.of(WALL).source(WALL_ID).hittable().passive();
 * wall.pose(pose.at(4, 0, 0)).tick(arena, 1);                   // placed, and met from now on
 * arena.ray().at(0, 1, 0).along(1, 0, 0, 10).first(hit);        // hit.entity() == WALL_ID
 * }</pre>
 *
 * Off the level's thread, a {@link #snapshot} or a {@link #watch}: the blocks and entities in a box as they stood at the
 * end of the last server tick, which any number of threads may query at once while the level moves on. A server level
 * keeps them in one shared mirror, so asking costs the tick nothing however many ask.
 *
 * <pre>{@code
 * CgCollisionWorld.of(level).snapshot(x, y, z, 24)                              // the level's thread
 *         .then(area -> CgJob.run(() -> simulate(area)).then(hits -> hits.forEach(this::hurt)));
 *
 * CgCollisionWorld.Watch arena = CgCollisionWorld.of(level).watch(ARENA);      // a simulation running every tick
 * CgCollisionWorld now = arena.latest();                                        // any thread: the newest tick's
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
 *   <li>A query is its thread's scratch: end one before starting the next (see {@link CgCollisionQuery}).</li>
 *   <li>An unloaded cell blocks line of sight; a block is never loaded to answer.</li>
 *   <li>Inside a batch, an entity a hitbox's listener or {@link CgCollisionQuery#forEach} was handed is asked of the
 *       host again for the rest of it, so what a hit damages, knocks back or kills is seen as it now is. Code that
 *       changes any other entity tells the batch with {@link #changed(int)}. Hitboxes the engine ticks, and effects, run in one
 *       already.</li>
 * </ul>
 */
public class CgCollisionWorld {

    /** How deep queries may nest, one inside another's visitor. */
    static final int MAX_DEPTH = 4;
    private static final int CACHED_LEVELS = 8;
    private static final ThreadLocal<PerThread> PER_THREAD = ThreadLocal.withInitial(PerThread::new);

    CgWorldQuery world;
    /** The host's: read through {@link #view}, which is the batch's cache while one is open. */
    CgEntityQuery entities;
    float partialTick;
    /** A snapshot: nothing it reads changes, so any thread may ask it, with no batch and nothing watched. */
    boolean frozen;
    /** The thread whose world this is; null for one any thread may ask. */
    private Thread owner;
    /** {@link #client}'s: the client's one level, whichever query object a host hands out for it. */
    private boolean clientLevel;
    /** A snapshot's hittable hitboxes, frozen; null for none, and for a live world. */
    @Nullable FrozenHittables frozenHittables;
    /** {@link #of(Object)}'s level, for its mirror; null for every other world. */
    @Nullable private WeakReference<Object> level;
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
        made.level = new WeakReference<>(level);
        t.levels[t.nextLevel] = made;
        t.nextLevel = (t.nextLevel + 1) % CACHED_LEVELS;
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

    /**
     * A world of hitboxes alone: no level's blocks or entities, only the hittable hitboxes ticked in it, each standing
     * for its {@code source}. Use it in the game for queries that should meet only your own shapes, and in the harness
     * or a test where there is no level. A new world each call.
     *
     * <ul>
     *   <li>Its hitboxes are met by queries on the thread that ticks them. Another thread asks a {@link #snapshot}.</li>
     *   <li>Entity filters ({@code living()}, {@code kinds}) pass everything: there are no flags to ask.</li>
     * </ul>
     */
    public static CgCollisionWorld empty() {
        return new CgCollisionWorld(CgWorldQuery.NONE, CgEntityQuery.NONE, 1f);
    }

    /**
     * The blocks and entities touching {@code radius} of a point as they stood at the end of the last server tick: a
     * world any number of threads may query at once, frozen. On the thread this world belongs to; the reply settles
     * there too.
     *
     * <pre>{@code
     * CgCollisionWorld.of(level).snapshot(x, y, z, 24)
     *         .then(area -> CgJob.forEach(shards, s -> s.step(area)));    // every worker queries it together
     * }</pre>
     *
     * <ul>
     *   <li>A server level answers from its mirror: at once when the area is mirrored already, else at the end of a
     *       later tick, once it is. Asking costs the tick nothing; the same box asked again is the same area, kept
     *       while it is asked (a {@link #watch} keeps one for good).</li>
     *   <li>Any other world (a client's, a test's, an {@link #empty()} one, a level whose write hook did not apply) is
     *       copied now, on this thread, and the reply is settled already.</li>
     *   <li>Outside the box it holds nothing, and every cell is loaded: a cast leaving it flies on through air. A column
     *       inside it that is not loaded reads as unloaded.</li>
     *   <li>Entities are where they stood at the tick's end ({@link #partialTick}), whatever a query asks later.</li>
     *   <li>A snapshot of a snapshot is itself.</li>
     * </ul>
     */
    public CgReply<CgCollisionWorld> snapshot(double x, double y, double z, double radius) {
        return snapshot(new CgAABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius));
    }

    /** The blocks and entities touching {@code bounds}: {@link #snapshot(double, double, double, double)}. */
    public CgReply<CgCollisionWorld> snapshot(CgAABBc bounds) {
        Objects.requireNonNull(bounds);
        if (frozen) return CgReply.of(this);
        checkThread();
        CgLevelMirror mirror = mirror();
        if (mirror == null) return CgReply.of(copy(bounds));
        CgLevelMirror.Watch asked = mirror.asked(bounds);
        CgAABB box = new CgAABB(bounds);
        return asked.ready().map(v -> frozen(v, box));
    }

    /**
     * Keeps {@code bounds} as it stands at the end of every server tick until {@link Watch#close}: what a simulation that
     * runs every tick reads. On the thread this world belongs to; the watch is any thread's.
     *
     * <pre>{@code
     * CgCollisionWorld.Watch arena = CgCollisionWorld.of(level).watch(ARENA);
     * CgJob.repeat(arena::latest).run(area -> step(area));    // each run, the newest tick's
     * arena.close();
     * }</pre>
     *
     * @throws IllegalArgumentException when the area alone is past {@code crystalgraphics.mirror.memory}
     */
    public Watch watch(CgAABBc bounds) {
        Objects.requireNonNull(bounds);
        checkThread();
        CgLevelMirror mirror = frozen ? null : mirror();
        return new Watch(this, bounds, mirror == null ? null : mirror.watch(bounds));
    }

    /** This level's mirror; null for a world that is not a server level of a host that mirrors it. */
    @Nullable
    private CgLevelMirror mirror() {
        Object lvl = level == null ? null : level.get();
        if (lvl == null) return null;
        return CgLevelMirror.of(lvl, world, entities, within -> FrozenHittables.of(this, within));
    }

    /** A mirror version's world inside {@code bounds}, frozen. */
    static CgCollisionWorld frozen(CgMirrorVersion v, CgAABBc bounds) {
        CgCollisionWorld s = new CgCollisionWorld(v.blocks(bounds), v.entities(), 1f);
        s.frozenHittables = (FrozenHittables) v.frozen();
        s.frozen = true;
        return s;
    }

    /** The blocks and entities touching {@code bounds}, copied now on this thread: a world with no mirror. */
    CgCollisionWorld copy(CgAABBc bounds) {
        if (frozen) return this;
        checkThread();
        CgWorldQuery blocks = world == CgWorldQuery.NONE ? world : CgBlockSnapshot.of(world, bounds);
        CgEntityQuery seen = entities == CgEntityQuery.NONE ? entities : CgEntitySnapshot.of(entities, partialTick, bounds);
        CgCollisionWorld s = new CgCollisionWorld(blocks, seen, partialTick);
        s.frozenHittables = FrozenHittables.of(this, bounds);
        s.frozen = true;
        return s;
    }

    /**
     * An area kept as it stands at the end of every server tick, until {@link #close}d: {@link CgCollisionWorld#watch}.
     * {@link #latest} is the newest tick's world, frozen, which any number of threads may query at once.
     *
     * <pre>{@code
     * CgCollisionWorld.Watch watch = CgCollisionWorld.of(level).watch(bounds);   // the level's thread
     * CgCollisionWorld area = watch.latest();                                    // any thread, each tick
     * watch.move(next);                                                          // a boss walking: any thread
     * watch.close();
     * }</pre>
     *
     * <ul>
     *   <li>{@link #latest} is null until the level's first tick's end after the watch opened, and holds the area only
     *       once {@link #complete}: until then, columns not yet mirrored read as unloaded. {@link #ready} waits for it.</li>
     *   <li>A world with no mirror (a client's, a test's) copies the area at each {@link #latest}, on its own thread
     *       only.</li>
     *   <li>Reading allocates one world each new tick, shared by every reader; within a tick it allocates nothing.</li>
     * </ul>
     */
    public static final class Watch implements AutoCloseable {

        private final CgCollisionWorld owner;
        @Nullable private final CgLevelMirror.Watch mirrored;
        private volatile CgAABB bounds;
        @Nullable private volatile CgMirrorVersion seen;
        @Nullable private volatile CgCollisionWorld seenWorld;

        Watch(CgCollisionWorld owner, CgAABBc bounds, @Nullable CgLevelMirror.Watch mirrored) {
            this.owner = owner;
            this.bounds = new CgAABB(bounds);
            this.mirrored = mirrored;
        }

        /** The newest tick's world inside the area; null before the first. Any thread (a world with no mirror: its own). */
        @Nullable
        public CgCollisionWorld latest() {
            if (mirrored == null) return owner.copy(bounds);
            CgMirrorVersion v = mirrored.version();
            if (v == null) return null;
            CgCollisionWorld w = seenWorld;
            if (v == seen && w != null) return w;
            w = frozen(v, mirrored.bounds());
            seenWorld = w;
            seen = v;
            return w;
        }

        /** Whether {@link #latest} holds the whole area. Always true for a world with no mirror. Any thread. */
        public boolean complete() {
            return mirrored == null || mirrored.complete();
        }

        /** The first world holding the whole area: settled now when the newest does. The thread the watch was opened on. */
        public CgReply<CgCollisionWorld> ready() {
            if (mirrored == null) return CgReply.of(owner.copy(bounds));
            return mirrored.ready().map(v -> frozen(v, mirrored.bounds()));
        }

        /** Keeps {@code next} instead, from the next tick's end. Any thread. */
        public void move(CgAABBc next) {
            bounds = new CgAABB(next);
            if (mirrored != null) mirrored.move(next);
        }

        /** Stops keeping it. Any thread; a second close does nothing. */
        @Override
        public void close() {
            if (mirrored != null) mirrored.close();
        }
    }

    /** The blocks it asks: {@code CgWorldQuery.NONE} for an {@link #empty()} world. */
    public CgWorldQuery blocks() {
        return world;
    }

    /** The entities it asks, through the batch's cache while one is open: {@code CgEntityQuery.NONE} for an empty world. */
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

    /** What {@code shape} overlaps, placed by {@link CgCollisionQuery#at} and {@link CgCollisionQuery#facing}. */
    public CgCollisionQuery overlap(CgShape shape) {
        return next().start(this, CgCollisionQuery.OVERLAP, Objects.requireNonNull(shape), null);
    }

    /** What a box overlaps, absolute in the world: an entity's own box, a region. */
    public CgCollisionQuery overlap(CgAABBc box) {
        return next().start(this, CgCollisionQuery.OVERLAP, null, Objects.requireNonNull(box));
    }

    /** What {@code shape} first meets moving {@link CgCollisionQuery#along} a line. */
    public CgCollisionQuery cast(CgShape shape) {
        return next().start(this, CgCollisionQuery.CAST, Objects.requireNonNull(shape), null);
    }

    /** What a ray first meets, from {@link CgCollisionQuery#at} {@link CgCollisionQuery#along} a line. */
    public CgCollisionQuery ray() {
        return next().start(this, CgCollisionQuery.RAY, null, null);
    }

    /** Whether no block stands between the point and {@code target}'s eyes; false for no such entity. */
    public boolean lineOfSight(double x, double y, double z, int target) {
        checkThread();
        Sight s = PER_THREAD.get().sight;
        if (!view().pose(target, partialTick, s.pose)) return false;
        return s.clear(world, x, y, z, s.pose[CgEntityQuery.X], s.pose[CgEntityQuery.Y] + s.pose[CgEntityQuery.EYE_HEIGHT],
                s.pose[CgEntityQuery.Z]);
    }

    /**
     * How much of {@code target} the point sees, 0 to 1: Minecraft's {@code Explosion.getSeenPercent}, a grid of points
     * over its box each looking back past blocks. 0 for no such entity.
     */
    public float exposure(double x, double y, double z, int target) {
        checkThread();
        Sight s = PER_THREAD.get().sight;
        CgAABB box = s.box;
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
                    if (s.clear(world, px, py, pz, x, y, z)) seen++;
                    samples++;
                }
            }
        }
        return (float) seen / samples;
    }

    // ---- what a query asks of the world

    /** This thread's query at the depth now running. */
    private CgCollisionQuery next() {
        checkThread();
        PerThread t = PER_THREAD.get();
        if (t.depth == MAX_DEPTH) throw new IllegalStateException("queries nest " + MAX_DEPTH + " deep at most");
        CgCollisionQuery q = t.queries[t.depth];
        if (q == null) t.queries[t.depth] = q = new CgCollisionQuery(t);
        return q;
    }

    void checkThread() {
        if (frozen) return;
        if (owner != null && owner != Thread.currentThread()) {
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

    /**
     * Whether a hitbox ticking in {@code other} stands in this world's level: the same host level, or the client's
     * one level however it was reached; an empty world holds only its own.
     */
    boolean sameLevel(CgCollisionWorld other) {
        if (other == this) return true;
        if (world == CgWorldQuery.NONE || other.world == CgWorldQuery.NONE) return false;
        return world == other.world && entities == other.entities || clientLevel || other.clientLevel;
    }

    /** A thread's queries, one per depth, its line-of-sight scratch, its batch and the worlds it was handed. */
    /**
     * A snapshot's hittable hitboxes, frozen: each part's shape, a copy of its pose, its box, and its owner's flags, kind
     * and root vehicle as they were. Candidates are found by a scan of the boxes.
     */
    static final class FrozenHittables {

        private CgHitbox[] hitboxes = new CgHitbox[4];
        private CgHitPart[] names = new CgHitPart[4];
        private CgShape[] shapes = new CgShape[4];
        private CgPose[] poses = new CgPose[4];
        private CgAABB[] bounds = new CgAABB[4];
        /** Per part: the id it stands for, and that id's flags, kind and root vehicle. */
        private int[] owners = new int[4 * 4];
        private int size;

        /** {@code world}'s live hittable hitboxes touching {@code within}, copied now; null for none. */
        static @Nullable FrozenHittables of(CgCollisionWorld world, CgAABBc within) {
            FrozenHittables set = new FrozenHittables();
            set.freeze(world, within);
            return set.size == 0 ? null : set;
        }

        private void freeze(CgCollisionWorld world, CgAABBc within) {
            CgEntityQuery view = world.view();
            CgAABB box = new CgAABB();
            CgHitboxes.hittables(new CgHitboxes.Hittables() {
                private int owner, flags, kind, root;

                @Override
                public boolean wants(CgHitbox hitbox, CgCollisionWorld tickedIn, int owner) {
                    if (!world.sameLevel(tickedIn)) return false;
                    this.owner = owner;
                    flags = CgCollisionQuery.flagsOf(view, owner);
                    kind = view.kind(owner);
                    root = view.rootVehicle(owner);
                    return true;
                }

                @Override
                public void part(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose) {
                    if (shape.bounds(pose, box).intersects(within)) {
                        add(hitbox, name, shape, pose, owner, flags, kind, root);
                    }
                }
            }, true);
        }

        /** Adds a part at a copy of {@code pose}. */
        private void add(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose, int owner, int flags,
                         int kind, int root) {
            if (size == shapes.length) grow();
            hitboxes[size] = hitbox;
            names[size] = name;
            shapes[size] = shape;
            if (poses[size] == null) {
                poses[size] = new CgPose();
                bounds[size] = new CgAABB();
            }
            poses[size].set(pose);
            shape.bounds(pose, bounds[size]);
            int o = size * 4;
            owners[o] = owner;
            owners[o + 1] = flags;
            owners[o + 2] = kind;
            owners[o + 3] = root;
            size++;
        }

        /** Hands {@code q} each part whose box touches {@code within}. */
        void scan(CgCollisionQuery q, CgAABBc within) {
            for (int i = 0; i < size; i++) {
                if (!bounds[i].intersects(within)) continue;
                int o = i * 4;
                q.meet(hitboxes[i], names[i], shapes[i], poses[i], owners[o], owners[o + 1], owners[o + 2], owners[o + 3]);
            }
        }

        private void grow() {
            int n = size * 2;
            hitboxes = Arrays.copyOf(hitboxes, n);
            names = Arrays.copyOf(names, n);
            shapes = Arrays.copyOf(shapes, n);
            poses = Arrays.copyOf(poses, n);
            bounds = Arrays.copyOf(bounds, n);
            owners = Arrays.copyOf(owners, n * 4);
        }
    }

    static final class PerThread {
        final CgCollisionQuery[] queries = new CgCollisionQuery[MAX_DEPTH];
        /** Queries running on this thread: the depth the next one starts at. */
        int depth;
        final Sight sight = new Sight();
        final CgCollisionWorld[] levels = new CgCollisionWorld[CACHED_LEVELS];
        final Batch batch = new Batch();
        int nextLevel;
        CgCollisionWorld client;
    }

    /** A segment through the cells, stopping at the first collision box or unloaded cell. */
    static final class Sight implements CgBlockCells.Visitor {
        final CgAABB box = new CgAABB();
        final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        private final CgBlockCells cells = new CgBlockCells();
        private final CgAABB cellBox = new CgAABB();
        private float[] boxes = new float[6 * 8];
        private CgWorldQuery world;
        private double ox, oy, oz, invX, invY, invZ;
        private boolean blocked;

        /** Whether the segment between the points passes no collision box and no unloaded cell. */
        boolean clear(CgWorldQuery world, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
            if (world.levelEpoch() == 0) return true;
            double mx = toX - fromX, my = toY - fromY, mz = toZ - fromZ;
            this.world = world;
            ox = fromX;
            oy = fromY;
            oz = fromZ;
            invX = 1 / mx;
            invY = 1 / my;
            invZ = 1 / mz;
            blocked = false;
            cells.set(fromX, fromY, fromZ, fromX, fromY, fromZ, mx, my, mz).walk(world.minY(), world.maxY(), this);
            this.world = null;
            return !blocked;
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
}
