package com.crystalgraphics.mc.render;

import com.crystalgraphics.trace.CgThreadAllocation;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

/**
 * What a draw point allocated of its own, Minecraft's host calls left out: the number its allocation gate reads (a steady
 * frame allocates nothing). Measures only while {@code channel} records; one per draw point, render thread.
 *
 * <pre>{@code
 * private static final CgHostAllocation ALLOCATION = new CgHostAllocation(CgChannels.PICTURES, "pictures-our-bytes");
 *
 * ALLOCATION.begin();
 * ...our bookkeeping...
 * long host = ALLOCATION.hostBegin();
 * host.draw(...);                          // Minecraft's code: its allocation is not ours
 * ALLOCATION.hostEnd(host);
 * ALLOCATION.end();                        // the counter "pictures-our-bytes" for this frame
 * }</pre>
 *
 * <ul>
 *   <li>{@link #end} is the bytes, or -1 where nothing was measured (the channel off, or the JVM not counting).</li>
 *   <li>Nested draw points each keep their own: a draw point inside another's host call counts as that host's.</li>
 * </ul>
 */
public final class CgHostAllocation {

    private final CgTraceChannel channel;
    private final String counter;
    private long mark = -1L, host;
    private volatile long last = -1L;

    public CgHostAllocation(CgTraceChannel channel, String counter) {
        this.channel = channel;
        this.counter = counter;
    }

    /** Opens a draw point's measure. */
    public void begin() {
        mark = CgTrace.isEnabled(channel) && CgThreadAllocation.available() ? CgThreadAllocation.current() : -1L;
        host = 0L;
    }

    /** Before a host call: a mark for {@link #hostEnd}, or -1 while not measuring. */
    public long hostBegin() {
        return mark < 0L ? -1L : CgThreadAllocation.current();
    }

    /** After a host call: what it allocated is Minecraft's. */
    public void hostEnd(long hostMark) {
        if (hostMark >= 0L) host += CgThreadAllocation.current() - hostMark;
    }

    /** What the last measured draw point allocated of its own, or -1 before any. Any thread. */
    public long last() {
        return last;
    }

    /** Closes the measure and writes the counter: our bytes this draw point, or -1 where unmeasured. */
    public long end() {
        if (mark < 0L) return -1L;
        long ours = CgThreadAllocation.current() - mark - host;
        mark = -1L;
        last = ours;
        CgTrace.counter(channel, counter, ours);
        return ours;
    }
}
