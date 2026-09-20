package com.crystalgraphics.trace;

/**
 * The three arenas that are not zones: counters, markers and spans.
 *
 * <p>Not public; {@link CgTrace} writes and {@link CgTraceSnapshot} reads.</p>
 *
 * <h3>Locked, and that is affordable here</h3>
 *
 * <p>Zones are per-thread and lock-free because a frame records hundreds of them. These three are
 * orders of magnitude rarer — a few counters and markers per frame, a span or two per user action —
 * so one lock costs nothing measurable and buys a single arena that any thread can write to. Making
 * them per-thread as well would mean merging three sets of arenas on every read for no gain.</p>
 *
 * <h3>Spans are the events that outlive a frame</h3>
 *
 * <p>A zone lives inside a frame. A span does not: opening a file is a command, a picker, a search per
 * keystroke, an accept, a dock open, a read and a parse, and what matters about it is the order and
 * where it stalled. Aggregating that into per-frame buckets loses exactly the part being asked about,
 * so spans have their own arena, evicted by age rather than by frame.</p>
 */
final class CgTraceEvents {

    /** A span begun and not yet ended. Not zero — a span may legitimately end at zero. */
    static final long OPEN = Long.MIN_VALUE;

    /** No parent: a chain's root. */
    static final long NO_PARENT = -1L;

    // ── Counters: a named number, one or more times per frame ────────────────────────────────

    private final int counterCapacity;
    private final int counterMask;
    final int[] counterName;
    final long[] counterFrame;
    final long[] counterValue;
    long countersWritten;

    // ── Markers: an instant, optionally attributed ───────────────────────────────────────────

    private final int markerCapacity;
    private final int markerMask;
    final int[] markerName;
    final long[] markerNanos;
    final int[] markerChannel;
    /** An interned detail string, or -1. What {@code blame} puts its call site in. */
    final int[] markerDetail;
    long markersWritten;

    // ── Spans: a nestable interval that is not bound to a frame ──────────────────────────────

    private final int spanCapacity;
    private final int spanMask;
    final int[] spanName;
    final long[] spanStart;
    final long[] spanEnd;
    final long[] spanParent;
    final int[] spanChannel;
    final int[] spanThread;
    long spansWritten;

    long dropped;

    CgTraceEvents(int counterCapacity, int markerCapacity, int spanCapacity) {
        this.counterCapacity = counterCapacity;
        this.counterMask = counterCapacity - 1;
        this.counterName = new int[counterCapacity];
        this.counterFrame = new long[counterCapacity];
        this.counterValue = new long[counterCapacity];

        this.markerCapacity = markerCapacity;
        this.markerMask = markerCapacity - 1;
        this.markerName = new int[markerCapacity];
        this.markerNanos = new long[markerCapacity];
        this.markerChannel = new int[markerCapacity];
        this.markerDetail = new int[markerCapacity];

        this.spanCapacity = spanCapacity;
        this.spanMask = spanCapacity - 1;
        this.spanName = new int[spanCapacity];
        this.spanStart = new long[spanCapacity];
        this.spanEnd = new long[spanCapacity];
        this.spanParent = new long[spanCapacity];
        this.spanChannel = new int[spanCapacity];
        this.spanThread = new int[spanCapacity];
    }

    /**
     * Records one value of a counter.
     *
     * <p><b>A counter may take several values in one frame</b>, and the summary is derived on read.
     * That is what absorbs the old {@code sample()} concept: its callers record a distribution —
     * min/max/avg over a period — and one value per frame per name would lose that at every site.
     * There is no second concept here; a "sample" is a counter written more than once.</p>
     */
    synchronized void counter(int name, long frameIndex, long value) {
        long slot = countersWritten++;
        int at = (int) (slot & counterMask);
        counterName[at] = name;
        counterFrame[at] = frameIndex;
        counterValue[at] = value;
    }

    synchronized void marker(int name, int channelIndex, long nanos, int detail) {
        long slot = markersWritten++;
        int at = (int) (slot & markerMask);
        markerName[at] = name;
        markerNanos[at] = nanos;
        markerChannel[at] = channelIndex;
        markerDetail[at] = detail;
    }

    /** @return the span's id, which {@link #spanEnd} takes back */
    synchronized long spanBegin(int name, int channelIndex, int threadId, long parent, long now) {
        long id = spansWritten++;
        int at = (int) (id & spanMask);
        spanName[at] = name;
        spanStart[at] = now;
        spanEnd[at] = OPEN;
        spanParent[at] = parent;
        spanChannel[at] = channelIndex;
        spanThread[at] = threadId;
        return id;
    }

    /** Closes a span begun earlier, ignoring one whose slot has since been recycled. */
    synchronized void spanEnd(long id, long now) {
        if (id < 0 || spansWritten - id > spanCapacity) {
            dropped++;
            return;
        }
        spanEnd[(int) (id & spanMask)] = now;
    }

    /** A span whose start is already known — the shape {@code step(started, what)} has. */
    synchronized long spanDone(int name, int channelIndex, int threadId, long parent,
                               long startNanos, long endNanos) {
        long id = spanBegin(name, channelIndex, threadId, parent, startNanos);
        spanEnd[(int) (id & spanMask)] = endNanos;
        return id;
    }

    long oldestCounter() {
        return Math.max(0L, countersWritten - counterCapacity);
    }

    long oldestMarker() {
        return Math.max(0L, markersWritten - markerCapacity);
    }

    long oldestSpan() {
        return Math.max(0L, spansWritten - spanCapacity);
    }

    int counterAt(long slot) {
        return (int) (slot & counterMask);
    }

    int markerAt(long slot) {
        return (int) (slot & markerMask);
    }

    int spanAt(long slot) {
        return (int) (slot & spanMask);
    }
}
