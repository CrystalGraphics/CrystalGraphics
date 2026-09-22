package com.crystalgraphics.trace;

/**
 * One frame, as the ring holds it — what a bar in a frame strip is, and what a report addresses.
 *
 * <pre>{@code
 * CgTraceSnapshot snap = CgTrace.snapshot();
 * CgFrameRecord worst = snap.worstFrame();
 * worst.index();          // 412 — stable, never reused
 * worst.wallMillis();     // 35.1
 * snap.zonesIn(worst);    // what it spent that on
 * }</pre>
 *
 * <h3>Wall and CPU are different questions</h3>
 *
 * <p>{@link #wallNanos()} is the interval to the next frame — what the frame rate is made of, vsync
 * and all. {@link #cpuNanos()} is what the frame spent between the host's first line and the painter's
 * last. Under vsync the first sits at the refresh rate while the second says how much headroom is left,
 * so a readout showing only the first reports 60fps right up until it collapses.</p>
 *
 * <h3>Absent is not zero</h3>
 *
 * <p>{@link #cpuNanos()} is {@code -1} when the host never called {@code frameEnd} — a document framed
 * without being painted, which is an ordinary thing for a test or a minimised window — and
 * {@link #gpuNanos()} is {@code -1} until a timer query resolves, which is typically one to three
 * frames later. Both must be <em>shown</em> as absent. A zero here would read as "the GPU did nothing",
 * which is the most misleading number a profiler can print.</p>
 */
public record CgFrameRecord(
        long index,
        long beginNanos,
        long endNanos,
        long cpuNanos,
        long gpuNanos,
        long gcMillis,
        int gcCollections,
        int leakedZones,
        long droppedZones) {

    /** No CPU mark arrived for this frame. @see #cpuNanos() */
    public static final long ABSENT = -1L;

    /** The interval to the next frame's start. */
    public long wallNanos() {
        return endNanos - beginNanos;
    }

    public double wallMillis() {
        return wallNanos() / 1_000_000d;
    }

    public boolean hasCpu() {
        return cpuNanos != ABSENT;
    }

    public double cpuMillis() {
        return cpuNanos / 1_000_000d;
    }

    public boolean hasGpu() {
        return gpuNanos != ABSENT;
    }

    public double gpuMillis() {
        return gpuNanos / 1_000_000d;
    }

    /**
     * Whether a collection ran inside this frame — asked of the COUNT, since a young pause under a
     * millisecond adds nothing to {@link #gcMillis()} and still happened.
     */
    public boolean hadGc() {
        return gcCollections > 0;
    }

    /**
     * The collection time as a reader wants it — {@code "6 ms"}, {@code "<1 ms"} for a pause too short
     * to reach a millisecond, and the count after it when there was more than one.
     */
    public String gcSummary() {
        String time = gcMillis > 0L ? gcMillis + " ms" : "<1 ms";
        return gcCollections > 1 ? time + " (" + gcCollections + " collections)" : time;
    }

    /** Whether this frame covers {@code nanos} — how zones are attributed to it. */
    public boolean contains(long nanos) {
        return nanos >= beginNanos && nanos < endNanos;
    }
}
