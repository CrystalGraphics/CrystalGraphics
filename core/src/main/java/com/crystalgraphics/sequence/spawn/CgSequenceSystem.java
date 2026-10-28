package com.crystalgraphics.sequence.spawn;

import javax.annotation.Nullable;

/**
 * A spawned particle system, as a {@code system} track drives it: Unreal's Niagara component under its spawn section,
 * and Cascade's under its particle keys.
 *
 * <pre>{@code
 * // what the engine's system spawner makes; a test's stand-in records the calls
 * system.activate(true);       // a section's start, an activate key: from its beginning
 * system.deactivate();         // no new particles; what is alive finishes
 * }</pre>
 *
 * <ul>
 *   <li>{@link #activate} with {@code reset} starts it over even if it was running, as a trigger key and a section's
 *       start do; without, it only starts what is not running.</li>
 *   <li>{@link #age} is a {@code desired_age} section's: the sequence's seconds since its start, which the system
 *       catches up to.</li>
 *   <li>A track naming a {@code handle} drives {@link #emitter} of it alone; a handle it has not fails at play, by
 *       name.</li>
 * </ul>
 *
 * <pre>{@code
 * tracks:
 *   - { id: t3, kind: system, binding: { spawnable: s1 }, handle: e01, sections: [ { id: a, range: [0, 25], keys: [[1, activate], [13, deactivate]] } ] }
 * }</pre>
 */
public interface CgSequenceSystem extends CgSequenceSpawned {

    /** Starts it: over from its beginning with {@code reset}, else only if it is not running. */
    void activate(boolean reset);

    /** No new particles; what is alive plays out. */
    void deactivate();

    /** Its particles cleared at once. */
    void kill();

    /** Leaves what it follows, in world space where it is, and plays on. */
    void detach();

    /** Whether it is running. */
    boolean active();

    /** Brings its age to {@code seconds}: a {@code desired_age} section's time since its start. */
    void age(float seconds);

    /**
     * Its emitter of handle {@code handle} as a system of its own, which a track naming the handle drives: activating
     * it starts that emitter alone (the system too, if it is not playing), deactivating it stops its spawning. Null if
     * it has no such handle, or drives no emitter apart.
     */
    @Nullable
    default CgSequenceSystem emitter(String handle) {
        return null;
    }
}
