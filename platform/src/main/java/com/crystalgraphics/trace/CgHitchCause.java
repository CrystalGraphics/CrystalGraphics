package com.crystalgraphics.trace;

/**
 * What a hitch can be blamed on, in the order {@link CgHitches#causesOf} breaks ties.
 *
 * <pre>{@code
 * CgHitch hitch = CgTrace.snapshot().hitches(16_700_000L).get(0);
 * if (hitch.top().kind() == CgHitchCause.GC_PAUSE) System.out.println(hitch.top().detail());
 * }</pre>
 */
public enum CgHitchCause {
    /** A collection paused the JVM; the detail names the collector and the JVM's own cause. */
    GC_PAUSE,
    /** All threads stopped at a safepoint. */
    SAFEPOINT,
    /** Classes loaded inside the frame. */
    CLASS_LOAD,
    /** The engine waited: a counter ending {@code .compile-waits} or {@code .sync-loads} was above zero. Ours. */
    ENGINE_WAIT,
    /** The frame thread allocated at least {@link CgHitches#ALLOCATION_BURST_BYTES}. Ours when named groups did it. */
    ALLOCATION,
    /** Compiled code was abandoned for the interpreter. An instant, so it has no overlap. */
    DEOPTIMISATION,
    /** The JIT compiled beside the frame thread. Context only: it never explains a hitch alone. */
    COMPILATION
}
