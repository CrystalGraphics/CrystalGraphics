package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * Times the VFX engine's hot loops into per-frame counters on {@link CgChannels#VFX}: a stretch run thousands of times
 * a frame (an emitter's tick, a module's pass) adds its nanoseconds to a counter instead of recording a zone, which
 * would flood the trace.
 *
 * <pre>{@code
 * private static final int SOLVE_NS = CgTrace.name("vfx.sim.solve-ns");
 *
 * long t = CgVfxTrace.start();          // 0 while the channel is off: nothing below reads a clock
 * solve(dt);
 * t = CgVfxTrace.lap(SOLVE_NS, t);      // adds the stretch, and starts the next one
 * age(dt);
 * t = CgVfxTrace.lap(AGE_NS, t);
 * }</pre>
 *
 * <ul>
 *   <li>Counter names end in {@code -ns}: a report shows each frame's sum.</li>
 *   <li>Lap per loop, never per element: a clock read costs about 20 ns.</li>
 * </ul>
 */
public final class CgVfxTrace {

    public static final CgTraceChannel CHANNEL = CgChannels.VFX;

    private CgVfxTrace() {
    }

    /** A start for {@link #lap}, or 0 while the channel is off. */
    public static long start() {
        return CgTrace.stamp(CHANNEL);
    }

    /** Adds the nanoseconds since {@code start} to {@code nameId} and answers now; a 0 start adds nothing and answers 0. */
    public static long lap(int nameId, long start) {
        if (start == 0L) return 0L;
        long now = System.nanoTime();
        CgTrace.add(CHANNEL, nameId, now - start);
        return now;
    }

    /** Adds {@code delta} to the count {@code nameId}. */
    public static void count(int nameId, long delta) {
        CgTrace.add(CHANNEL, nameId, delta);
    }
}
