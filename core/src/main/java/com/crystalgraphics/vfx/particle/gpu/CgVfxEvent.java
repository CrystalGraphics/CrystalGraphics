package com.crystalgraphics.vfx.particle.gpu;

import javax.annotation.Nullable;

/**
 * Something a particle does that its emitter reacts to: it lands, it hits something, it dies, it reaches an age, or
 * another period of its life passes. A definition
 * lists its events ({@link CgVfxGpuEmitter#events()}); each spawns children of another definition where it happened,
 * sends rows to the CPU, or both. On the GPU no CPU is in the loop: a child spawns in the step its parent's event fired.
 *
 * <pre>{@code
 * // debris landing raises three puffs of dust, carrying a fifth of its speed
 * CgVfxEvent dust = CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f);
 *
 * // an ember's death reported to the CPU, at most 16 a step: a hiss, a scorch decal
 * CgVfxEvent hiss = CgVfxEvent.onDeath().readback(16);
 *
 * // both at once, at an age
 * CgVfxEvent burst = CgVfxEvent.onAge(0.5f).spawn(SPARKS, 8).readback(4);
 *
 * // a spark at every bounce, and a puff of smoke every tenth of a second of an ember's life
 * CgVfxEvent bounce = CgVfxEvent.onCollision().spawn(SPARKS, 2);
 * CgVfxEvent smoke = CgVfxEvent.every(0.1f).spawn(SMOKE, 1).inherit(0.5f);
 *
 * // a sequence particle's: sparks at its clip's "tip" node on frame 20, and smoke from it between 0.3 s and 1.5 s
 * CgVfxEvent sparks = CgVfxEvent.onAge(20f / 30f).spawn(SPARKS, 8).at(CLIP.prefab().node("tip"));
 * CgVfxEvent trail = CgVfxEvent.every(0.05f).between(0.3f, 1.5f).spawn(SMOKE, 1).at(CLIP.prefab().node("tip"));
 * }</pre>
 *
 * <p>When each fires, on both paths:</p>
 * <ul>
 *   <li>{@link Trigger#LANDING}: the step Ground sets the particle resting. Its normal is the floor's: up, on the fixed
 *       ground and on the voxel window's floors alike.</li>
 *   <li>{@link Trigger#DEATH}: the step its age reaches its life and it is removed. Never when an effect is stopped or
 *       its slot closes. Its normal is its velocity's direction, up when it barely moves.</li>
 *   <li>{@link Trigger#AGE}: the step {@code age - dt < seconds <= age}. Its normal as a death's.</li>
 *   <li>{@link Trigger#COLLISION}: each step a kind after the solver reports an impact ({@code fx_hit} in
 *       {@code fx_types.glsl}: Unity VFX Graph's punctual contact, never resting or sliding), the surface's normal its
 *       normal, its velocity the one it struck with: after the solver, before the kinds after it. Firing n is the
 *       particle's n-th impact, from 1.</li>
 *   <li>{@link Trigger#RATE}: each step {@code floor((age - dt - from) / period) < floor((age - from) / period)}, the age
 *       after the step, while {@code age <= until} ({@link #between}; from birth for ever unless given): first one
 *       period past {@code from}, never at it, and at most once a step (Godot's constant sub-emitter). Its normal as a
 *       death's. Firing n is {@code floor((age - from) / period)}.</li>
 *   <li>{@link #at}: on a {@code SEQUENCE} emitter, any trigger fires at a node of the particle's clip, where that node
 *       is at the particle's age (its size times the node's place, turned with the effect), its normal the node's +Y.</li>
 * </ul>
 *
 * <p>Easy to get wrong:</p>
 * <ul>
 *   <li>Two levels of children: a child's events may spawn children, theirs may not ({@link #spawn} throws). A
 *       grandchild is keyed by {@link #grandchildKey}, whatever its trigger.</li>
 *   <li>A child is spawn {@link #childKey}{@code (parent id, event, i)} of its slot: the same particle on every path and
 *       tier, whatever order the events were found in. A trigger that {@linkplain #repeats repeats} mixes in which
 *       firing it is ({@link #childKey(int, int, int, int)}), so each firing's children differ. A definition lists at most {@link #MAX_EVENTS} events, each
 *       spawning at most {@link #MAX_CHILDREN}, and a parent's id stays below 2^24.</li>
 *   <li>Readback rows past the cap of a step are counted, never delivered.</li>
 *   <li>A repeating event fires its first {@link #firings(int)} only: a collision's first eight unless told.</li>
 * </ul>
 */
