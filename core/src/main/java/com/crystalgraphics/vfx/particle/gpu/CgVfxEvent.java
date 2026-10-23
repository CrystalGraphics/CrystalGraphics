package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.easing.CgKeyframes;

import javax.annotation.Nullable;

/**
 * Something a particle does that its emitter reacts to: it is born, lands, hits something, dies, reaches an age, passes
 * another period of its life or another step, or its emitter spawns a batch. A definition lists its events
 * ({@link CgVfxGpuEmitter#events()}); each spawns children of another definition where it happened, sends rows to the
 * CPU, or both. On the GPU no CPU is in the loop: a child spawns in the step its parent's event fired.
 *
 * <pre>{@code
 * // debris landing raises three puffs of dust, carrying a fifth of its speed
 * CgVfxEvent dust = CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f);
 *
 * // an ember's death reported to the CPU, at most 16 a step: a hiss, a scorch decal
 * CgVfxEvent hiss = CgVfxEvent.onDeath().readback(16);
 *
 * // three to five chips at each impact, at most 64 impacts' worth a frame (Unreal's default)
 * CgVfxEvent chips = CgVfxEvent.onCollision().spawn(CHIPS, 3, 5).inherit(0.3f);
 *
 * // a puff of smoke every tenth of a second of an ember's life; a spark each step it lives, 256 a frame at most
 * CgVfxEvent smoke = CgVfxEvent.every(0.1f).spawn(SMOKE, 1).inherit(0.5f);
 * CgVfxEvent trail = CgVfxEvent.onLocation().spawn(SPARK, 1).maxPerFrame(256);
 *
 * // a ring at the effect's origin each time the emitter spawns a batch (Cascade's burst event)
 * CgVfxEvent ring = CgVfxEvent.onBurst().spawn(RING, 2).fromEmitter();
 *
 * // a sequence particle's: sparks at its clip's "tip" node on frame 20
 * CgVfxEvent sparks = CgVfxEvent.onAge(20f / 30f).spawn(SPARKS, 8).at(CLIP.prefab().node("tip"));
 * }</pre>
 *
 * <p>When each fires:</p>
 * <ul>
 *   <li>{@link Trigger#LANDING}: the step Ground sets the particle resting. Its normal is the floor's: up, on the fixed
 *       ground and on the voxel window's floors alike.</li>
 *   <li>{@link Trigger#DEATH}: the step its age reaches its life and it is removed. Never when an effect is stopped or
 *       its slot closes. Its normal is its velocity's direction, up when it barely moves.</li>
 *   <li>{@link Trigger#AGE}: the step {@code age - dt < seconds <= age}; at 0 its first step, the format's
 *       {@code spawn}. Its normal as a death's.</li>
 *   <li>{@link Trigger#COLLISION}: each step a kind after the solver reports an impact ({@code fx_hit} in
 *       {@code fx_types.glsl}: Unity VFX Graph's punctual contact, never resting or sliding), the surface's normal its
 *       normal, its velocity the one it struck with. Firing n is the particle's n-th impact, from 1.</li>
 *   <li>{@link Trigger#RATE}: each step {@code floor((age - dt - from) / period) < floor((age - from) / period)}, while
 *       {@code age <= until} ({@link #between}): first one period past {@code from}, at most once a step.</li>
 *   <li>{@link Trigger#LOCATION}: every step the particle lives, its death's included (Niagara's location event).</li>
 *   <li>{@link Trigger#BURST}: each batch its emitter spawns, at the batch's source and with no velocity: a tick's
 *       bursts are one batch and its rate spawns another (Cascade's {@code HandleParticleBurst},
 *       {@code ParticleModules_Event.cpp}). The batch's first spawn index is the event's parent id.</li>
 *   <li>{@link #at}: on a {@code SEQUENCE} emitter, any trigger fires at a node of the particle's clip, its normal the
 *       node's +Y.</li>
 * </ul>
 *
 * <p>Easy to get wrong:</p>
 * <ul>
 *   <li>{@link #LEVELS} levels of children ({@link #spawn} throws past them), Sparking! Zero's deepest chain.</li>
 *   <li>At most {@link #maxPerFrame()} of an event's firings a frame spawn children, over every instance of its
 *       definition in a pool: Unreal's {@code MaxEventsPerFrame}, 64 unless told. Which ones past it is the GPU's
 *       order. Readback rows have their own cap.</li>
 *   <li>A child is spawn {@link #childKey}{@code (parent id, event, i)} of its slot, hashed: the same particle on every
 *       path and tier. A definition lists at most {@link #MAX_EVENTS} events, each spawning at most
 *       {@link #MAX_CHILDREN}.</li>
 *   <li>A repeating event fires its first {@link #firings(int)} only: a collision's first eight unless told.</li>
 * </ul>
 */
