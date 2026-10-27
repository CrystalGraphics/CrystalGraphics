package com.crystalgraphics.sequence;

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
 * </ul>
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
}
