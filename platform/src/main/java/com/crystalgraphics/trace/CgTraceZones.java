package com.crystalgraphics.trace;

/**
 * One thread's zone arena: parallel primitive arrays, written only by the thread that owns them.
 *
 * <p>Not public. {@link CgTrace} owns the lifecycle and {@link CgTraceSnapshot} does the reading; this
 * is the storage and the LIFO discipline, and nothing else.</p>
 *
 * <h3>The layout, and why it is four arrays rather than one object per zone</h3>
 *
 * <p>24 bytes a zone, no references, no allocation after construction. A frame in the workbench records
 * on the order of two hundred zones, so a 64Ki arena holds around three hundred frames of one thread's
 * history — which is what lets the viewer scroll a window of frames back without a heap that grows with
 * it. An object per zone would make every arena a scanned reference array and put the collector into
 * the thing being measured.</p>
 *
 * <h3>A ring addressed by an absolute counter</h3>
 *
 * <p>{@link #written} only ever increases and the physical slot is {@code written & mask}. That gives
 * the recycling check for free: a slot recorded as {@code n} is still ours exactly while
 * {@code written - n <= capacity}. Without it, a zone held open across more than a full arena of
 * activity would have its {@code end} written into whatever now occupies that slot — a corruption that
 * reads as a wrong number rather than as a failure.</p>
 */
final class CgTraceZones {

    /** An {@code end} that has not been written yet. Not zero: a zone may legitimately end at zero. */
    static final long OPEN = Long.MIN_VALUE;

    /** Deep enough for any real call tree; past it the tree is a recursion bug, not instrumentation. */
    private static final int MAX_DEPTH = 64;

    /** A push that could not be recorded. Kept on the stack so the matching pop still balances. */
    private static final int DROPPED = -1;

    private final int capacity;
    private final int mask;

    final String threadName;
    final int threadId;

    final long[] start;
    final long[] end;
    final int[] nameId;
    final int[] packed;

    /** Absolute count of zones ever begun on this thread; the physical slot is this masked. */
    long written;

    private final int[] stack = new int[MAX_DEPTH];
    private int depth;

    /** Zones lost to a full arena or to a call tree deeper than {@link #MAX_DEPTH}. Reported, never hidden. */
    long dropped;

    /** Pops with nothing open — a leaked zone somewhere. @see CgTrace#frameBegin */
    long unbalanced;

    /**
     * Open span ids on this thread, innermost last — what gives a span its parent.
     *
     * <p>Here rather than in the span arena because parentage is a property of <em>this thread's</em>
     * call stack, and here rather than in a second {@code ThreadLocal} because one lookup per thread is
     * already one more than free.</p>
     */
    private final long[] spanStack = new long[MAX_DEPTH];
    private int spanDepth;

    /** The span a new one on this thread nests inside, or {@link CgTraceEvents#NO_PARENT}. */
    long currentSpan() {
        return spanDepth == 0 ? CgTraceEvents.NO_PARENT : spanStack[spanDepth - 1];
    }

    void pushSpan(long id) {
        if (spanDepth < spanStack.length) spanStack[spanDepth] = id;
        spanDepth++;
    }

    /** @return the span to close, or {@link CgTraceEvents#NO_PARENT} if the stack was empty or too deep */
    long popSpan() {
        if (spanDepth == 0) {
            unbalanced++;
            return CgTraceEvents.NO_PARENT;
        }
        spanDepth--;
        return spanDepth < spanStack.length ? spanStack[spanDepth] : CgTraceEvents.NO_PARENT;
    }

    CgTraceZones(String threadName, int threadId, int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Zone arena capacity must be a power of two: " + capacity);
        }
        this.threadName = threadName;
        this.threadId = threadId;
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.start = new long[capacity];
        this.end = new long[capacity];
        this.nameId = new int[capacity];
        this.packed = new int[capacity];
    }

    int capacity() {
        return capacity;
    }

    int depth() {
        return depth;
    }

    /** Opens a zone. The caller has already checked the channel. */
    void push(int name, int channelIndex, long now) {
        if (depth >= MAX_DEPTH) {
            dropped++;
            // NOT a silent return: the matching pop must still find something, or every zone after it
            // in this frame closes one level too high and the whole tree shifts.
            if (depth < stack.length) stack[depth] = DROPPED;
            depth++;
            return;
        }
        long slot = written++;
        int at = (int) (slot & mask);
        start[at] = now;
        end[at] = OPEN;
        nameId[at] = name;
        packed[at] = pack(channelIndex, depth, threadId);
        stack[depth++] = (int) slot;
    }

    /**
     * Writes a zone that is already over, at the current depth, without touching the stack.
     *
     * <p>For an instrumentation shape that is an <em>additive bucket</em> rather than a stack — a
     * start stamp taken here and a duration attributed there, which is what
     * {@code FrameProfile.begin()/end(t, bucket)} has always been. Recording those as push/pop would
     * impose a nesting discipline they never had; recording them at the current depth means they nest
     * correctly the moment an enclosing bracket becomes a real zone, and read as siblings until then.</p>
     */
    void record(int name, int channelIndex, long startNanos, long endNanos) {
        long slot = written++;
        int at = (int) (slot & mask);
        start[at] = startNanos;
        end[at] = endNanos;
        nameId[at] = name;
        packed[at] = pack(channelIndex, depth, threadId);
    }

    /** Closes the innermost open zone. */
    void pop(long now) {
        if (depth == 0) {
            unbalanced++;
            return;
        }
        depth--;
        if (depth >= stack.length) return;
        int slot = stack[depth];
        if (slot == DROPPED) return;
        // Recycled while it was open: writing an end now would land on somebody else's zone.
        if (written - Integer.toUnsignedLong(slot) > capacity) {
            dropped++;
            return;
        }
        end[slot & mask] = now;
    }

    /**
     * Force-closes everything still open, at {@code now}.
     *
     * <p>Called at a frame boundary. A zone left open across one is a leak, and in a depth-carrying
     * arena it corrupts every zone after it rather than only its own row — so the boundary closes it
     * loudly instead of letting the damage spread.
     *
     * @return how many were open
     */
    int closeOpen(long now) {
        int leaked = depth;
        while (depth > 0) pop(now);
        return leaked;
    }

    /** The oldest absolute slot still held. */
    long oldest() {
        return Math.max(0L, written - capacity);
    }

    long at(long slot, long[] values) {
        return values[(int) (slot & mask)];
    }

    int at(long slot, int[] values) {
        return values[(int) (slot & mask)];
    }

    private static int pack(int channelIndex, int depth, int threadId) {
        return (channelIndex & 0x3F) | ((depth & 0xFF) << 6) | ((threadId & 0x3FF) << 14);
    }

    static int channelOf(int packed) {
        return packed & 0x3F;
    }

    static int depthOf(int packed) {
        return (packed >>> 6) & 0xFF;
    }

    static int threadOf(int packed) {
        return (packed >>> 14) & 0x3FF;
    }
}