public final class CgVfxEvent {

    public enum Trigger { LANDING, DEATH, AGE, COLLISION, RATE, LOCATION, BURST }

    /** Events a definition lists, and children an event spawns, at most (P0's census: 1,000). */
    public static final int MAX_EVENTS = 16, MAX_CHILDREN = 1000;

    /** Levels of children below a definition spawned by nothing: a pool's feed tiers less one (P0's census: 3). */
    public static final int LEVELS = 3;

    /** Firings a frame that spawn children unless told: Unreal's {@code MaxEventsPerFrame} ({@code NiagaraEmitter.h}). */
    public static final int MAX_PER_FRAME = 64;

    /** {@link #childKey}'s GLSL twin, bit for bit: {@code lib/vfx/fx_event.glsl} holds it. */
    public static final String CHILD_KEY_GLSL = "uint fx_child_key(uint parent, uint event, uint i)";

    /** {@link #childKey(int, int, int, int)}'s GLSL twin, in the same file. */
    public static final String CHILD_KEY_AT_GLSL = "uint fx_child_key_at(uint parent, uint event, uint i, uint firing)";

    /** {@link #grandchildKey}'s GLSL twin, in the same file. */
    public static final String GRANDCHILD_KEY_GLSL = "uint fx_grandchild_key(uint parent, uint event, uint i, uint firing)";

    /** {@link #node()} for an event fired where its particle is. */
    public static final int NO_NODE = -1;

    /** A collision's firings at most by default: Niagara and Unity cap event output, and children are sized by it. */
    public static final int COLLISION_FIRINGS = 8;

    private final Trigger trigger;
    private final float age;
    @Nullable
    private final CgVfxGpuEmitter child;
    private final int countMin, countMax, readback, firings, maxPerFrame;
    private final float inheritX, inheritY, inheritZ;
    /** A count rolled as a float and rounded to the nearest ({@link #spawnRounded}): its ends; NaN for a whole roll. */
    private final float roundMin, roundMax;
    private final int node;
    private final float from, until;
    private final boolean fromEmitter;
    /** A count read off a curve by the emitter's loop seconds, rounded: Cascade's receiver count as a curve; else null. */
    @Nullable
    private final CgKeyframes countCurve;

    private CgVfxEvent(Trigger trigger, float age, @Nullable CgVfxGpuEmitter child, int countMin, int countMax, float roundMin,
                       float roundMax, float inheritX, float inheritY, float inheritZ, int readback, int firings, int node, float from, float until,
                       int maxPerFrame, boolean fromEmitter, @Nullable CgKeyframes countCurve) {
        this.trigger = trigger;
        this.age = age;
        this.child = child;
        this.countMin = countMin;
        this.countMax = countMax;
        this.roundMin = roundMin;
        this.roundMax = roundMax;
        this.inheritX = inheritX;
        this.inheritY = inheritY;
        this.inheritZ = inheritZ;
        this.readback = readback;
        this.firings = firings;
        this.node = node;
        this.from = from;
        this.until = until;
        this.maxPerFrame = maxPerFrame;
        this.fromEmitter = fromEmitter;
        this.countCurve = countCurve;
    }

    private CgVfxEvent(Trigger trigger, float age, int firings) {
        this(trigger, age, null, 0, 0, Float.NaN, Float.NaN, 0f, 0f, 0f, 0, firings, NO_NODE, 0f, Float.POSITIVE_INFINITY, MAX_PER_FRAME, false, null);
    }