public final class CgVfxEvent {

    public enum Trigger { LANDING, DEATH, AGE, COLLISION, RATE }

    /** Events a definition lists, and children an event spawns, at most: what {@link #childKey} has room for. */
    public static final int MAX_EVENTS = 8, MAX_CHILDREN = 32;

    /** {@link #childKey}'s GLSL twin, bit for bit: {@code lib/vfx/fx_event.glsl} holds it. */
    public static final String CHILD_KEY_GLSL = "uint fx_child_key(uint parent, uint event, uint i)";

    /** {@link #childKey(int, int, int, int)}'s GLSL twin, in the same file. */
    public static final String CHILD_KEY_AT_GLSL = "uint fx_child_key_at(uint parent, uint event, uint i, uint firing)";

    /** {@link #grandchildKey}'s GLSL twin, in the same file. */
    public static final String GRANDCHILD_KEY_GLSL = "uint fx_grandchild_key(uint parent, uint event, uint i, uint firing)";

    /** {@link #node()} for an event fired where its particle is. */
    public static final int NO_NODE = -1;

    private final Trigger trigger;
    private final float age;
    @Nullable
    private final CgVfxGpuEmitter child;
    private final int count, readback, firings;
    private final float inherit;
    private final int node;
    private final float from, until;

    /** A collision's firings at most by default: Niagara and Unity cap event output, and children are sized by it. */
    public static final int COLLISION_FIRINGS = 8;

    private CgVfxEvent(Trigger trigger, float age, @Nullable CgVfxGpuEmitter child, int count, float inherit, int readback,
                       int firings, int node, float from, float until) {
        this.trigger = trigger;
        this.age = age;
        this.child = child;
        this.count = count;
        this.inherit = inherit;
        this.readback = readback;
        this.firings = firings;
        this.node = node;
        this.from = from;
        this.until = until;
    }

    private CgVfxEvent(Trigger trigger, float age, int firings) {
        this(trigger, age, null, 0, 0f, 0, firings, NO_NODE, 0f, Float.POSITIVE_INFINITY);
    }

