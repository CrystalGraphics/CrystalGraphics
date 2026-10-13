package com.crystalgraphics.collision;

import com.crystalgraphics.collision.block.CgCells;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.query.CgCastHit;
import com.crystalgraphics.shape.query.CgShapes;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * One question to a {@link CgCollisionWorld}, read left to right: a shape, where it is, what it may hit, and what to do
 * with the hits. Started by {@link CgCollisionWorld#overlap}, {@link CgCollisionWorld#cast} or
 * {@link CgCollisionWorld#ray}, and ended by one of {@link #forEach}, {@link #first}, {@link #any}, {@link #count} or
 * {@link #into}.
 *
 * <pre>{@code
 * static final CgShape BLAST = CgSphere.of(6);
 *
 * world.overlap(BLAST).at(x, y, z).except(caster).living().forEach(h -> damage(h.entity(), 10f * h.falloff(2, 6)));
 * world.overlap(CLEAVE).at(eyeX, eyeY, eyeZ).facing(yaw, pitch).source(caster).forEach(h -> damage(h.entity(), 6f));
 * boolean blocked = world.ray().at(eyeX, eyeY, eyeZ).along(lookX, lookY, lookZ, 64).blocksOnly().any();
 * }</pre>
 *
 * The first thing a moving shape meets, blocks by their real shapes included:
 *
 * <pre>{@code
 * private final CgHit hit = new CgHit();                       // a field, reused
 *
 * if (world.cast(BOLT).at(x, y, z).along(vx, vy, vz, 3).source(caster).blocksToo().first(hit)) strike(hit);
 * }</pre>
 *
 * Targets, by default, are what Minecraft's projectiles hit: alive, pickable, not a spectator. {@link #living},
 * {@link #itemsToo}, {@link #allEntities}, {@link #kinds}, {@link #solid} and {@link #where} change that.
 *
 * An entity's own body moving, met as Minecraft's movement meets it: blocks as they show that entity, the world border,
 * and the entities it would stand on:
 *
 * <pre>{@code
 * world.cast(body).at(x, y, z).along(vx, vy, vz, 1).as(mob).blocksToo().border().solid().first(hit);
 * }</pre>
 *
 * <ul>
 *   <li>The query is its thread's scratch, reset by the next query started there: end one before starting the next,
 *       and never hold one. A query inside a visitor is fine, four deep.</li>
 *   <li>A cast or ray with blocks stops at the first block: that block is the last hit, and nothing beyond it is.</li>
 *   <li>An unloaded cell stops a cast or ray ({@link CgHit.Kind#UNLOADED}) and is left out of an overlap.</li>
 *   <li>A multipart entity is hit once, by its nearest part; {@link #living} and {@link #kinds} ask of the whole.</li>
 *   <li>A {@link CgHitbox#hittable() hittable} hitbox is met as an entity is, standing for its owner: a shield stops a
 *       ray before the player holding it. {@link #noHitboxes} passes through them.</li>
 *   <li>A host world's queries are drawn under {@code crystalgraphics.collision.show} and F3+B.</li>
 * </ul>
 */
public final class CgQuery {

    static final int OVERLAP = 0, CAST = 1, RAY = 2;
    private static final int MAX_EXCEPT = 8;
    private static final int ALL_KINDS = -1;
    private static final int TARGETS = CgEntityQuery.ALIVE | CgEntityQuery.PICKABLE;
    /** How far a block's shape reaches into the cell above: fences and walls stand 1.5 high. */
    private static final double TALL_SHAPE = 0.5;

    private static volatile @Nullable Watcher watcher;

    private final CgCollisionWorld.PerThread thread;
    private CgCollisionWorld world;
    private final CgPose pose = new CgPose();
    private final CgAABB queryBox = new CgAABB(), start = new CgAABB(), swept = new CgAABB(), target = new CgAABB();
    private final CgCastHit cast = new CgCastHit();
    private final CgHits hits = new CgHits();
    private final CgHit nearestBlock = new CgHit(), probe = new CgHit();
    private final CgCells cells = new CgCells();
    private final Blocks blocks = new Blocks();
    private final Entities entities = new Entities();
    private final int[] except = new int[MAX_EXCEPT];
    private float[] boxes = new float[6 * 8];

    private int mode;
    /** Null for a ray, and for an overlap of {@link #queryBox}. */
    private CgShape shape;
    private boolean boxQuery, along;
    private double dirX, dirY, dirZ, length;
    private boolean entitiesOn, hitboxesOn, blocksOn, fluidsOn, borderOn, visible, exposure, items;
    /** The hitbox asking, which never meets itself, and whose queries the show leaves out: it draws its parts. */
    private CgHitbox self;
    private final MeetLive meetLive = new MeetLive();
    private final CgAABB targetBox = new CgAABB();
    private int require, forbid, wholeRequire, kinds, excepts, source, as, max;
    private final double[] border = new double[4];
    /** The cells beyond the border, while a run meets it: x below {@code borderMinX} or from {@code borderMaxX} on. */
    private boolean borderActive;
    private int borderMinX, borderMinZ, borderMaxX, borderMaxZ;
    private IntPredicate where;
    private double inflate;
    private CgHitPart part;

    private double ox, oy, oz, invX, invY, invZ, entityLimit;
    /** The world's entities as this run reads them: {@link CgCollisionWorld#view}. */
    private CgEntityQuery view;
    private boolean stopAtFirst, done, partsSeen, blockFound;
    private int sourceRoot;

    CgQuery(CgCollisionWorld.PerThread thread) {
        this.thread = thread;
    }

    CgQuery start(CgCollisionWorld world, int mode, CgShape shape, CgAABBc box) {
        this.world = world;
        this.mode = mode;
        this.shape = shape;
        boxQuery = box != null;
        pose.identity();
        if (boxQuery) queryBox.set(box);
        along = false;
        dirX = dirY = dirZ = length = 0;
        entitiesOn = hitboxesOn = true;
        blocksOn = fluidsOn = borderOn = visible = exposure = items = false;
        require = TARGETS;
        forbid = CgEntityQuery.SPECTATOR;
        wholeRequire = 0;
        kinds = ALL_KINDS;
        excepts = 0;
        source = as = -1;
        max = Integer.MAX_VALUE;
        where = null;
        inflate = 0;
        part = null;
        return this;
    }

    // ---- placement

    public CgQuery at(double x, double y, double z) {
        if (boxQuery) throw new IllegalStateException("a box query is placed by its box");
        pose.at(x, y, z);
        return this;
    }

    /** Turns the shape's local +Z to look along a yaw and pitch in radians, as {@link CgPose#facing}. */
    public CgQuery facing(double yaw, double pitch) {
        turnable();
        pose.facing(yaw, pitch);
        return this;
    }

    public CgQuery rotation(Quaternionfc q) {
        turnable();
        pose.rotation(q);
        return this;
    }

    public CgQuery rotation(Quaterniondc q) {
        turnable();
        pose.rotation(q);
        return this;
    }

    /**
     * Which way a cast or ray goes, and how far in blocks; the direction need not be unit length.
     *
     * @throws IllegalArgumentException for a zero direction or a negative distance
     * @throws IllegalStateException on an overlap
     */
    public CgQuery along(double dx, double dy, double dz, double distance) {
        if (mode == OVERLAP) throw new IllegalStateException("an overlap does not move: cast(shape) does");
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(len > 0) || !(distance >= 0) || Double.isInfinite(distance)) {
            throw new IllegalArgumentException("along(" + dx + ", " + dy + ", " + dz + ", " + distance + ")");
        }
        dirX = dx / len;
        dirY = dy / len;
        dirZ = dz / len;
        length = distance;
        along = true;
        return this;
    }

    private void turnable() {
        if (mode == RAY) throw new IllegalStateException("a ray has no rotation");
        if (boxQuery) throw new IllegalStateException("a box query is axis-aligned");
    }

    // ---- what it may hit

    /** Blocks as well as entities, by their collision shapes. */
    public CgQuery blocksToo() {
        blocksOn = true;
        return this;
    }

    /** Blocks alone: no entity and no hittable hitbox. */
    public CgQuery blocksOnly() {
        blocksOn = true;
        entitiesOn = false;
        return this;
    }

    /** Passes through {@link CgHitbox#hittable() hittable} hitboxes, meeting their owners' bodies alone. */
    public CgQuery noHitboxes() {
        hitboxesOn = false;
        return this;
    }

    /** Fluids as boxes up to their surface, blocks included: a cast or ray stops at water. */
    public CgQuery fluids() {
        fluidsOn = blocksOn = true;
        return this;
    }

    /**
     * Never hits {@code id}, nor its parts; up to eight.
     *
     * @throws IllegalStateException for a ninth
     */
    public CgQuery except(int id) {
        if (excepts == MAX_EXCEPT) throw new IllegalStateException("except takes " + MAX_EXCEPT + " ids at most");
        except[excepts++] = id;
        return this;
    }

    /** Whose query it is: never hits them, their parts, or anyone riding with them, as Minecraft's projectiles. */
    public CgQuery source(int id) {
        source = id;
        return this;
    }

    /**
     * Whose body is moving: blocks answer as they show entity {@code id} where it stands now, as Minecraft's movement
     * asks them. Scaffolding holds up one standing on it, powder snow one in leather boots. Without it blocks answer as
     * to no one, as they do to particles. A {@link CgCollisionWorld#snapshot} always answers as to no one: what such a
     * block shows depends on where the entity is when it asks.
     */
    public CgQuery as(int id) {
        as = id;
        return this;
    }

    /**
     * The world border too, as Minecraft stops a moving entity at it: every block cell beyond it is solid, while the
     * query's origin is inside. Particles and projectiles pass it in Minecraft, so nothing meets it unasked.
     */
    public CgQuery border() {
        borderOn = true;
        return this;
    }

    /** Living entities only: a dragon's part by its dragon. */
    public CgQuery living() {
        wholeRequire |= CgEntityQuery.LIVING;
        return this;
    }

    /** Only what Minecraft stops other entities at, as it does at a block: boats, minecarts, shulkers. */
    public CgQuery solid() {
        wholeRequire |= CgEntityQuery.SOLID;
        return this;
    }

    /** Dropped items too, which Minecraft's projectiles pass through. */
    public CgQuery itemsToo() {
        items = true;
        return this;
    }

    /** Every entity, spectators and the dead included. */
    public CgQuery allEntities() {
        require = forbid = 0;
        return this;
    }

    /** Only these kinds: bits {@code 1 << CgEntityQuery.KIND_...}, asked of the whole entity. */
    public CgQuery kinds(int mask) {
        kinds = mask;
        return this;
    }

    /** Only entities {@code test} accepts: teams, PvP, anything the engine does not decide. */
    public CgQuery where(IntPredicate test) {
        where = test;
        return this;
    }

    /**
     * Grows every entity's box by {@code d} blocks a side: Minecraft's arrows use 0.3, aim assist more.
     *
     * @throws IllegalArgumentException for a negative or non-finite size
     */
    public CgQuery inflate(double d) {
        if (!(d >= 0) || Double.isInfinite(d)) throw new IllegalArgumentException("inflate(" + d + ")");
        inflate = d;
        return this;
    }

    /** At most the {@code n} nearest. */
    public CgQuery max(int n) {
        if (n < 1) throw new IllegalArgumentException("max(" + n + ")");
        max = n;
        return this;
    }

    /** Only entities the query's origin sees: the centre of their box, past no block. */
    public CgQuery visible() {
        visible = true;
        return this;
    }

    /** Fills each entity hit's {@link CgHit#exposure()}, as Minecraft's explosions sample it. */
    public CgQuery exposure() {
        exposure = true;
        return this;
    }

    /** Labels every hit with {@code part}: several queries' hits told apart in one list. */
    public CgQuery part(CgHitPart part) {
        this.part = part;
        return this;
    }

    // ---- watching

    /** Hears each query a host's world runs, as it ends, on the thread running it. */
    @FunctionalInterface
    public interface Watcher {
        /**
         * Every argument is valid during the call only.
         *
         * @param shape   null for a ray and for a box overlap
         * @param box     a box overlap's box, else null
         * @param length  how far a cast or ray went along its unit direction; 0 for an overlap
         * @param level   the host level it asked
         * @param nearest the nearest hit found, null for none
         */
        void ran(@Nullable CgShape shape, CgPose pose, @Nullable CgAABBc box, double dx, double dy, double dz,
                 double length, CgWorldQuery level, @Nullable CgHit nearest);
    }

    /**
     * Hands {@code watcher} every query a host's world runs from now on; null stops. One for the process: the
     * collision show is it. A hitbox's queries and an empty world's are never handed over.
     *
     * <pre>{@code
     * CgQuery.watch((shape, pose, box, dx, dy, dz, length, level, nearest) -> recorder.add(pose, nearest));
     * }</pre>
     */
    public static void watch(@Nullable Watcher watcher) {
        CgQuery.watcher = watcher;
    }

    // ---- ending it

    /** Hands every hit to {@code visitor}, nearest first; the hit is valid during the call. */
    public void forEach(Consumer<? super CgHit> visitor) {
        thread.depth++;
        try {
            run(false);
            for (int i = 0; i < hits.size(); i++) {
                CgHit h = hits.get(i);
                world.handed(h);
                visitor.accept(h);
            }
        } finally {
            thread.depth--;
        }
    }

    /** The nearest hit, copied into {@code out}; false, with {@code out} untouched, for none. */
    public boolean first(CgHit out) {
        thread.depth++;
        try {
            run(false);
            if (hits.isEmpty()) return false;
            out.set(hits.get(0));
            return true;
        } finally {
            thread.depth--;
        }
    }

    /** Whether anything is hit, stopping at the first. */
    public boolean any() {
        thread.depth++;
        try {
            run(true);
            return !hits.isEmpty();
        } finally {
            thread.depth--;
        }
    }

    /** How many hits. */
    public int count() {
        thread.depth++;
        try {
            run(false);
            return hits.size();
        } finally {
            thread.depth--;
        }
    }

    /** The hits, nearest first, into {@code out}, which is cleared first; how many. */
    public int into(CgHits out) {
        thread.depth++;
        try {
            run(false);
            out.clear();
            for (int i = 0; i < hits.size(); i++) out.add().set(hits.get(i));
            return out.size();
        } finally {
            thread.depth--;
        }
    }

    // ---- running it

    private void run(boolean stopAtFirst) {
        if (mode != OVERLAP && !along) throw new IllegalStateException("a cast or ray needs along(...)");
        this.stopAtFirst = stopAtFirst;
        done = partsSeen = blockFound = false;
        self = CgHitboxes.gathering();
        hits.clear();
        if (boxQuery) {
            ox = queryBox.centerX();
            oy = queryBox.centerY();
            oz = queryBox.centerZ();
            start.set(queryBox);
        } else {
            ox = pose.x;
            oy = pose.y;
            oz = pose.z;
            if (shape == null) start.set(ox, oy, oz, ox, oy, oz);
            else shape.bounds(pose, start);
        }
        double mx = dirX * length, my = dirY * length, mz = dirZ * length;
        invX = 1 / dirX;
        invY = 1 / dirY;
        invZ = 1 / dirZ;
        entityLimit = Double.POSITIVE_INFINITY;
        CgWorldQuery w = world.world;
        borderActive = borderOn && w.border(border) && ox > border[0] && ox < border[2] && oz > border[1]
                && oz < border[3];
        if (borderActive) {
            borderMinX = (int) Math.floor(border[0]);
            borderMinZ = (int) Math.floor(border[1]);
            borderMaxX = (int) Math.ceil(border[2]);
            borderMaxZ = (int) Math.ceil(border[3]);
        }
        if ((blocksOn || borderActive) && w.levelEpoch() != 0) {
            // a ray reads only the cells it crosses, as Minecraft's clip
            double low = mode == RAY ? start.minY : start.minY - TALL_SHAPE;
            cells.set(start.minX, low, start.minZ, start.maxX, start.maxY, start.maxZ, mx, my, mz)
                    .walk(w.minY(), w.maxY(), blocks);
            if (blockFound) {
                hits.add().set(nearestBlock);
                entityLimit = nearestBlock.distance;
            }
        }
        if (entitiesOn && !done) {
            view = world.view();
            sourceRoot = source < 0 ? -1 : view.rootVehicle(source);
            swept.set(start).union(start.minX + mx, start.minY + my, start.minZ + mz)
                    .union(start.maxX + mx, start.maxY + my, start.maxZ + mz).grow(inflate);
            view.within(swept.minX, swept.minY, swept.minZ, swept.maxX, swept.maxY, swept.maxZ, entities);
            if (hitboxesOn) hittables(swept);
        }
        hits.sort();
        hits.truncate(max);
        if (part != null) {
            for (int i = 0; i < hits.size(); i++) hits.get(i).part = part;
        }
        if (exposure) {
            for (int i = 0; i < hits.size(); i++) {
                CgHit h = hits.get(i);
                if (h.kind == CgHit.Kind.ENTITY) h.exposure = world.exposure(ox, oy, oz, h.entity);
            }
        }
        Watcher heard = watcher;
        if (heard != null && self == null && !world.frozen && world.world != CgWorldQuery.NONE) {
            heard.ran(shape, pose, boxQuery ? queryBox : null, dirX, dirY, dirZ, length, world.world,
                    hits.isEmpty() ? null : hits.get(0));
        }
    }

    /** The hittable hitboxes in this world whose parts' boxes touch {@code within}: a snapshot's frozen ones. */
    private void hittables(CgAABBc within) {
        if (done) return;
        if (world.frozen) {
            if (world.frozenHittables != null) world.frozenHittables.scan(this, within);
            return;
        }
        meetLive.within = within;
        CgHitboxes.hittables(meetLive, false);
        meetLive.within = null;
    }

    /** Each live hittable hitbox this world's level holds, its owner asked of the view once. */
    private final class MeetLive implements CgHitboxes.Hittables {
        CgAABBc within;
        private int owner, flags, kind, root;

        @Override
        public boolean wants(CgHitbox hitbox, CgCollisionWorld tickedIn, int owner) {
            if (done || hitbox == self || !world.sameLevel(tickedIn)) return false;
            this.owner = owner;
            flags = CgPartSet.flagsOf(view, owner);
            kind = view.kind(owner);
            root = view.rootVehicle(owner);
            return true;
        }

        @Override
        public void part(CgHitbox hitbox, @Nullable CgHitPart name, CgShape shape, CgPose pose) {
            if (!done && shape.bounds(pose, targetBox).intersects(within)) {
                meet(hitbox, name, shape, pose, owner, flags, kind, root);
            }
        }
    }

    /**
     * One part of a hittable hitbox, standing for {@code owner}: the filters an entity is asked are asked of the owner's
     * flags, kind and root vehicle, unless it has none ({@link CgPartSet#NOT_AN_ENTITY}). A hitbox is hit once, by its
     * nearest part.
     */
    void meet(CgHitbox hitbox, @Nullable CgHitPart name, CgShape s, CgPose p, int owner, int flags, int kind,
              int root) {
        if (done || hitbox == self || owner == source || excepted(owner)
                || sourceRoot >= 0 && root == sourceRoot) return;
        if (flags != CgPartSet.NOT_AN_ENTITY && ((flags & require) != require || (flags & forbid) != 0
                || (flags & wholeRequire) != wholeRequire || kinds != ALL_KINDS && (kinds & 1 << kind) == 0)) return;
        CgHit h = hits.add();
        if (!placedGeometry(s, p, h) || h.distance > entityLimit || where != null && !where.test(owner)
                || visible && !thread.sight.clear(world.world, ox, oy, oz, p.x, p.y, p.z)) {
            hits.removeLast();
            return;
        }
        h.entity = h.parent = owner;
        h.kind = CgHit.Kind.HITBOX;
        h.hitbox = hitbox;
        h.hitboxPart = name;
        for (int i = 0; i < hits.size() - 1; i++) {
            CgHit other = hits.get(i);
            if (other.hitbox != hitbox) continue;
            if (h.distance < other.distance) other.set(h);
            hits.removeLast();
            return;
        }
        if (stopAtFirst) done = true;
    }

    private boolean excepted(int id) {
        for (int i = 0; i < excepts; i++) {
            if (except[i] == id) return true;
        }
        return false;
    }

    /** Each entity in the swept bounds, from the host's walk. */
    private final class Entities implements IntConsumer {
        @Override
        public void accept(int id) {
            if (done || excepted(id)) return;
            CgEntityQuery e = view;
            int f = e.flags(id);
            if ((f & require) != require || (f & forbid) != 0) {
                if (!items || (f & CgEntityQuery.ALIVE) == 0 || e.kind(id) != CgEntityQuery.KIND_ITEM) return;
            }
            if (sourceRoot >= 0 && e.rootVehicle(id) == sourceRoot) return;
            if (!e.box(id, world.partialTick, target)) return;
            if (inflate > 0) target.grow(inflate);
            CgHit h = hits.add();
            if (!geometry(target, h) || h.distance > entityLimit || !wanted(e, id, f, h)) {
                hits.removeLast();
                return;
            }
            if (partsSeen) {
                // a multipart entity is hit once: keep its nearest part
                for (int i = 0; i < hits.size() - 1; i++) {
                    CgHit other = hits.get(i);
                    if (other.parent != h.parent) continue;
                    if (h.distance < other.distance) other.set(h);
                    hits.removeLast();
                    return;
                }
            }
            if (stopAtFirst) done = true;
        }

        /** The filters asked of the whole, and the user's, once the shape has met it; fills the hit's ids. */
        private boolean wanted(CgEntityQuery e, int id, int f, CgHit h) {
            int parent = e.parent(id);
            if (parent < 0) parent = id;
            if (parent != id && (parent == source || excepted(parent))) return false;
            int whole = parent == id ? f : e.flags(parent);
            if ((whole & wholeRequire) != wholeRequire) return false;
            if (kinds != ALL_KINDS && (kinds & 1 << e.kind(parent)) == 0) return false;
            if (where != null && !where.test(id)) return false;
            if (visible && !thread.sight.clear(world.world, ox, oy, oz, target.centerX(), target.centerY(), target.centerZ())) return false;
            h.entity = id;
            h.parent = parent;
            h.kind = CgHit.Kind.ENTITY;
            if (parent != id) partsSeen = true;
            return true;
        }
    }

    /** Each block cell the shape covers or sweeps, nearest first. */
    private final class Blocks implements CgCells.Visitor {
        @Override
        public boolean cell(int x, int y, int z) {
            CgWorldQuery w = world.world;
            if (borderActive && (x < borderMinX || x >= borderMaxX || z < borderMinZ || z >= borderMaxZ)) {
                block(x, y, z, x, y, z, x + 1, y + 1, z + 1, CgHit.Kind.BORDER, CgWorldQuery.FLUID_NONE);
                return !done;
            }
            if (!blocksOn) return !done;
            if (!w.loaded(x, z)) {
                if (mode != OVERLAP) block(x, y, z, x, y, z, x + 1, y + 1, z + 1, CgHit.Kind.UNLOADED, CgWorldQuery.FLUID_NONE);
                return !done;
            }
            int n = w.collisionBoxes(x, y, z, as, boxes);
            if (n * 6 > boxes.length) {
                boxes = new float[n * 6];
                n = w.collisionBoxes(x, y, z, as, boxes);
            }
            for (int i = 0; i < n * 6; i += 6) {
                block(x, y, z, x + boxes[i], y + boxes[i + 1], z + boxes[i + 2], x + boxes[i + 3], y + boxes[i + 4],
                        z + boxes[i + 5], CgHit.Kind.BLOCK, CgWorldQuery.FLUID_NONE);
            }
            if (fluidsOn) {
                float top = w.fluidHeight(x, y, z);
                if (!Float.isNaN(top)) block(x, y, z, x, y, z, x + 1, y + top, z + 1, CgHit.Kind.FLUID, w.fluidKind(x, y, z));
            }
            return !done;
        }

        @Override
        public double limit() {
            return blockFound ? nearestBlock.fraction : Double.POSITIVE_INFINITY;
        }

        private void block(int cx, int cy, int cz, double minX, double minY, double minZ, double maxX, double maxY,
                           double maxZ, CgHit.Kind kind, int fluid) {
            target.set(minX, minY, minZ, maxX, maxY, maxZ);
            if (mode == OVERLAP) {
                CgHit h = hits.add();
                if (!geometry(target, h)) {
                    hits.removeLast();
                    return;
                }
                fill(h, cx, cy, cz, kind, fluid);
                if (hits.size() > 1) {
                    // a block is hit once: its boxes come one after another
                    CgHit last = hits.get(hits.size() - 2);
                    if (last.kind.isTerrain() && last.blockX == cx && last.blockY == cy && last.blockZ == cz
                            && last.fluid == fluid) {
                        if (h.distance < last.distance) last.set(h);
                        hits.removeLast();
                    }
                }
                if (stopAtFirst) done = true;
                return;
            }
            probe.reset();
            if (!geometry(target, probe) || blockFound && probe.distance >= nearestBlock.distance) return;
            nearestBlock.set(probe);
            fill(nearestBlock, cx, cy, cz, kind, fluid);
            blockFound = true;
            if (stopAtFirst) done = true;
        }

        private void fill(CgHit h, int cx, int cy, int cz, CgHit.Kind kind, int fluid) {
            h.kind = kind;
            h.blockX = cx;
            h.blockY = cy;
            h.blockZ = cz;
            h.fluid = fluid;
        }
    }

    // ---- geometry

    /** Whether the query meets box {@code b}, filling the hit's point, normal, distance and fraction. */
    private boolean geometry(CgAABB b, CgHit h) {
        switch (mode) {
            case OVERLAP: {
                if (boxQuery ? !queryBox.intersects(b) : !CgShapes.overlaps(shape, pose, b)) return false;
                return pointHit(h, clamp(ox, b.minX, b.maxX), clamp(oy, b.minY, b.maxY), clamp(oz, b.minZ, b.maxZ),
                        b.centerX(), b.centerY(), b.centerZ());
            }
            case CAST: {
                if (!CgShapes.cast(shape, pose, dirX * length, dirY * length, dirZ * length, b, cast)) return false;
                castHit(h);
                return true;
            }
            default: {
                double t = b.rayHit(ox, oy, oz, invX, invY, invZ, length);
                if (t == Double.POSITIVE_INFINITY) return false;
                h.x = ox + dirX * t;
                h.y = oy + dirY * t;
                h.z = oz + dirZ * t;
                h.distance = t;
                h.fraction = length == 0 ? 0 : t / length;
                if (t == 0) {
                    h.normalX = dirX;
                    h.normalY = dirY;
                    h.normalZ = dirZ;
                    return true;
                }
                // the face it entered by: the slab entered last
                double tx = dirX == 0 ? Double.NEGATIVE_INFINITY : ((dirX > 0 ? b.minX : b.maxX) - ox) * invX;
                double ty = dirY == 0 ? Double.NEGATIVE_INFINITY : ((dirY > 0 ? b.minY : b.maxY) - oy) * invY;
                double tz = dirZ == 0 ? Double.NEGATIVE_INFINITY : ((dirZ > 0 ? b.minZ : b.maxZ) - oz) * invZ;
                h.normalX = tx >= ty && tx >= tz ? Math.signum(dirX) : 0;
                h.normalY = h.normalX == 0 && ty >= tz ? Math.signum(dirY) : 0;
                h.normalZ = h.normalX == 0 && h.normalY == 0 ? Math.signum(dirZ) : 0;
                return true;
            }
        }
    }

    /** As {@link #geometry}, against a placed shape: an overlap's point is where the line to its origin meets it. */
    private boolean placedGeometry(CgShape s, CgPose p, CgHit h) {
        switch (mode) {
            case OVERLAP: {
                if (boxQuery ? !CgShapes.overlaps(s, p, queryBox) : !CgShapes.overlaps(shape, pose, s, p)) return false;
                double dx = p.x - ox, dy = p.y - oy, dz = p.z - oz;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0 && CgShapes.castRay(s, p, ox, oy, oz, dx, dy, dz, len, cast)) {
                    return pointHit(h, cast.point.x, cast.point.y, cast.point.z, p.x, p.y, p.z);
                }
                return pointHit(h, ox, oy, oz, p.x, p.y, p.z);
            }
            case CAST: {
                if (!CgShapes.cast(shape, pose, dirX * length, dirY * length, dirZ * length, s, p, cast)) return false;
                castHit(h);
                return true;
            }
            default: {
                if (!CgShapes.castRay(s, p, ox, oy, oz, dirX, dirY, dirZ, length, cast)) return false;
                castHit(h);
                return true;
            }
        }
    }

    /** An overlap's hit at a point of the target; the normal toward its centre when the origin is inside. */
    private boolean pointHit(CgHit h, double px, double py, double pz, double cx, double cy, double cz) {
        h.x = px;
        h.y = py;
        h.z = pz;
        double dx = px - ox, dy = py - oy, dz = pz - oz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        h.distance = d;
        h.fraction = 0;
        if (d == 0) {
            dx = cx - ox;
            dy = cy - oy;
            dz = cz - oz;
            d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        if (d == 0) {
            h.normalX = h.normalY = h.normalZ = 0;
        } else {
            h.normalX = dx / d;
            h.normalY = dy / d;
            h.normalZ = dz / d;
        }
        return true;
    }

    /** From {@link #cast}, whose normal faces the query: the hit's points away from it. */
    private void castHit(CgHit h) {
        h.x = cast.point.x;
        h.y = cast.point.y;
        h.z = cast.point.z;
        h.normalX = -cast.normal.x;
        h.normalY = -cast.normal.y;
        h.normalZ = -cast.normal.z;
        h.distance = cast.distance;
        h.fraction = cast.fraction;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