    /**
     * The event the asset format's trigger names: {@code spawn} (the particle's first step), {@code death},
     * {@code collision}, {@code location} (every step) and {@code burst} (every batch): Cascade's five. A landing is
     * Java's alone, Unreal having none.
     *
     * <pre>{@code
     * CgVfxEvent.ofSpec("collision").spawn(CHIPS, 4);
     * }</pre>
     */
    public static CgVfxEvent ofSpec(String trigger) {
        return switch (trigger) {
            case "spawn" -> new CgVfxEvent(Trigger.AGE, 0f, 1);
            case "death" -> onDeath();
            case "collision" -> onCollision();
            case "location" -> onLocation();
            case "burst" -> onBurst();
            default -> throw new IllegalArgumentException("event trigger '" + trigger + "': spawn, death, collision, "
                    + "location or burst");
        };
    }

    /** The format's name for this event's trigger ({@link #ofSpec}), or null where the format has none. */
    @Nullable
    public String specTrigger() {
        return switch (trigger) {
            case AGE -> age == 0f ? "spawn" : null;
            case DEATH -> "death";
            case COLLISION -> firings == COLLISION_FIRINGS ? "collision" : null;
            case LOCATION -> "location";
            case BURST -> "burst";
            case LANDING, RATE -> null;
        };
    }

    public static CgVfxEvent onLanding() {
        return new CgVfxEvent(Trigger.LANDING, 0f, 1);
    }

    public static CgVfxEvent onDeath() {
        return new CgVfxEvent(Trigger.DEATH, 0f, 1);
    }

    public static CgVfxEvent onAge(float seconds) {
        if (!(seconds > 0f)) throw new IllegalArgumentException("an age of " + seconds + ": it must be past birth");
        return new CgVfxEvent(Trigger.AGE, seconds, 1);
    }

    /** Each impact a kind after the solver reports ({@code fx_hit}). */
    public static CgVfxEvent onCollision() {
        return new CgVfxEvent(Trigger.COLLISION, 0f, COLLISION_FIRINGS);
    }

    /** Each time another {@code seconds} of the particle's life pass. */
    public static CgVfxEvent every(float seconds) {
        if (!(seconds > 0f)) throw new IllegalArgumentException("a period of " + seconds + ": it must be positive");
        return new CgVfxEvent(Trigger.RATE, seconds, Integer.MAX_VALUE);
    }

    /** Every step the particle lives. */
    public static CgVfxEvent onLocation() {
        return new CgVfxEvent(Trigger.LOCATION, 0f, Integer.MAX_VALUE);
    }

    /** Every batch its emitter spawns, at the batch's source. */
    public static CgVfxEvent onBurst() {
        return new CgVfxEvent(Trigger.BURST, 0f, 1);
    }

    /** {@code count} children of {@code child} at each event, launched by its own spawn numbers about the event's normal. */
    public CgVfxEvent spawn(CgVfxGpuEmitter child, int count) {
        return spawn(child, count, count);
    }

    /**
     * Between {@code min} and {@code max} children of {@code child} at each event, rolled per firing: Cascade's spawn
     * count over a range. A {@code min} of 0 lets a firing spawn none, as Cascade's rounded count does
     * ({@code ParticleModules_Event.cpp:345-378}).
     */
    public CgVfxEvent spawn(CgVfxGpuEmitter child, int min, int max) {
        if (min < 0 || max < Math.max(min, 1) || max > MAX_CHILDREN) {
            throw new IllegalArgumentException(min + " to " + max + " children an event: at most 1 to " + MAX_CHILDREN);
        }
        int below = levels(child);
        if (below >= LEVELS) {
            throw new IllegalArgumentException(child.name() + " spawns children " + below + " levels deep itself: " + LEVELS
                    + " levels of children at most");
        }
        return new CgVfxEvent(trigger, age, child, min, max, Float.NaN, Float.NaN, inheritX, inheritY, inheritZ, readback, firings, node, from, until, maxPerFrame, fromEmitter, null);
    }

