package com.crystalgraphics.vfx.particle.gpu;

import javax.annotation.Nullable;

/**
 * Something a particle does that its emitter reacts to: it lands, it dies of age, or it reaches an age. A definition
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
 * }</pre>
 *
 * <p>When each fires, on both paths:</p>
 * <ul>
 *   <li>{@link Trigger#LANDING}: the step Ground sets the particle resting. Its normal is the floor's: up, on the fixed
 *       ground and on the voxel window's floors alike.</li>
 *   <li>{@link Trigger#DEATH}: the step its age reaches its life and it is removed. Never when an effect is stopped or
 *       its slot closes. Its normal is its velocity's direction, up when it barely moves.</li>
 *   <li>{@link Trigger#AGE}: the step {@code age - dt < seconds <= age}. Its normal as a death's.</li>
 * </ul>
 *
 * <p>Easy to get wrong:</p>
 * <ul>
 *   <li>A child's events may not spawn children of their own: one level of children ({@link #spawn} throws).</li>
 *   <li>A child is spawn {@link #childKey}{@code (parent id, event, i)} of its slot: the same particle on every path and
 *       tier, whatever order the events were found in. A definition lists at most {@link #MAX_EVENTS} events, each
 *       spawning at most {@link #MAX_CHILDREN}, and a parent's id stays below 2^24.</li>
 *   <li>Readback rows past the cap of a step are counted, never delivered.</li>
 * </ul>
 */
public final class CgVfxEvent {

    public enum Trigger { LANDING, DEATH, AGE }

    /** Events a definition lists, and children an event spawns, at most: what {@link #childKey} has room for. */
    public static final int MAX_EVENTS = 8, MAX_CHILDREN = 32;

    /** {@link #childKey}'s GLSL twin, bit for bit: {@code lib/vfx/fx_event.glsl} holds it. */
    public static final String CHILD_KEY_GLSL = "uint fx_child_key(uint parent, uint event, uint i)";

    private final Trigger trigger;
    private final float age;
    @Nullable
    private final CgVfxGpuEmitter child;
    private final int count, readback;
    private final float inherit;

    private CgVfxEvent(Trigger trigger, float age, @Nullable CgVfxGpuEmitter child, int count, float inherit, int readback) {
        this.trigger = trigger;
        this.age = age;
        this.child = child;
        this.count = count;
        this.inherit = inherit;
        this.readback = readback;
    }

    public static CgVfxEvent onLanding() {
        return new CgVfxEvent(Trigger.LANDING, 0f, null, 0, 0f, 0);
    }

    public static CgVfxEvent onDeath() {
        return new CgVfxEvent(Trigger.DEATH, 0f, null, 0, 0f, 0);
    }

    public static CgVfxEvent onAge(float seconds) {
        if (!(seconds > 0f)) throw new IllegalArgumentException("an age of " + seconds + ": it must be past birth");
        return new CgVfxEvent(Trigger.AGE, seconds, null, 0, 0f, 0);
    }

    /** {@code count} children of {@code child} at each event, launched by its own spawn numbers about the event's normal. */
    public CgVfxEvent spawn(CgVfxGpuEmitter child, int count) {
        if (count < 1 || count > MAX_CHILDREN) throw new IllegalArgumentException(count + " children an event: 1 to " + MAX_CHILDREN);
        for (CgVfxEvent e : child.events()) {
            if (e.child != null) {
                throw new IllegalArgumentException(child.name() + " spawns " + e.child.name() + " itself: a child's events "
                        + "may report rows but not spawn children");
            }
        }
        return new CgVfxEvent(trigger, age, child, count, inherit, readback);
    }

    /** The share of the parent's velocity a child takes on, added to its own launch; 0 by default. */
    public CgVfxEvent inherit(float share) {
        return new CgVfxEvent(trigger, age, child, count, share, readback);
    }

    /** Rows to the CPU, at most {@code cap} a step ({@link CgVfxEventListener}). */
    public CgVfxEvent readback(int cap) {
        if (cap < 1) throw new IllegalArgumentException("a cap of " + cap);
        return new CgVfxEvent(trigger, age, child, count, inherit, cap);
    }

    public Trigger trigger() {
        return trigger;
    }

    /** {@link Trigger#AGE}'s seconds; 0 for the others. */
    public float age() {
        return age;
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
}
