package com.crystalgraphics.trace;

import java.util.List;

/**
 * One slow frame, with what the trace saw in it, most likely cause first.
 *
 * <pre>{@code
 * for (CgHitch hitch : CgTrace.snapshot().hitches(16_700_000L)) {
 *     if (!hitch.explained()) System.out.println("#" + hitch.frame().index() + " unexplained");
 *     else if (hitch.ours()) throw new AssertionError(hitch.top().detail());
 * }
 * }</pre>
 *
 * <p>Easy to get wrong: a hitch with only {@link CgHitchCause#COMPILATION} causes is not explained, and
 * {@link #top()} is then null.</p>
 *
 * @param medianNanos the rolling median the frame was judged against
 * @param causes      ranked by {@link CgHitches#causesOf}; empty when the trace saw nothing
 */
public record CgHitch(CgFrameRecord frame, long medianNanos, CgHitchBound bound, List<Cause> causes) {

    /**
     * One cause.
     *
     * @param overlapNanos the time of the frame the cause covers, clipped to the frame; 0 for what has no duration
     * @param ours         whether the engine, not the host or the JVM, is to blame
     */
    public record Cause(CgHitchCause kind, long overlapNanos, boolean ours, String detail) {
    }

    public CgHitch {
        causes = List.copyOf(causes);
    }

    /** Whether any cause but a compilation was seen. */
    public boolean explained() {
        return top() != null;
    }

    /** Whether any cause is ours. */
    public boolean ours() {
        for (Cause each : causes) if (each.ours()) return true;
        return false;
    }

    /** The leading cause, or null when the hitch is unexplained. */
    public Cause top() {
        for (Cause each : causes) if (each.kind() != CgHitchCause.COMPILATION) return each;
        return null;
    }
}