    /**
     * Between {@code min} and {@code max} children of {@code child} at each event, a float rolled per firing and rounded
     * to the nearest: Cascade's receiver count, {@code RoundToInt(SpawnCount.GetValue())}
     * ({@code ParticleModules_Event.cpp:345-366}). Its ends come up half as often as the counts between them;
     * {@link #spawn(CgVfxGpuEmitter, int, int)} rolls a whole number, each equally, as Niagara's {@code RandRange}.
     *
     * <pre>{@code
     * CgVfxEvent.onDeath().spawnRounded(SHARDS, 3f, 5f);   // 3, 4 and 5 a quarter, a half and a quarter of the time
     * }</pre>
     */
    public CgVfxEvent spawnRounded(CgVfxGpuEmitter child, float min, float max) {
        if (!(min >= 0f) || !(max >= min)) throw new IllegalArgumentException(min + " to " + max + " children an event");
        CgVfxEvent e = spawn(child, Math.round(min), Math.round(max));
        return new CgVfxEvent(trigger, age, child, e.countMin, e.countMax, min, max, inheritX, inheritY, inheritZ, readback,
                firings, node, from, until, maxPerFrame, fromEmitter, null);
    }

    /**
     * Children of {@code child} at each event, as many as {@code count} reads at the emitter's loop seconds, rounded to
     * the nearest: Cascade's receiver count as a curve, read at the emitter's time ({@code ParticleModules_Event.cpp:345-362}).
     *
     * <pre>{@code
     * // four at each bounce in the first quarter second, none after a second
     * CgVfxEvent.onCollision().spawnOver(CHIPS, CgKeyframes.start(0f, 4f).to(1f, 0f, CgEasings.LINEAR).build());
     * }</pre>
     *
     * <ul>
     *   <li>It is read once a frame per emitter instance, not per firing: every firing of a frame spawns the same count.</li>
     *   <li>Its children are sized by the curve's most, at most {@link #MAX_CHILDREN}.</li>
     * </ul>
     */
    public CgVfxEvent spawnOver(CgVfxGpuEmitter child, CgKeyframes count) {
        float most = 0f;
        float t0 = count.time(0), t1 = count.time(count.size() - 1);
        for (int i = 0; i <= 64; i++) most = Math.max(most, count.at(t0 + (t1 - t0) * i / 64f));
        for (int i = 0; i < count.size(); i++) most = Math.max(most, count.value(i));
        CgVfxEvent e = spawn(child, 0, Math.max(1, Math.round(most)));
        return new CgVfxEvent(trigger, age, child, 0, e.countMax, Float.NaN, Float.NaN, inheritX, inheritY, inheritZ, readback,
                firings, node, from, until, maxPerFrame, fromEmitter, count);
    }