    /**
     * The event the asset format's trigger names: {@code spawn} (the particle's first step), {@code death},
     * {@code collision}, {@code location} (every {@code seconds} of its life, Niagara's location event). The format's
     * {@code burst} has no GPU trigger yet and throws, naming it.
     *
     * <pre>{@code
     * CgVfxEvent.ofSpec("collision", 0f).spawn(CHIPS, 4);
     * CgVfxEvent.ofSpec("location", 0.1f).spawn(SMOKE, 1);   // a puff every tenth of a second of flight
     * }</pre>
     */
    public static CgVfxEvent ofSpec(String trigger, float seconds) {
        return switch (trigger) {
            case "spawn" -> new CgVfxEvent(Trigger.AGE, 0f, 1);
            case "death" -> onDeath();
            case "collision" -> onCollision();
            case "location" -> every(seconds);
            default -> throw new IllegalArgumentException("event trigger '" + trigger + "': the GPU events take spawn, death, "
                    + "collision and location");
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

    /** {@code count} children of {@code child} at each event, launched by its own spawn numbers about the event's normal. */
    public CgVfxEvent spawn(CgVfxGpuEmitter child, int count) {
        if (count < 1 || count > MAX_CHILDREN) throw new IllegalArgumentException(count + " children an event: 1 to " + MAX_CHILDREN);
        for (CgVfxEvent e : child.events()) {
            if (e.child == null) continue;
            for (CgVfxEvent g : e.child.events()) {
                if (g.child != null) {
                    throw new IllegalArgumentException(child.name() + "'s child " + e.child.name() + " spawns " + g.child.name()
                            + " itself: two levels of children, so a grandchild's events may report rows but not spawn");
                }
            }
        }
        return new CgVfxEvent(trigger, age, child, count, inherit, readback, firings, node, from, until);
    }

    /** The share of the parent's velocity a child takes on, added to its own launch; 0 by default. */
    public CgVfxEvent inherit(float share) {
        return new CgVfxEvent(trigger, age, child, count, share, readback, firings, node, from, until);
    }

    /** Rows to the CPU, at most {@code cap} a step ({@link CgVfxEventListener}). */
    public CgVfxEvent readback(int cap) {
        if (cap < 1) throw new IllegalArgumentException("a cap of " + cap);
        return new CgVfxEvent(trigger, age, child, count, inherit, cap, firings, node, from, until);
    }

    /**
     * A repeating event's first {@code most} firings only, per particle: {@link #COLLISION_FIRINGS} for a collision by
     * default, every one for a rate. What its children are sized by ({@code CgVfxEmitter.peakChildren}), on both paths.
     *
     * <pre>{@code
     * CgVfxEvent.onCollision().spawn(SPARKS, 3).firings(2);   // sparks at the first two bounces only
     * }</pre>
     */
    public CgVfxEvent firings(int most) {
        if (!repeats()) throw new IllegalArgumentException(trigger + " fires once: only a collision or a rate repeats");
        if (most < 1) throw new IllegalArgumentException("at most " + most + " firings");
        return new CgVfxEvent(trigger, age, child, count, inherit, readback, most, node, from, until);
    }

    /**
     * Fired at node {@code node} of its {@code SEQUENCE} emitter's clip ({@code CgVfxPrefab.node(name)}) rather than
     * where the particle is: the node's place at the particle's age, its +Y the normal. The emitter refuses a node its
     * clip does not bake ({@code CgVfxClipAtlas.bakes}), and any node on another renderer.
     */
    public CgVfxEvent at(int node) {
        if (node < 0) throw new IllegalArgumentException("node " + node);
        return new CgVfxEvent(trigger, age, child, count, inherit, readback, firings, node, from, until);
    }

    /**
     * A rate's window: it fires only while the particle's age is within {@code from} to {@code until} seconds, its
     * periods counted from {@code from}. A clip's stream.
     */
    public CgVfxEvent between(float from, float until) {
        if (trigger != Trigger.RATE) throw new IllegalArgumentException(trigger + " has no window: only a rate fires within one");
        if (!(from >= 0f) || !(until > from)) throw new IllegalArgumentException("a window from " + from + " to " + until);
        return new CgVfxEvent(trigger, age, child, count, inherit, readback, firings, node, from, until);
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

    /** Whether it may fire more than once in a particle's life: a collision, or a rate. */
    public boolean repeats() {
        return trigger == Trigger.COLLISION || trigger == Trigger.RATE;
    }

    /** The definition it spawns, or null for none. */
    @Nullable
    public CgVfxGpuEmitter child() {
        return child;
    }

    /** Children an event spawns; 0 with no child. */
    public int count() {
        return count;
    }

    public float inherit() {
        return inherit;
    }

    /** Rows a step sent to the CPU at most; 0 for none. */
    public int readback() {
        return readback;
    }

    /**
     * Child {@code i} of event {@code event} (its index in the parent's {@link CgVfxGpuEmitter#events()}) of parent
     * particle {@code parentId}, as a spawn index of the child's slot: what {@code rand(seed, key, k)} draws from, and the
     * child's id. Injective inside the limits above.
     *
     * <pre>{@code
     * int key = CgVfxEvent.childKey(parent.id, 0, i);
     * float speed = speedMin + (speedMax - speedMin) * CgVfxEmitterInstance.rand(childSeed, key, 3);
     * }</pre>
     */
    public static int childKey(int parentId, int event, int i) {
        return parentId << 8 | event << 5 | i;
    }

    /**
     * {@link #childKey(int, int, int)} for an event that {@linkplain #repeats repeats}, at its {@code firing}-th firing
     * (the trigger says how they are numbered): hashed with it, so two firings' children differ. Not injective, at
     * 2^-32 a pair.
     *
     * <pre>{@code
     * int key = event.repeats() ? CgVfxEvent.childKey(parent.id, e, i, parent.collisions) : CgVfxEvent.childKey(parent.id, e, i);
     * }</pre>
     */
    public static int childKey(int parentId, int event, int i, int firing) {
        return mix(childKey(parentId, event, i) ^ mix(firing));
    }

    /**
     * Child {@code i} of event {@code event} of child particle {@code parentId}, at its {@code firing}-th firing (0 for
     * an event that fires once): a grandchild's key. Its parent's id is a full 32-bit key, past what
     * {@link #childKey(int, int, int)} packs, so every bit of it is hashed in. Not injective, at 2^-32 a pair.
     */
    public static int grandchildKey(int parentId, int event, int i, int firing) {
        return mix(mix(parentId ^ mix(firing)) + (event << 5 | i));
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
