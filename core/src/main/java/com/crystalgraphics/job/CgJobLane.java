package com.crystalgraphics.job;

/**
 * How urgent a job is: which work a free worker takes first. Declared in urgency order, which is the policy.
 *
 * <pre>{@code
 * CgJob.lane(CgJobLane.INTERACTIVE).run(() -> complete(prefix));   // someone is waiting on it now
 * CgJob.lane(CgJobLane.BACKGROUND).run(() -> indexClasspath());    // nobody is watching
 * }</pre>
 *
 * <ul>
 *   <li>A job waiting past the starvation guard (two seconds) goes first whatever its lane, so background work under
 *       a steady stream of urgent work still runs.</li>
 *   <li>{@link #BACKGROUND} never takes the last worker: long work cannot occupy every thread an urgent job needs.</li>
 *   <li>A loop made with its own executor ignores lanes.</li>
 * </ul>
 */
public enum CgJobLane {

    /** Someone is mid-gesture and blocked on it. Keep it short: it runs ahead of everything. */
    INTERACTIVE,

    /** Visible, but a few frames late is fine: a simulation step, a reparse. The default. */
    LATENCY,

    /** Nobody is watching: an index, a download. */
    BACKGROUND
}