    /** The same event spawning {@code child} instead, every count kept: a scaled copy of its child. */
    public CgVfxEvent withChild(CgVfxGpuEmitter child) {
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback,
                firings, node, from, until, maxPerFrame, fromEmitter, countCurve);
    }

    /** Its count curve by the loop's seconds ({@link #spawnOver}); null for a count or a range. */
    @Nullable
    public CgKeyframes countCurve() {
        return countCurve;
    }

    /** The count a curve gives at {@code loopSeconds}, rounded and held to {@link #count()}; {@link #count()} without one. */
    public int countAt(float loopSeconds) {
        if (countCurve == null) return countMax;
        return Math.max(0, Math.min(countMax, Math.round(countCurve.at(loopSeconds))));
    }

    /** Whether its count is a float rounded to the nearest ({@link #spawnRounded}). */
    public boolean rounded() {
        return !Float.isNaN(roundMax);
    }

    /** A rounded count's ends, as given; NaN for a whole roll. */
    public float roundMin() {
        return roundMin;
    }

    public float roundMax() {
        return roundMax;
    }

    /** The levels of children below {@code e}: 0 for one whose events spawn none. */
    private static int levels(CgVfxGpuEmitter e) {
        int deepest = 0;
        for (CgVfxEvent ev : e.events()) if (ev.child != null) deepest = Math.max(deepest, 1 + levels(ev.child));
        return deepest;
    }

    /** The share of the parent's velocity a child takes on, added to its own launch; 0 by default. */
    public CgVfxEvent inherit(float share) {
        return inherit(share, share, share);
    }

    /**
     * The share of each axis of the parent's velocity a child takes on: Cascade's {@code InheritVelocityScale}, a
     * vector ({@code ParticleModules_Event.cpp:373-375}).
     *
     * <pre>{@code
     * CgVfxEvent.onDeath().spawn(DUST, 2).inherit(1f, 0f, 1f);   // carried along the ground, not up or down
     * }</pre>
     */
    public CgVfxEvent inherit(float x, float y, float z) {
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, x, y, z, readback, firings, node, from, until,
                maxPerFrame, fromEmitter, countCurve);
    }

    /** Rows to the CPU, at most {@code cap} a step ({@link CgVfxEventListener}). */
    public CgVfxEvent readback(int cap) {
        if (cap < 1) throw new IllegalArgumentException("a cap of " + cap);
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, cap, firings, node, from, until, maxPerFrame,
                fromEmitter, countCurve);
    }

    /** At most {@code most} of its firings a frame spawn children: {@link #MAX_PER_FRAME} unless told. */
    public CgVfxEvent maxPerFrame(int most) {
        if (most < 1) throw new IllegalArgumentException("at most " + most + " a frame");
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback, firings, node, from, until, most,
                fromEmitter, countCurve);
    }

    /** Its children born at the effect's origin rather than where it fired: the format's {@code use_emitter_location}. */
    public CgVfxEvent fromEmitter() {
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback, firings, node, from, until, maxPerFrame,
                true, countCurve);
    }

    /**
     * A repeating event's first {@code most} firings only, per particle: {@link #COLLISION_FIRINGS} for a collision by
     * default, every one for a rate or a location. What its children are sized by ({@code CgVfxEmitter.peakChildren}).
     *
     * <pre>{@code
     * CgVfxEvent.onCollision().spawn(SPARKS, 3).firings(2);   // sparks at the first two bounces only
     * }</pre>
     */
    public CgVfxEvent firings(int most) {
        if (!repeats()) throw new IllegalArgumentException(trigger + " fires once: only a collision, a rate or a location repeats");
        if (most < 1) throw new IllegalArgumentException("at most " + most + " firings");
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback, most, node, from, until, maxPerFrame,
                fromEmitter, countCurve);
    }

    /**
     * Fired at node {@code node} of its {@code SEQUENCE} emitter's clip ({@code CgVfxPrefab.node(name)}) rather than
     * where the particle is: the node's place at the particle's age, its +Y the normal. The emitter refuses a node its
     * clip does not bake ({@code CgVfxClipAtlas.bakes}), and any node on another renderer.
     */
    public CgVfxEvent at(int node) {
        if (node < 0) throw new IllegalArgumentException("node " + node);
        if (trigger == Trigger.BURST) throw new IllegalArgumentException("a burst fires at its batch's source, never at a node");
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback, firings, node, from, until, maxPerFrame,
                fromEmitter, countCurve);
    }

    /**
     * A rate's window: it fires only while the particle's age is within {@code from} to {@code until} seconds, its
     * periods counted from {@code from}. A clip's stream.
     */
    public CgVfxEvent between(float from, float until) {
        if (trigger != Trigger.RATE) throw new IllegalArgumentException(trigger + " has no window: only a rate fires within one");
        if (!(from >= 0f) || !(until > from)) throw new IllegalArgumentException("a window from " + from + " to " + until);
        return new CgVfxEvent(trigger, age, child, countMin, countMax, roundMin, roundMax, inheritX, inheritY, inheritZ, readback, firings, node, from, until, maxPerFrame,
                fromEmitter, countCurve);
    }

    /** The clip node it fires at, or {@link #NO_NODE}. */
    public int node() {
        return node;
    }

    /** A rate's window, in seconds of the particle's age: 0 to infinity unless {@link #between} said. */
    public float from() {
        return from;
    }

    public float until() {
        return until;
    }

    /** The firings it makes at most per particle: 1 for an event that fires once. */
    public int firings() {
        return firings;
    }

    public Trigger trigger() {
        return trigger;
    }

    /** {@link Trigger#AGE}'s seconds, {@link Trigger#RATE}'s period; 0 for the others. */
    public float age() {
        return age;
    }

    /** Whether it may fire more than once in a particle's life: a collision, a rate or a location. */
    public boolean repeats() {
        return trigger == Trigger.COLLISION || trigger == Trigger.RATE || trigger == Trigger.LOCATION;
    }

    /** The definition it spawns, or null for none. */
    @Nullable
    public CgVfxGpuEmitter child() {
        return child;
    }

    /** Children an event spawns at most; 0 with no child. */
    public int count() {
        return countMax;
    }

    /** Children an event spawns at least; {@link #count()} unless a range was given. */
    public int countMin() {
        return countMin;
    }

    /** The share of the parent's velocity on {@code axis} (0 to 2) a child takes on. */
    public float inherit(int axis) {
        return axis == 0 ? inheritX : axis == 1 ? inheritY : inheritZ;
    }

    /** Rows a step sent to the CPU at most; 0 for none. */
    public int readback() {
        return readback;
    }

    /** Firings a frame that spawn children at most. */
    public int maxPerFrame() {
        return maxPerFrame;
    }

    /** Whether its children are born at the effect's origin. */
    public boolean atEmitter() {
        return fromEmitter;
    }

    /**
     * Child {@code i} of event {@code event} (its index in the parent's {@link CgVfxGpuEmitter#events()}) of parent
     * particle {@code parentId}, as a spawn index of the child's slot: what {@code rand(seed, key, k)} draws from, and the
     * child's id. Hashed: two keys alike at 2^-32 a pair.
     *
     * <pre>{@code
     * int key = CgVfxEvent.childKey(parent.id, 0, i);
     * float speed = speedMin + (speedMax - speedMin) * CgVfxEmitterInstance.rand(childSeed, key, 3);
     * }</pre>
     */
    public static int childKey(int parentId, int event, int i) {
        return mix(parentId ^ mix(event << 10 | i));
    }

    /**
     * {@link #childKey(int, int, int)} for an event that {@linkplain #repeats repeats}, at its {@code firing}-th firing
     * (the trigger says how they are numbered): each firing's children differ.
     *
     * <pre>{@code
     * int key = event.repeats() ? CgVfxEvent.childKey(parent.id, e, i, parent.collisions) : CgVfxEvent.childKey(parent.id, e, i);
     * }</pre>
     */
    public static int childKey(int parentId, int event, int i, int firing) {
        // A Weyl step, as splitmix's: an xor of two mixes is symmetric, parent p's firing f parent f's firing p.
        return mix(childKey(parentId, event, i) + firing * 0x9E3779B9);
    }

    /**
     * Child {@code i} of event {@code event} of child particle {@code parentId}, at its {@code firing}-th firing (0 for
     * an event that fires once): the key of every level past the first. Its parent's id is a full 32-bit key.
     */
    public static int grandchildKey(int parentId, int event, int i, int firing) {
        return mix(mix(parentId ^ mix(firing)) + (event << 10 | i));
    }

    /**
     * How many children a firing with a ranged count spawns, from {@code min} to {@code max}: drawn by
     * {@code rand(seed, key, 0)}, where {@code key} is the firing's {@code childKey} (or {@code grandchildKey}) at
     * {@code i = MAX_CHILDREN}, which no child takes. {@code step_child}'s, bit for bit given {@code rand}.
     *
     * <pre>{@code
     * int n = CgVfxEvent.rolledCount(2, 4, CgVfxEmitterInstance.rand(seed, CgVfxEvent.childKey(parentId, e, CgVfxEvent.MAX_CHILDREN), 0));
     * }</pre>
     */
    public static int rolledCount(int min, int max, float rand) {
        if (min >= max) return max;
        return min + Math.min((int) (rand * (max - min + 1)), max - min);
    }

    /**
     * The children a firing of a rounded count spawns ({@link #spawnRounded}), as the GPU rolls them: {@code rand}
     * between the ends, rounded to the nearest.
     */
    public static int roundedCount(float min, float max, float rand) {
        return (int) Math.floor(min + rand * (max - min) + 0.5f);
    }

    // Chris Wellons's lowbias32 (hash-prospector, public domain).
    private static int mix(int x) {
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return x;
    }
}
