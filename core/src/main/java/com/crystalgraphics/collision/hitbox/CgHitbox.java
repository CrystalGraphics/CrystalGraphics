package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitClaim;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.world.CgCharacterClock;
import com.crystalgraphics.collision.hit.CgHits;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.primitive.CgResizable;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.world.CgEntityAttachments;
import com.crystalgraphics.world.CgEntityQueries;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A volume that lives over time and hits what it reaches: a shockwave running along the ground, a sword's swing, a
 * trigger zone. Each tick it tests everything it swept since the last, so nothing fast slips past it, and its rules
 * decide which of those touches are hits. What it may hit is said as a {@link CgCollisionQuery} says it: each tick's
 * queries are configured by {@link #query}.
 *
 * <pre>{@code
 * // a front 1.2 blocks thick runs 12 blocks out in 12 ticks, each enemy hit once as it arrives
 * CgHitbox.of(CgRing.front(1.2, 0.6)).at(x, y, z).radius(0, 12, 12).source(caster)
 *         .query(q -> q.living().exposure())
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
 *         .onHit(h -> damage(h.entity(), h.part() == TIP ? 9f : 5f))     // never the player it follows
 *         .start(level);
 * }</pre>
 *
 * A blow aimed where its attacker looked as it began, from a point of the body's own proportions, drawn under F3+B in
 * what its hits do:
 *
 * <pre>{@code
 * static final int STRIKE = CgEntityAttachments.declareAtHeight("mymod:strike", 0.7);
 *
 * CgHitbox.follow(attacker, STRIKE).facingOnce(CgHitbox.Facing.LOOK_LEVEL).activeFor(1).maxTargets(3)
 *         .part(BLOW, CgBox.of(0.75, 0.75, 1.5), 0, 0, 1.5)          // 3 deep, from the strike point forward
 *         .query(q -> q.attackable().visible().where(this::canHit))  // seen from the attacker's eyes
 *         .showColour(0xFF4040)
 *         .onHit(this::land)
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
 * A shield its owner holds up, which hits nothing itself and stops what reaches it first: every query meets it,
 * other hitboxes and rays included, as a {@link CgHit.Kind#HITBOX} hit standing for its owner.
 *
 * <pre>{@code
 * static final CgHitPart SHIELD = CgHitPart.of("shield");
 *
 * CgHitbox.follow(player, CgEntityAttachments.HANDS_FORWARD).facing(CgHitbox.Facing.LOOK)
 *         .part(SHIELD, CgBox.of(1.2, 1.4, 0.2)).hittable().passive()
 *         .start(level);
 *
 * // a sword's swing meets the shield first, so the player behind it counts as hit and is not
 * swing.onHit(h -> { if (h.kind() == CgHit.Kind.HITBOX) parried(h.entity()); else damage(h.entity(), 6f); });
 * }</pre>
 *
 * <ul>
 *   <li>A followed hitbox's {@link #source} is the entity it follows unless given, so it never hits them.</li>
 *   <li>Each target is hit once by default; {@link #rehit} lets it be hit again, {@link #maxHits} caps that, and
 *       {@link #maxTargets} stops new targets once that many are hit. A multipart entity is one target.</li>
 *   <li>Time is game ticks: {@link #activeFrom}, {@link #activeFor}, the sizes and {@link #rehit}. Open-ended until
 *       {@link #stop} unless {@link #activeFor} is given.</li>
 *   <li>A started hitbox runs on the level's thread, ticked after the level's own tick; one started on a server runs
 *       only there. A followed entity gone stops it, and so does its level unloading.</li>
 *   <li>Configure it before it first ticks: parts and sizes are fixed from then. A hitbox runs once.</li>
 *   <li>The hit handed to a listener is valid during the call. Entities and hittable hitboxes are its targets; a
 *       block its query meets is not.</li>
 *   <li>A {@link #hittable} one is met by queries on the thread it ticks on, against its level, while it is active and
 *       until it stops: an owner ticking one must stop it.</li>
 *   <li>A followed point is where the client draws it ({@link CgEntityAttachments#mark}, then its providers), so a
 *       claim lands where the player saw the fist; a server places it from the entity's proportions
 *       ({@link CgEntityAttachments#point(int, double[], double[])}), and a claim's padding covers the gap.</li>
 * </ul>
 */
public final class CgHitbox {

    /** Which way a hitbox turns. */
    public enum Facing {
        /** As set by {@link #facing(double, double)}, {@link #rotation} or {@link #pose}; unturned by default. */
        FIXED,
        /** Where the followed entity looks. */
        LOOK,
        /** Where the followed entity looks, held level: a cleave that ignores the pitch. */
        LOOK_LEVEL,
        /** Where the followed entity's body faces, level. */
        BODY
    }

    /**
     * What {@link #parts} hands each part.
     *
     * <pre>{@code
     * hitbox.parts((part, shape, pose) -> CgCollisionDebug.shape(shape, pose, 0xFF40FF60));
     * }</pre>
     */
    @FunctionalInterface
    public interface PartSink {
        /** {@code name} is null for a hitbox of one unnamed part; {@code pose} is valid during the call. */
        void part(@Nullable CgHitPart name, CgShape shape, CgPose pose);
    }

    /** The most sub-steps one tick takes, however fast it turns or moves. */
    private static final int MAX_STEPS = 16;
    /** The most it turns in one sub-step; a faster turn takes more steps. */
    private static final double MAX_TURN = Math.toRadians(15);
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
    /** {@link #facingOnce}: the facing taken at the first placement, held in {@link #fixed} once {@code held}. */
    private boolean once, held;
    private final Quaterniond fixed = new Quaterniond();
    private int showColour;
    private double showLinger = -1;
    private float showFill = -1;

    // ---- size and time
    private int sizeKind = NO_SIZE;
    private double sizeFrom, sizeTo, sizeTicks;
    private CgKeyframes sizeCurve;
    private double activeFrom, activeFor = Double.POSITIVE_INFINITY;

    // ---- what it may hit
    private int source = -1;
    private Consumer<? super CgCollisionQuery> query;

    // ---- rules and listeners
    private double rehit = Double.POSITIVE_INFINITY;
    private int maxHits = Integer.MAX_VALUE, maxTargets = Integer.MAX_VALUE;
    private CgHitList hitList = new CgHitList();
    private Consumer<? super CgHit> onHit, onEnter;
    private IntConsumer onExit;
    /** Hears the hits a silent tick keeps from {@link #onHit}: a claimed hitbox's owner claims them still. */
    @Nullable
    private Consumer<? super CgHit> silentHits;
    private final CgAABB partBox = new CgAABB();

    // ---- hittable
    private boolean hittable, passive;

    // ---- running
    private CgCollisionWorld world;
    private boolean prepared, engine, stopped, hasPrev;
    /** Whether {@link #nowX}, {@link #nowQ} and {@link #sizeNow} place it now: active, and placed at least once. */
    private boolean posed;
    /** Ticks as usual, keeping its hit list and who is inside, and tells no listener: a late joiner catching up. */
    private boolean silent;
    private int epoch;
    private double age, sizeNow;
    /** The character clock an engine-ticked hitbox ages by, and the clock ticks it has spent. */
    private CgCharacterClock clock;
    private long clockTicks;
    private double prevX, prevY, prevZ, nowX, nowY, nowZ;
    private final Quaterniond prevQ = new Quaterniond(), nowQ = new Quaterniond(), stepQ = new Quaterniond();
    private final CgPose stepPose = new CgPose(), drawPose = new CgPose();
    private final Vector3d scratch = new Vector3d();
    private final double[] entityPose = new double[CgEntityQuery.POSE_LENGTH];
    private final double[] at = new double[3];
    // where a frame draws it: parts(partialTick, out)
    private final double[] frameEntity = new double[CgEntityQuery.POSE_LENGTH], frameAt = new double[3];
    private final Quaterniond frameQ = new Quaterniond();
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

    /**
     * A hitbox on point {@code point} of {@code entity} (a {@link CgEntityAttachments} point), its parts to add. Its
     * {@link #source} is that entity unless given: a hitbox on someone never hits them.
     */
    public static CgHitbox follow(int entity, int point) {
        return new CgHitbox().on(entity, point);
    }

    /** As {@link #follow(int, int)}, on Minecraft's entity. */
    public static CgHitbox follow(Object entity, int point) {
        return follow(CgEntityQueries.requireId(entity), point);
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

    /** Turns local +Z to a yaw and pitch in radians, as {@link CgPose#facing}. */
    public CgHitbox facing(double yaw, double pitch) {
        stepPose.facing(yaw, pitch).getRotation(fixed);
        return fixed();
    }

    /** Turns with the followed entity each tick; any but {@link Facing#FIXED} needs one. */
    public CgHitbox facing(Facing facing) {
        this.facing = Objects.requireNonNull(facing, "facing");
        once = false;
        return this;
    }

    /**
     * Turns as {@link #facing(Facing)} does at its first tick (its start, for a started one), then holds that: a
     * swing aimed where its attacker looked as it began, which turning after cannot drag round.
     *
     * <pre>{@code
     * CgHitbox.follow(attacker, STRIKE).facingOnce(CgHitbox.Facing.LOOK_LEVEL).part(BLOW, SWING, 0, 0, 1.5)...;
     * }</pre>
     *
     * @throws IllegalArgumentException for {@link Facing#FIXED}, which is held already
     */
    public CgHitbox facingOnce(Facing facing) {
        if (facing == Facing.FIXED) throw new IllegalArgumentException("facingOnce(FIXED): a fixed facing is held already");
        facing(facing);
        once = true;
        return this;
    }

    public CgHitbox rotation(Quaterniondc q) {
        fixed.set(q).normalize();
        return fixed();
    }

    public CgHitbox rotation(Quaternionfc q) {
        fixed.set(q.x(), q.y(), q.z(), q.w()).normalize();
        return fixed();
    }

    /** At {@code pose}'s position and rotation: what an owner computing the placement sets before each tick. */
    public CgHitbox pose(CgPose pose) {
        at(pose.x, pose.y, pose.z);
        pose.getRotation(fixed);
        return fixed();
    }

    private CgHitbox fixed() {
        facing = Facing.FIXED;
        once = false;
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

    // ---- what it may hit

    /**
     * Whose hitbox it is: never hits them, their parts, or anyone riding with them; a hittable one stands for them. The
     * entity it {@link #follow follows}, unless given.
     */
    public CgHitbox source(int id) {
        source = id;
        return this;
    }

    /** As {@link #source(int)}, by Minecraft's entity. */
    public CgHitbox source(Object entity) {
        return source(CgEntityQueries.requireId(entity));
    }

    /**
     * Configures each query it runs, after placing it and naming its {@link #source}: what it may hit, as
     * {@link CgCollisionQuery} says it. Called on every sub-step, so it must only configure.
     *
     * <pre>{@code
     * hitbox.query(q -> q.except(ally).living().exposure());
     * hitbox.query(q -> q.kinds(1 << CgEntityQuery.KIND_MONSTER).inflate(0.3));
     * }</pre>
     */
    public CgHitbox query(Consumer<? super CgCollisionQuery> configure) {
        query = Objects.requireNonNull(configure, "configure");
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

    // ---- hittable

    /**
     * Makes it met by queries, other hitboxes' included, while it is active: a shield, a ward, a weak spot. Each such
     * hit is a {@link CgHit.Kind#HITBOX} whose {@link CgHit#entity()} is its owner (the entity it follows, else its
     * {@link #source}), so a hitbox meeting it first counts its owner as hit.
     *
     * <ul>
     *   <li>It needs an owner: its first tick throws without one.</li>
     *   <li>Its owner's queries never meet it, nor those excepting its owner. Filters a query asks of an entity
     *       ({@code living()}, {@code kinds}, {@code where}) are asked of its owner; {@code inflate} does not grow it.</li>
     *   <li>A query asking {@code noHitboxes()} or {@code blocksOnly()} passes through it.</li>
     *   <li>A snapshot holds it as it was when taken.</li>
     * </ul>
     */
    public CgHitbox hittable() {
        configurable();
        hittable = true;
        return this;
    }

    /** Hits nothing: it only moves and sizes, for queries to meet as {@link #hittable}. Its listeners never hear. */
    public CgHitbox passive() {
        configurable();
        passive = true;
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

    // ---- seeing it

    /**
     * Draws it in {@code argb} under the collision show (F3+B), on every side, in place of the side's own green or
     * orange: what its hits mean, at a glance. An alpha of 0 is taken as opaque, so {@code 0xFF0000} is red.
     *
     * <pre>{@code
     * swing.showColour(unblockable ? 0xFF2020 : 0x20A0FF);
     * }</pre>
     */
    public CgHitbox showColour(int argb) {
        showColour = argb >>> 24 == 0 ? argb | 0xFF000000 : argb;
        return this;
    }

    /** Its {@link #showColour(int)}; 0 for the side's own. */
    public int showColour() {
        return showColour;
    }

    /**
     * How many game ticks the collision show keeps drawing it once it stops, fading out, over
     * {@code crystalgraphics.collision.linger} (none by default). For a hitbox too short to see; a lingering one is
     * no longer hitting anything, which the fade is there to say.
     *
     * <pre>{@code
     * CgHitbox.follow(player, STRIKE).activeFor(1).showLinger(6)...   // a one-tick blow, seen for 0.3 s after
     * }</pre>
     */
    public CgHitbox showLinger(double ticks) {
        if (!(ticks >= 0) || Double.isInfinite(ticks)) throw new IllegalArgumentException("linger ticks: " + ticks);
        showLinger = ticks;
        return this;
    }

    /** Its {@link #showLinger(double)} in ticks; negative for the show's own setting. */
    public double showLinger() {
        return showLinger;
    }

    /**
     * How strongly the collision show fills it in, for every player whatever their style: the share of its colour's
     * alpha its faces take, 0 for its edges alone. Unset, it follows each player's style: edges alone by default, a
     * quarter when they draw transparent.
     *
     * <pre>{@code
     * slam.showFill(0.25f);                                    // filled for everyone, as transparent fills
     * probe.showFill(0);                                       // edges only, even for a player drawing transparent
     * }</pre>
     */
    public CgHitbox showFill(float alpha) {
        if (!(alpha >= 0 && alpha <= 1)) throw new IllegalArgumentException("fill alpha outside 0..1: " + alpha);
        showFill = alpha;
        return this;
    }

    /** Its {@link #showFill(float)}; negative for the show's own. */
    public float showFill() {
        return showFill;
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
        if (hittable && activeFrom == 0) {
            // met from the start, not from its first engine tick
            posed = place();
            if (posed && sizeKind != NO_SIZE) sizeNow = size(0);
        }
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
        if (hasPrev && world != null) CgHitboxes.stopped(this, world);
    }

    public boolean stopped() {
        return stopped;
    }

    /**
     * Ages by a character's own game ticks rather than the level's: an engine-ticked hitbox holds through the
     * character's hitstop and a late-heard freeze, and its age agrees on every side. An owner ticking it passes
     * {@link CgCharacterClock#ticks} deltas itself.
     *
     * <pre>{@code
     * CgHitbox.follow(attacker, STRIKE).clock(CgCharacterClock.of(attacker)).activeFor(5).part(BLOW, SWING).start(level);
     * }</pre>
     */
    public CgHitbox clock(CgCharacterClock clock) {
        configurable();
        this.clock = clock;
        clockTicks = clock.ticks();
        return this;
    }

    /** Ticks since it started. */
    public double age() {
        return age;
    }

    /** Whether it was given a listener: an owner hearing its hits itself refuses one that was. */
    public boolean hasListeners() {
        return onHit != null || onEnter != null || onExit != null;
    }

    /** The world it last ticked in; null before its first tick or start. */
    @Nullable CgCollisionWorld world() {
        return world;
    }

    /**
     * Each part where its last tick placed it, sized as it was then: the last once it stops, and nothing before its
     * first active tick: what a debug view draws, or a mod's own outline. On its thread.
     */
    public void parts(PartSink out) {
        if (!posed) return;
        for (int p = 0; p < parts; p++) out.part(names[p], partShape(p), partPose(p, drawPose));
    }

    /**
     * As {@link #parts(PartSink)}, a followed one where its point is drawn at {@code partialTick}, turning as its entity
     * is drawn: it moves with the animation each frame, while it tests at tick rate. Any other, and a stopped one, is
     * where its last tick placed it. A client's render thread.
     *
     * <pre>{@code
     * hitbox.parts(partialTick, (part, shape, pose) -> CgCollisionDebug.shape(shape, pose, 0xFF40FF60));
     * }</pre>
     */
    public void parts(float partialTick, PartSink out) {
        if (!posed) return;
        if (entity < 0 || stopped || !world.entities().pose(entity, partialTick, frameEntity)) {
            parts(out);
            return;
        }
        CgEntityAttachments.point(entity, point, partialTick, frameEntity, frameAt);
        if (held || facing == Facing.FIXED) {
            frameQ.set(fixed);
        } else {
            double yaw = Math.toRadians(frameEntity[facing == Facing.BODY ? CgEntityQuery.BODY_YAW : CgEntityQuery.YAW]);
            double pitch = facing == Facing.LOOK ? Math.toRadians(frameEntity[CgEntityQuery.PITCH]) : 0;
            stepPose.facing(yaw, pitch).getRotation(frameQ);
        }
        for (int p = 0; p < parts; p++) {
            partOrigin(frameAt[0], frameAt[1], frameAt[2], frameQ, offsets[p * 3], offsets[p * 3 + 1], offsets[p * 3 + 2]);
            out.part(names[p], partShape(p), drawPose.at(scratch.x, scratch.y, scratch.z).rotation(frameQ));
        }
    }

    /** Whether it follows an entity: drawn where the frame draws its point ({@link #parts(float, PartSink)}). */
    public boolean follows() {
        return entity >= 0;
    }

    // ---- for CgHitboxType, which builds one per side

    void silent(boolean silent) {
        this.silent = silent;
    }

    void silentHits(@Nullable Consumer<? super CgHit> listener) {
        silentHits = listener;
    }

    /** The box round every part where its last tick placed it, into {@code out}; false before its first active tick. */
    boolean bounds(CgAABB out) {
        if (!posed) return false;
        for (int p = 0; p < parts; p++) {
            partShape(p).bounds(partPose(p, drawPose), partBox);
            if (p == 0) out.set(partBox);
            else out.union(partBox);
        }
        return true;
    }

    /**
     * A hit its owner claims on {@code target} with part {@code part}, put to this hitbox's own query and rules over
     * {@code window}, the box it swept while the claim could have been made, every entity grown by {@code padding},
     * its sight from {@code eyes} (the follower's at the claim's tick; null for where they are now): null and the hit
     * in {@code out} when they allow it, recorded as this hitbox's own; else {@link CgHitClaim.Refusal#RULE}. The
     * caller has checked the target is in range.
     */
    @Nullable
    CgHitClaim.Refusal judge(CgCollisionWorld world, int target, int part, CgAABBc window, double padding,
                             @Nullable double[] eyes, CgHit out) {
        CgHitPart name = part >= 0 && part < parts ? names[part] : parts > 0 ? names[0] : null;
        CgCollisionQuery q = world.overlap(window).part(name);
        int whose = source >= 0 ? source : entity;
        if (whose >= 0) q.source(whose);
        if (eyes != null) q.lookingFrom(eyes[0], eyes[1], eyes[2]);
        if (query != null) query.accept(q);
        q.inflate(padding);
        int n;
        CgHitboxes.gathering(this);
        try {
            n = q.into(partHits);
        } finally {
            CgHitboxes.gathering(null);
        }
        for (int i = 0; i < n; i++) {
            CgHit h = partHits.get(i);
            if (h.kind().isTerrain() || h.entityId() != target && h.parentId() != target) continue;
            if (!allowed(h.parentId())) return CgHitClaim.Refusal.RULE;
            hitList.record(h.parentId(), age);
            out.set(h);
            world.changed(h.entityId());
            if (h.parentId() != h.entityId()) world.changed(h.parentId());
            return null;
        }
        return CgHitClaim.Refusal.RULE;
    }

    /** Ages it {@code ticks} without testing anything: the start of a late joiner's catch-up. */
    void skip(double ticks) {
        if (!prepared) prepare();
        age += ticks;
        hasPrev = posed = false;
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

    /** On point {@code point} of {@code entity}, following it each tick. */
    CgHitbox on(int entity, int point) {
        this.entity = entity;
        this.point = point;
        return this;
    }

    boolean placed() {
        return entity >= 0;
    }

    // ---- for the queries meeting a hittable one

    /** Whether its last tick placed it, active: where queries meet it when hittable. */
    boolean posed() {
        return posed;
    }

    /** Whom it stands for: the entity it follows, else its source. */
    int owner() {
        return entity >= 0 ? entity : source;
    }

    /** The followed entity's eyes as its last tick placed it, into {@code out[0..2]}; false following nobody. */
    boolean followerEyes(double[] out) {
        if (entity < 0 || !posed) return false;
        out[0] = entityPose[CgEntityQuery.X];
        out[1] = entityPose[CgEntityQuery.Y] + entityPose[CgEntityQuery.EYE_HEIGHT];
        out[2] = entityPose[CgEntityQuery.Z];
        return true;
    }

    /** Part {@code p}'s shape as last placed; the next call may change it. */
    CgShape partShape(int p) {
        if (sizeKind == RADIUS) return sized[p].radius(sizeNow);
        if (sizeKind == SCALE) return sized[p].scale(sizeNow);
        return shapes[p];
    }

    /** As {@link #partShape}, a copy of its own: what a snapshot keeps. */
    CgShape frozenPartShape(int p) {
        if (sizeKind == RADIUS) return CgResizable.of(shapes[p]).radius(sizeNow);
        if (sizeKind == SCALE) return CgResizable.of(shapes[p]).scale(sizeNow);
        return shapes[p];
    }

    /** Part {@code p}'s pose as last placed, into {@code out}. */
    CgPose partPose(int p, CgPose out) {
        partOrigin(nowX, nowY, nowZ, nowQ, offsets[p * 3], offsets[p * 3 + 1], offsets[p * 3 + 2]);
        return out.at(scratch.x, scratch.y, scratch.z).rotation(nowQ);
    }

    /** The engine's tick: stops with its level. */
    void engineTick() {
        if (epoch != 0 && world.blocks().levelEpoch() != epoch) {
            stop();
            return;
        }
        if (clock == null) {
            advance(1);
            return;
        }
        // Ticks the character ran since last time; a freeze heard late steps its count back, and this waits it out.
        long ran = clock.ticks() - clockTicks;
        if (ran > 0) {
            clockTicks += ran;
            advance(ran);
        }
    }

    private void prepare() {
        if (parts == 0) throw new IllegalStateException("a hitbox needs a part");
        if (facing != Facing.FIXED && entity < 0) throw new IllegalStateException(facing + " needs a followed entity");
        if (hittable && owner() < 0) {
            throw new IllegalStateException("a hittable hitbox stands for its owner: follow an entity or give it a source");
        }
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
        if (hittable) CgHitboxes.addHittable(this);
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
            hasPrev = posed = false;
            return;
        }
        double now = sizeKind == NO_SIZE ? 0 : size(Math.min(active, activeFor));
        double before = sizeKind == NO_SIZE ? 0 : size(Math.max(0, active - ticks));
        sizeNow = now;
        posed = true;
        if (!passive) gather(before, now);
        prevX = nowX;
        prevY = nowY;
        prevZ = nowZ;
        prevQ.set(nowQ);
        hasPrev = true;
        if (!passive) apply();
        if (!stopped && active >= activeFor) stop();
        CgHitboxes.ticked(this, world);
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
        // where this client draws the point, so a claim is where the player saw it; a server draws nothing
        if (world.isClient()) CgEntityAttachments.point(entity, point, world.partialTick(), entityPose, at);
        else CgEntityAttachments.point(point, entityPose, at);
        nowX = at[0];
        nowY = at[1];
        nowZ = at[2];
        if (held || facing == Facing.FIXED) {
            nowQ.set(fixed);
            return true;
        }
        double yaw = Math.toRadians(entityPose[facing == Facing.BODY ? CgEntityQuery.BODY_YAW : CgEntityQuery.YAW]);
        double pitch = facing == Facing.LOOK ? Math.toRadians(entityPose[CgEntityQuery.PITCH]) : 0;
        stepPose.facing(yaw, pitch).getRotation(nowQ);
        if (once) {
            fixed.set(nowQ);
            held = true;
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
        CgHitboxes.gathering(this);
        try {
            gatherParts(before, now);
        } finally {
            CgHitboxes.gathering(null);
        }
        sortTargets();
    }

    private void gatherParts(double before, double now) {
        int turnSteps = 1;
        if (hasPrev) {
            double dot = Math.abs(prevQ.x * nowQ.x + prevQ.y * nowQ.y + prevQ.z * nowQ.z + prevQ.w * nowQ.w);
            double angle = 2 * Math.acos(Math.min(1, dot));
            if (angle > MAX_TURN) turnSteps = (int) Math.ceil(angle / MAX_TURN);
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
                    stepPose.rotation(stepQ).unrotate(cx - lastX, cy - lastY, cz - lastZ, scratch);
                    query(p, swept.set(shape, scratch.x, scratch.y, scratch.z), lastX, lastY, lastZ, stepQ);
                } else {
                    query(p, shape, cx, cy, cz, stepQ);
                }
                lastX = cx;
                lastY = cy;
                lastZ = cz;
            }
        }
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

    private void query(int p, CgShape shape, double px, double py, double pz, Quaterniondc rotation) {
        CgCollisionQuery q = world.overlap(shape).at(px, py, pz).rotation(rotation).part(names[p]);
        int whose = source >= 0 ? source : entity;
        if (whose >= 0) q.source(whose);
        if (query != null) query.accept(q);
        int n = q.into(partHits);
        for (int i = 0; i < n; i++) {
            CgHit h = partHits.get(i);
            if (!h.kind().isTerrain() && !hasTarget(h.parentId())) nextTarget().set(h);
        }
    }

    private boolean hasTarget(int whole) {
        for (int i = 0; i < targetCount; i++) {
            if (targets[i].parentId() == whole) return true;
        }
        return false;
    }

    private CgHit nextTarget() {
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
            int whole = h.parentId();
            if (zone && enters(whole) && onEnter != null && !silent) {
                handed(h);
                onEnter.accept(h);
            }
            if (stopped || onHit == null || !allowed(whole)) continue;
            hitList.record(whole, age);
            if (!silent) {
                handed(h);
                onHit.accept(h);
            } else if (silentHits != null) {
                silentHits.accept(h);
            }
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

    /** A hit about to reach a listener: what it damages or moves is the host's again for the rest of the batch. */
    private void handed(CgHit h) {
        world.changed(h.entityId());
        if (h.parentId() != h.entityId()) world.changed(h.parentId());
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
