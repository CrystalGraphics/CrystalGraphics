package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.collision.query.CgCollisionWorld;
import com.crystalgraphics.collision.query.CgHit;
import com.crystalgraphics.collision.query.CgHits;
import com.crystalgraphics.collision.query.CgQuery;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgResizable;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.world.CgEntityAttachments;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * A volume that lives over time and hits what it reaches: a shockwave running along the ground, a sword's swing, a
 * trigger zone. Each tick it tests everything it swept since the last, so nothing fast slips past it, and its rules
 * decide which of those touches are hits.
 *
 * <pre>{@code
 * // a front 1.2 blocks thick runs 12 blocks out in 12 ticks, each enemy hit once as it arrives
 * CgHitbox.of(CgRing.front(1.2, 0.6)).at(x, y, z).radius(0, 12, 12)
 *         .except(caster).living().exposure()
 *         .onHit(h -> damage(h.entity(), 8f * h.exposure()))
 *         .start(level);
 * }</pre>
 *
 * Parts on a followed entity, the first listed winning a tick both touch:
 *
 * <pre>{@code
 * static final CgHitPart TIP = CgHitPart.of("tip"), BLADE = CgHitPart.of("blade");
 *
 * CgHitbox.follow(player, CgEntityAttachments.RIGHT_HAND_FORWARD).facing(CgHitbox.Facing.LOOK).activeFor(5)
 *         .part(TIP, CgSphere.of(0.35), 0, 0, 1.1)
 *         .part(BLADE, CgCapsule.of(0.5, 0.3))
 *         .except(player)
 *         .onHit(h -> damage(h.entity(), h.part() == TIP ? 9f : 5f))
 *         .start(level);
 * }</pre>
 *
 * A zone, told who comes and goes until stopped:
 *
 * <pre>{@code
 * CgHitbox zone = CgHitbox.of(CgBox.of(4, 2, 4)).at(x, y, z)
 *         .onEnter(h -> buff(h.entity())).onExit(entity -> unbuff(entity))
 *         .start(level);
 * zone.stop();                                                  // exits for everyone still inside
 * }</pre>
 *
 * Ticked by its owner instead (a VFX effect, a mod's entity), on frames as well as ticks:
 *
 * <pre>{@code
 * private final CgHitbox slam = CgHitbox.of(SLAM).radius(0, 6, 8).onHit(this::hurt);
 *
 * slam.pose(pose).tick(world, deltaTicks);                      // never also start()ed
 * }</pre>
 *
 * <ul>
 *   <li>Each target is hit once by default; {@link #rehit} lets it be hit again, {@link #maxHits} caps that, and
 *       {@link #maxTargets} stops new targets once that many are hit. A multipart entity is one target.</li>
 *   <li>Time is game ticks: {@link #activeFrom}, {@link #activeFor}, the sizes and {@link #rehit}. Open-ended until
 *       {@link #stop} unless {@link #activeFor} is given.</li>
 *   <li>A started hitbox runs on the level's thread, ticked after the level's own tick; one started on a server runs
 *       only there. A followed entity gone stops it, and so does its level unloading.</li>
 *   <li>Configure it before it first ticks: parts and sizes are fixed from then. A hitbox runs once.</li>
 *   <li>The hit handed to a listener is valid during the call.</li>
 *   <li>Placement is from the followed entity's proportions on every side ({@link CgEntityAttachments#point(int,
 *       double[], double[])}), so server and client agree.</li>
 * </ul>
 */
public final class CgHitbox {

    /** Which way a hitbox turns. */
    public enum Facing {
        /** As set by {@link #facing(double, double)}, {@link #rotation} or {@link #pose}; unturned by default. */
        FIXED,
        /** Where the followed entity looks. */
        LOOK,
        /** Where the followed entity's body faces, level. */
        BODY
    }

    /**
     * What {@link #parts} hands each part.
     *
     * <pre>{@code
     * hitbox.parts((part, shape, pose) -> lines.write(shape, pose, sink));
     * }</pre>
     */
    @FunctionalInterface
    public interface PartSink {
        /** {@code name} is null for a hitbox of one unnamed part; {@code pose} is valid during the call. */
        void part(@Nullable CgHitPart name, CgShape shape, CgPose pose);
    }

    private static volatile @Nullable Consumer<? super CgHitbox> watcher;

    /** The most sub-steps one tick takes, however fast it turns or moves. */
    private static final int MAX_STEPS = 16;
    private static final int MAX_EXCEPT = 8;
    private static final int NO_SIZE = 0, RADIUS = 1, SCALE = 2;
    /** How far a part that is not convex moves per sub-step, when it has no width to go by. */
    private static final double DEFAULT_STEP = 0.5, MIN_STEP = 0.05;

    // ---- parts
    private CgHitPart[] names = new CgHitPart[2];
    private CgShape[] shapes = new CgShape[2];
    private CgResizable[] sized;
    private double[] offsets = new double[2 * 3];
    private int parts;

    // ---- placement
    private double x, y, z;
    private int entity = -1, point;
    private Facing facing = Facing.FIXED;
    private final Quaterniond fixed = new Quaterniond();

    // ---- size and time
    private int sizeKind = NO_SIZE;
    private double sizeFrom, sizeTo, sizeTicks;
    private CgKeyframes sizeCurve;
    private double activeFrom, activeFor = Double.POSITIVE_INFINITY, maxSweep = Math.toRadians(15);

    // ---- filters, applied to each query
    private final int[] except = new int[MAX_EXCEPT];
    private int excepts, source = -1, kinds = -1;
    private boolean living, items, anything, visible, exposure;
    private IntPredicate where;
    private double inflate;

    // ---- rules and listeners
    private double rehit = Double.POSITIVE_INFINITY;
    private int maxHits = Integer.MAX_VALUE, maxTargets = Integer.MAX_VALUE;
    private CgHitList hitList = new CgHitList();
    private Consumer<? super CgHit> onHit, onEnter;
    private IntConsumer onExit;

    // ---- running
    private CgCollisionWorld world;
    private boolean prepared, engine, stopped, hasPrev;
    /** Ticks as usual, keeping its hit list and who is inside, and tells no listener: a late joiner catching up. */
    private boolean silent;
    private int epoch;
    private double age, sizeNow;
    private double prevX, prevY, prevZ, nowX, nowY, nowZ;
    private final Quaterniond prevQ = new Quaterniond(), nowQ = new Quaterniond(), stepQ = new Quaterniond();
    private final CgPose stepPose = new CgPose(), drawPose = new CgPose();
    private final Vector3d scratch = new Vector3d();
    private final double[] entityPose = new double[CgEntityQuery.POSE_LENGTH];
    private final double[] at = new double[3];
    private final CgSwept swept = new CgSwept();
    private final CgHits partHits = new CgHits();
    private CgHit[] targets = new CgHit[0];
    private int targetCount;
    private int[] inside = new int[4];
    private boolean[] seen = new boolean[4];
    private int insideCount;

    private CgHitbox() {
    }

    /** A hitbox of one part, {@code shape}; its hits have no {@link CgHit#part()}. */
    public static CgHitbox of(CgShape shape) {
        return new CgHitbox().part(null, shape);
    }

    /** A hitbox of one part, {@code shape}, its hits naming {@code part}. */
    public static CgHitbox of(CgHitPart part, CgShape shape) {
        return new CgHitbox().part(part, shape);
    }

    /** A hitbox on point {@code point} of {@code entity} (a {@link CgEntityAttachments} point), its parts to add. */
    public static CgHitbox follow(int entity, int point) {
        return new CgHitbox().on(entity, point);
    }

    // ---- parts

    /** Adds a part at the hitbox's origin. */
    public CgHitbox part(CgHitPart name, CgShape shape) {
        return part(name, shape, 0, 0, 0);
    }

    /**
     * Adds a part offset in the hitbox's own space, +Z forward. Parts added earlier win a tick several touch one
     * target, and {@link CgHit#part()} names the one that did.
     */
    public CgHitbox part(CgHitPart name, CgShape shape, double ox, double oy, double oz) {
        configurable();
        if (parts == shapes.length) {
            names = Arrays.copyOf(names, parts * 2);
            shapes = Arrays.copyOf(shapes, parts * 2);
            offsets = Arrays.copyOf(offsets, parts * 2 * 3);
        }
        names[parts] = name;
        shapes[parts] = Objects.requireNonNull(shape, "shape");
        offsets[parts * 3] = ox;
        offsets[parts * 3 + 1] = oy;
        offsets[parts * 3 + 2] = oz;
        parts++;
        return this;
    }

    // ---- placement

    /** Fixed at a point in the world. */
    public CgHitbox at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        entity = -1;
        return this;
    }

    /** On point {@code point} of {@code entity}, following it each tick. */
    public CgHitbox on(int entity, int point) {
        this.entity = entity;
        this.point = point;
        return this;
    }

    /** Turns local +Z to a yaw and pitch in radians, as {@link CgPose#facing}. */
    public CgHitbox facing(double yaw, double pitch) {
        stepPose.facing(yaw, pitch).rotation(fixed);
        facing = Facing.FIXED;
        return this;
    }

    /** {@link Facing#LOOK} and {@link Facing#BODY} need a followed entity. */
    public CgHitbox facing(Facing facing) {
        this.facing = Objects.requireNonNull(facing, "facing");
        return this;
    }

    public CgHitbox rotation(Quaterniondc q) {
        fixed.set(q).normalize();
        facing = Facing.FIXED;
        return this;
    }

    public CgHitbox rotation(Quaternionfc q) {
        fixed.set(q.x(), q.y(), q.z(), q.w()).normalize();
        facing = Facing.FIXED;
        return this;
    }

    /** At {@code pose}'s position and rotation: what an owner computing the placement sets before each tick. */
    public CgHitbox pose(CgPose pose) {
        at(pose.x, pose.y, pose.z);
        pose.rotation(fixed);
        facing = Facing.FIXED;
        return this;
    }

    // ---- size over time

    /** A radius from {@code from} to {@code to} over its first {@code ticks} active ticks, then held. */
    public CgHitbox radius(double from, double to, double ticks) {
        return size(RADIUS, from, to, ticks);
    }

    /** A radius read from {@code curve} at the ticks it has been active. */
    public CgHitbox radius(CgKeyframes curve) {
        return size(RADIUS, Objects.requireNonNull(curve, "curve"));
    }

    /** Every length times a factor from {@code from} to {@code to} over its first {@code ticks} active ticks. */
    public CgHitbox scale(double from, double to, double ticks) {
        return size(SCALE, from, to, ticks);
    }

    /** Every length times a factor read from {@code curve} at the ticks it has been active. */
    public CgHitbox scale(CgKeyframes curve) {
        return size(SCALE, Objects.requireNonNull(curve, "curve"));
    }

    private CgHitbox size(int kind, double from, double to, double ticks) {
        configurable();
        if (!(from >= 0) || !(to >= 0) || !(ticks > 0)) {
            throw new IllegalArgumentException("size from " + from + " to " + to + " over " + ticks + " ticks");
        }
        sizeKind = kind;
        sizeFrom = from;
        sizeTo = to;
        sizeTicks = ticks;
        sizeCurve = null;
        return this;
    }

    private CgHitbox size(int kind, CgKeyframes curve) {
        configurable();
        sizeKind = kind;
        sizeCurve = curve;
        return this;
    }

    // ---- time

    /** Hits nothing for its first {@code ticks} ticks. */
    public CgHitbox activeFrom(double ticks) {
        if (!(ticks >= 0)) throw new IllegalArgumentException("activeFrom(" + ticks + ")");
        activeFrom = ticks;
        return this;
    }

    /** Stops after {@code ticks} active ticks. */
    public CgHitbox activeFor(double ticks) {
        if (!(ticks > 0)) throw new IllegalArgumentException("activeFor(" + ticks + ")");
        activeFor = ticks;
        return this;
    }

    /** The most it turns in one sub-step, in radians; a faster turn takes more steps. 15 degrees by default. */
    public CgHitbox maxSweep(double radians) {
        if (!(radians > 0)) throw new IllegalArgumentException("maxSweep(" + radians + ")");
        maxSweep = radians;
        return this;
    }

    // ---- filters, as CgQuery's

    /** Never hits {@code id}, nor its parts; up to eight. */
    public CgHitbox except(int id) {
        if (excepts == MAX_EXCEPT) throw new IllegalStateException("except takes " + MAX_EXCEPT + " ids at most");
        except[excepts++] = id;
        return this;
    }

    /** Whose hitbox it is: never hits them, their parts, or anyone riding with them. */
    public CgHitbox source(int id) {
        source = id;
        return this;
    }

    /** Living entities only. */
    public CgHitbox living() {
        living = true;
        return this;
    }

    /** Dropped items too. */
    public CgHitbox items() {
        items = true;
        return this;
    }

    /** Every entity, spectators and the dead included. */
    public CgHitbox anything() {
        anything = true;
        return this;
    }

    /** Only these kinds: bits {@code 1 << CgEntityQuery.KIND_...}. */
    public CgHitbox kinds(int mask) {
        kinds = mask;
        return this;
    }

    /** Only entities {@code test} accepts. */
    public CgHitbox where(IntPredicate test) {
        where = test;
        return this;
    }

    /** Grows every entity's box by {@code d} blocks a side. */
    public CgHitbox inflate(double d) {
        if (!(d >= 0) || Double.isInfinite(d)) throw new IllegalArgumentException("inflate(" + d + ")");
        inflate = d;
        return this;
    }

    /** Only entities its origin sees, past no block. */
    public CgHitbox visible() {
        visible = true;
        return this;
    }

    /** Fills each hit's {@link CgHit#exposure()}, as Minecraft's explosions sample it. */
    public CgHitbox exposure() {
        exposure = true;
        return this;
    }

    // ---- rules

    /** A target may be hit again {@code ticks} after its last hit. */
    public CgHitbox rehit(double ticks) {
        if (!(ticks >= 0)) throw new IllegalArgumentException("rehit(" + ticks + ")");
        rehit = ticks;
        return this;
    }

    /** At most {@code n} hits on one target, with {@link #rehit}. */
    public CgHitbox maxHits(int n) {
        if (n < 1) throw new IllegalArgumentException("maxHits(" + n + ")");
        maxHits = n;
        return this;
    }

    /** No new target once {@code n} have been hit, nearest first: a pierce count. */
    public CgHitbox maxTargets(int n) {
        if (n < 1) throw new IllegalArgumentException("maxTargets(" + n + ")");
        maxTargets = n;
        return this;
    }

    /** Keeps its hits in {@code list}, shared with other hitboxes that must hit each target once between them. */
    public CgHitbox hitList(CgHitList list) {
        hitList = Objects.requireNonNull(list, "list");
        return this;
    }

    // ---- listeners

    /** Each hit its rules allow, nearest first. */
    public CgHitbox onHit(Consumer<? super CgHit> listener) {
        onHit = listener;
        return this;
    }

    /** A target touching it that did not the tick before. */
    public CgHitbox onEnter(Consumer<? super CgHit> listener) {
        onEnter = listener;
        return this;
    }

    /** A target that touched it the tick before and no longer does, or was inside when it stopped. */
    public CgHitbox onExit(IntConsumer listener) {
        onExit = listener;
        return this;
    }

    // ---- running

    /** Starts it in {@code level} (a host level), ticked by the engine on the level's thread. */
    public CgHitbox start(Object level) {
        return start(CgCollisionWorld.of(level));
    }

    /** Starts it in {@code world}, ticked by the engine on this thread. */
    public CgHitbox start(CgCollisionWorld world) {
        if (prepared) throw new IllegalStateException("a hitbox runs once");
        this.world = Objects.requireNonNull(world, "world");
        prepare();
        engine = true;
        epoch = world.blocks().levelEpoch();
        CgHitboxes.add(this);
        return this;
    }

    /**
     * Advances it {@code ticks} game ticks in {@code world}, fractional for an owner ticking on frames, and hits what
     * it swept. For a hitbox its owner ticks: one {@link #start}ed ticks itself.
     */
    public void tick(CgCollisionWorld world, double ticks) {
        if (engine) throw new IllegalStateException("a started hitbox ticks itself");
        this.world = Objects.requireNonNull(world, "world");
        if (!prepared) prepare();
        advance(ticks);
    }

    /** Ends it; a zone's exits fire for everyone still inside. */
    public void stop() {
        if (stopped) return;
        stopped = true;
        prepared = true;
        while (insideCount > 0) {
            int gone = inside[--insideCount];
            if (onExit != null) onExit.accept(gone);
        }
    }

    public boolean stopped() {
        return stopped;
    }

    /** Ticks since it started. */
    public double age() {
        return age;
    }

    public CgHitList hitList() {
        return hitList;
    }

    /** Whether it was given a listener: an owner hearing its hits itself refuses one that was. */
    public boolean hasListeners() {
        return onHit != null || onEnter != null || onExit != null;
    }

    /** The world it last ticked in; null before its first tick or start. */
    public @Nullable CgCollisionWorld world() {
        return world;
    }

    /**
     * Each part as this tick tested it, sized as it is now; nothing while it is not active or once it stops. What a
     * debug view draws. On its thread.
     *
     * <pre>{@code
     * hitbox.parts((part, shape, pose) -> CgCollisionDebug.shape(shape, pose, 0xFF40FF60));
     * }</pre>
     */
    public void parts(PartSink out) {
        if (!hasPrev || stopped) return;
        for (int p = 0; p < parts; p++) {
            CgShape shape = shapes[p];
            if (sizeKind == RADIUS) shape = sized[p].radius(sizeNow);
            else if (sizeKind == SCALE) shape = sized[p].scale(sizeNow);
            partOrigin(nowX, nowY, nowZ, nowQ, offsets[p * 3], offsets[p * 3 + 1], offsets[p * 3 + 2]);
            drawPose.at(scratch.x, scratch.y, scratch.z).rotation((Quaterniondc) nowQ);
            out.part(names[p], shape, drawPose);
        }
    }

    /**
     * Hands {@code watcher} every hitbox each time it ticks, on the thread ticking it, after its hits; null stops. One
     * watcher for the process: {@code crystalgraphics.collision.show} is it.
     */
    public static void watch(@Nullable Consumer<? super CgHitbox> watcher) {
        CgHitbox.watcher = watcher;
    }

    // ---- for CgHitboxType, which builds one per side

    void silent(boolean silent) {
        this.silent = silent;
    }

    /** Ages it {@code ticks} without testing anything: the start of a late joiner's catch-up. */
    void skip(double ticks) {
        if (!prepared) prepare();
        age += ticks;
        hasPrev = false;
        if (age - activeFrom >= activeFor) stop();
    }

    int partCount() {
        return parts;
    }

    /** {@code part}'s index, by identity; -1 for none. */
    int partIndex(CgHitPart part) {
        for (int p = 0; p < parts; p++) {
            if (names[p] == part) return p;
        }
        return -1;
    }

    CgHitPart partName(int index) {
        return index >= 0 && index < parts ? names[index] : null;
    }

    boolean placed() {
        return entity >= 0;
    }

    /** The engine's tick: stops with its level. */
    void engineTick() {
        if (epoch != 0 && world.blocks().levelEpoch() != epoch) {
            stop();
            return;
        }
        advance(1);
    }

    private void prepare() {
        if (parts == 0) throw new IllegalStateException("a hitbox needs a part");
        if (facing != Facing.FIXED && entity < 0) throw new IllegalStateException(facing + " needs a followed entity");
        if (sizeKind != NO_SIZE) {
            sized = new CgResizable[parts];
            for (int p = 0; p < parts; p++) {
                sized[p] = CgResizable.of(shapes[p]);
                if (sizeKind == RADIUS && !sized[p].hasRadius()) {
                    throw new IllegalArgumentException(shapes[p] + " has no radius: scale it");
                }
            }
        }
        prepared = true;
    }

    private void configurable() {
        if (prepared) throw new IllegalStateException("parts and sizes are fixed once a hitbox runs");
    }

    private void advance(double ticks) {
        if (stopped || !(ticks > 0)) return;
        age += ticks;
        double active = age - activeFrom;
        if (!place()) {
            stop();
            return;
        }
        if (active <= 0) {
            hasPrev = false;
            return;
        }
        double now = sizeKind == NO_SIZE ? 0 : size(Math.min(active, activeFor));
        double before = sizeKind == NO_SIZE ? 0 : size(Math.max(0, active - ticks));
        sizeNow = now;
        gather(before, now);
        prevX = nowX;
        prevY = nowY;
        prevZ = nowZ;
        prevQ.set(nowQ);
        hasPrev = true;
        apply();
        Consumer<? super CgHitbox> w = watcher;
        if (w != null) w.accept(this);
        if (!stopped && active >= activeFor) stop();
    }

    /** Where it is this tick, into {@link #nowX} and {@link #nowQ}; false for a followed entity gone. */
    private boolean place() {
        if (entity < 0) {
            nowX = x;
            nowY = y;
            nowZ = z;
            nowQ.set(fixed);
            return true;
        }
        if (!world.entities().pose(entity, world.partialTick(), entityPose)) return false;
        CgEntityAttachments.point(point, entityPose, at);
        nowX = at[0];
        nowY = at[1];
        nowZ = at[2];
        if (facing == Facing.LOOK) {
            stepPose.facing(Math.toRadians(entityPose[CgEntityQuery.YAW]),
                    Math.toRadians(entityPose[CgEntityQuery.PITCH])).rotation(nowQ);
        } else if (facing == Facing.BODY) {
            stepPose.facing(Math.toRadians(entityPose[CgEntityQuery.BODY_YAW]), 0).rotation(nowQ);
        } else {
            nowQ.set(fixed);
        }
        return true;
    }

    private double size(double active) {
        if (sizeCurve != null) return Math.max(0, sizeCurve.at((float) active));
        double t = Math.min(1, active / sizeTicks);
        return sizeFrom + (sizeTo - sizeFrom) * t;
    }

    /** Every target its parts swept this tick into {@link #targets}, once each by the first part, nearest first. */
    private void gather(double before, double now) {
        targetCount = 0;
        int turnSteps = 1;
        if (hasPrev) {
            double dot = Math.abs(prevQ.x * nowQ.x + prevQ.y * nowQ.y + prevQ.z * nowQ.z + prevQ.w * nowQ.w);
            double angle = 2 * Math.acos(Math.min(1, dot));
            if (angle > maxSweep) turnSteps = (int) Math.ceil(angle / maxSweep);
        }
        for (int p = 0; p < parts; p++) {
            CgShape shape = shapeAt(p, before, now);
            double ox = offsets[p * 3], oy = offsets[p * 3 + 1], oz = offsets[p * 3 + 2];
            if (!hasPrev) {
                partOrigin(nowX, nowY, nowZ, nowQ, ox, oy, oz);
                query(p, shape, scratch.x, scratch.y, scratch.z, nowQ);
                continue;
            }
            partOrigin(prevX, prevY, prevZ, prevQ, ox, oy, oz);
            double lastX = scratch.x, lastY = scratch.y, lastZ = scratch.z;
            int steps = turnSteps;
            if (!shape.isConvex()) {
                partOrigin(nowX, nowY, nowZ, nowQ, ox, oy, oz);
                double dx = scratch.x - lastX, dy = scratch.y - lastY, dz = scratch.z - lastZ;
                double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
                steps = Math.max(steps, (int) Math.ceil(moved / step(shape)));
            }
            steps = Math.min(steps, MAX_STEPS);
            for (int k = 1; k <= steps; k++) {
                double t = (double) k / steps;
                prevQ.nlerp(nowQ, t, stepQ);
                partOrigin(prevX + (nowX - prevX) * t, prevY + (nowY - prevY) * t, prevZ + (nowZ - prevZ) * t, stepQ,
                        ox, oy, oz);
                double cx = scratch.x, cy = scratch.y, cz = scratch.z;
                if (shape.isConvex()) {
                    stepPose.rotation((Quaterniondc) stepQ).unrotate(cx - lastX, cy - lastY, cz - lastZ, scratch);
                    query(p, swept.set(shape, scratch.x, scratch.y, scratch.z), lastX, lastY, lastZ, stepQ);
                } else {
                    query(p, shape, cx, cy, cz, stepQ);
                }
                lastX = cx;
                lastY = cy;
                lastZ = cz;
            }
        }
        sortTargets();
    }

    private CgShape shapeAt(int p, double before, double now) {
        if (sizeKind == RADIUS) return sized[p].sweptRadius(before, now);
        if (sizeKind == SCALE) return sized[p].sweptScale(before, now);
        return shapes[p];
    }

    /** How far a part that is not convex may move per sub-step: half a shell's or ring's width. */
    private static double step(CgShape shape) {
        double width;
        if (shape instanceof CgShell) width = ((CgShell) shape).outer() - ((CgShell) shape).inner();
        else if (shape instanceof CgRing) width = ((CgRing) shape).outer() - ((CgRing) shape).inner();
        else return DEFAULT_STEP;
        return Math.max(MIN_STEP, width * 0.5);
    }

    /** A part's origin, offset in its hitbox's space, into {@link #scratch}. */
    private void partOrigin(double px, double py, double pz, Quaterniondc q, double ox, double oy, double oz) {
        stepPose.rotation(q).rotate(ox, oy, oz, scratch);
        scratch.add(px, py, pz);
    }

    private void query(int p, CgShape shape, double px, double py, double pz, Quaterniondc q) {
        CgQuery query = world.overlap(shape).at(px, py, pz).rotation(q).part(names[p]);
        for (int i = 0; i < excepts; i++) query.except(except[i]);
        if (source >= 0) query.source(source);
        if (anything) query.anything();
        if (living) query.living();
        if (items) query.items();
        if (kinds != -1) query.kinds(kinds);
        if (where != null) query.where(where);
        if (inflate > 0) query.inflate(inflate);
        if (visible) query.visible();
        if (exposure) query.exposure();
        int n = query.into(partHits);
        for (int i = 0; i < n; i++) {
            CgHit h = partHits.get(i);
            if (!hasTarget(h.parent())) target().set(h);
        }
    }

    private boolean hasTarget(int whole) {
        for (int i = 0; i < targetCount; i++) {
            if (targets[i].parent() == whole) return true;
        }
        return false;
    }

    private CgHit target() {
        if (targetCount == targets.length) {
            CgHit[] grown = Arrays.copyOf(targets, Math.max(8, targetCount * 2));
            for (int i = targetCount; i < grown.length; i++) grown[i] = new CgHit();
            targets = grown;
        }
        return targets[targetCount++];
    }

    /** Nearest first; a shell sort, which allocates nothing. */
    private void sortTargets() {
        for (int gap = targetCount / 2; gap > 0; gap = gap == 2 ? 1 : gap * 5 / 11) {
            for (int i = gap; i < targetCount; i++) {
                CgHit h = targets[i];
                int j = i;
                for (; j >= gap && targets[j - gap].distance() > h.distance(); j -= gap) targets[j] = targets[j - gap];
                targets[j] = h;
            }
        }
    }

    /** The tick's targets through the rules, then the zone's comings and goings. */
    private void apply() {
        boolean zone = onEnter != null || onExit != null;
        if (zone) {
            for (int i = 0; i < insideCount; i++) seen[i] = false;
        }
        for (int i = 0; i < targetCount && !stopped; i++) {
            CgHit h = targets[i];
            int whole = h.parent();
            if (zone && enters(whole) && onEnter != null && !silent) onEnter.accept(h);
            if (stopped || onHit == null || !allowed(whole)) continue;
            hitList.record(whole, age);
            if (!silent) onHit.accept(h);
        }
        if (!zone || stopped) return;
        for (int i = insideCount - 1; i >= 0; i--) {
            if (seen[i]) continue;
            int gone = inside[i];
            inside[i] = inside[--insideCount];
            seen[i] = seen[insideCount];
            if (onExit != null && !silent) onExit.accept(gone);
            if (stopped) return;
        }
    }

    /** Marks {@code whole} inside this tick; whether it was outside before. */
    private boolean enters(int whole) {
        for (int i = 0; i < insideCount; i++) {
            if (inside[i] == whole) {
                seen[i] = true;
                return false;
            }
        }
        if (insideCount == inside.length) {
            inside = Arrays.copyOf(inside, insideCount * 2);
            seen = Arrays.copyOf(seen, insideCount * 2);
        }
        inside[insideCount] = whole;
        seen[insideCount++] = true;
        return true;
    }

    private boolean allowed(int whole) {
        int count = hitList.count(whole);
        if (count == 0) return hitList.size() < maxTargets;
        return count < maxHits && age - hitList.lastHit(whole) >= rehit;
    }

    @Override
    public String toString() {
        return "CgHitbox[" + parts + " parts, age " + age + (stopped ? ", stopped" : "") + "]";
    }
}
