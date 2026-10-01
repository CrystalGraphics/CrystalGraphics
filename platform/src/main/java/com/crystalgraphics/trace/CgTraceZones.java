package com.crystalgraphics.trace;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

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

    /**
     * The arrays, in pages, replaced by a bigger store when the arena grows — and read through one reference,
     * so a reader on another thread sees one consistent store however the owner is growing it.
     *
     * <p><b>Growing copies nothing once a store is a page or more.</b> An arena grows only when it is exactly
     * full and has never wrapped, so every slot already written keeps its index in a store twice the size: the
     * bigger store takes this one's pages and adds new ones. Copying the arrays instead allocated and filled
     * arrays of millions of zones inside whichever zone push filled the arena, 40-170 ms charged to an
     * unrelated zone (plan desktop-hitches #1).</p>
     */
    static final class Store {
        /** Zones a page holds; a store smaller than this is one page of its own size. */
        static final int PAGE_BITS = 16;

        final int capacity;
        final int mask;
        private final int pageBits;
        private final int pageMask;
        final long[][] start;
        final long[][] end;
        final int[][] nameId;
        final int[][] packed;

        /**
         * The absolute count at which a bigger store replaced this one. A reader holding this store
         * clamps to it: slots written after it went only to the replacement.
         */
        volatile long retiredAt = Long.MAX_VALUE;

        Store(int capacity) {
            this.capacity = capacity;
            this.mask = capacity - 1;
            this.pageBits = Math.min(PAGE_BITS, Integer.numberOfTrailingZeros(capacity));
            this.pageMask = (1 << pageBits) - 1;
            int pages = capacity >>> pageBits;
            this.start = new long[pages][];
            this.end = new long[pages][];
            this.nameId = new int[pages][];
            this.packed = new int[pages][];
            addPages(0);
        }

        /** Twice {@code from}'s capacity, sharing its pages. */
        private Store(Store from) {
            this.capacity = from.capacity << 1;
            this.mask = capacity - 1;
            this.pageBits = from.pageBits;
            this.pageMask = from.pageMask;
            int pages = capacity >>> pageBits;
            this.start = Arrays.copyOf(from.start, pages);
            this.end = Arrays.copyOf(from.end, pages);
            this.nameId = Arrays.copyOf(from.nameId, pages);
            this.packed = Arrays.copyOf(from.packed, pages);
            addPages(from.start.length);
        }

        private void addPages(int fromPage) {
            int size = 1 << pageBits;
            for (int page = fromPage; page < start.length; page++) {
                start[page] = new long[size];
                end[page] = new long[size];
                nameId[page] = new int[size];
                packed[page] = new int[size];
            }
        }

        /**
         * A store twice the size holding everything written so far, which is all of this one: an arena grows only
         * when full and before it first wraps. Shares the pages from a page up; below that copies a page at most.
         */
        Store grown() {
            if (pageBits == PAGE_BITS) return new Store(this);
            Store grown = new Store(capacity << 1);
            for (long slot = 0; slot < capacity; slot++) {
                grown.set(slot, start(slot), end(slot), nameId(slot), packed(slot));
            }
            return grown;
        }

        /** Whether {@code other} holds this store's pages: what a grown store does once paged. */
        boolean sharesPagesWith(Store other) {
            return start[0] == other.start[0];
        }

        void set(long slot, long startNanos, long endNanos, int name, int pack) {
            int i = (int) (slot & mask);
            int page = i >>> pageBits, at = i & pageMask;
            start[page][at] = startNanos;
            end[page][at] = endNanos;
            nameId[page][at] = name;
            packed[page][at] = pack;
        }

        void setEnd(long slot, long endNanos) {
            int i = (int) (slot & mask);
            end[i >>> pageBits][i & pageMask] = endNanos;
        }

        long start(long slot) {
            int i = (int) (slot & mask);
            return start[i >>> pageBits][i & pageMask];
        }

        long end(long slot) {
            int i = (int) (slot & mask);
            return end[i >>> pageBits][i & pageMask];
        }

        int nameId(long slot) {
            int i = (int) (slot & mask);
            return nameId[i >>> pageBits][i & pageMask];
        }

        int packed(long slot) {
            int i = (int) (slot & mask);
            return packed[i >>> pageBits][i & pageMask];
        }
    }

    /** Grown up to {@link #maxCapacity}, so a thread that records little never holds a large arena. */
    volatile Store store;
    private final int maxCapacity;
    /** Keep the first zones and drop the rest once full, rather than overwriting the oldest. */
    final boolean keepFirst;

    final String threadName;
    final int threadId;

    /**
     * Absolute count of zones ever begun on this thread; the physical slot is this masked. Published with a
     * release store after the slot is filled, so a reader on another thread never counts a slot whose
     * contents are still the previous lap's.
     */
    volatile long written;

    private static final AtomicLongFieldUpdater<CgTraceZones> WRITTEN =
            AtomicLongFieldUpdater.newUpdater(CgTraceZones.class, "written");

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
        this(threadName, threadId, capacity, capacity, false);
    }

    /**
     * @param initialCapacity what is allocated now; doubled as needed up to {@code maxCapacity}
     * @param keepFirst       drop once {@code maxCapacity} is full instead of overwriting the oldest
     */
    CgTraceZones(String threadName, int threadId, int initialCapacity, int maxCapacity, boolean keepFirst) {
        if (Integer.bitCount(initialCapacity) != 1 || Integer.bitCount(maxCapacity) != 1
                || initialCapacity > maxCapacity) {
            throw new IllegalArgumentException("Zone arena capacities must be powers of two, initial <= max: "
                    + initialCapacity + ", " + maxCapacity);
        }
        this.threadName = threadName;
        this.threadId = threadId;
        this.maxCapacity = maxCapacity;
        this.keepFirst = keepFirst;
        this.store = new Store(initialCapacity);
    }

    int capacity() {
        return store.capacity;
    }

    /**
     * The store the next zone goes into — grown first if full and allowed to — or null when a
     * keep-first arena is full and the zone must be dropped.
     */
    private Store room() {
        Store current = store;
        if (written < current.capacity) return current;
        if (current.capacity < maxCapacity) {
            Store grown = current.grown();
            // RETIRED BEFORE IT IS REPLACED, so a reader that still holds it never reads past this point.
            current.retiredAt = written;
            store = grown;
            return grown;
        }
        return keepFirst ? null : current;
    }

    /** The first absolute slot past what {@code held} can answer for. */
    long highFor(Store held) {
        return Math.min(written, held.retiredAt);
    }

    /**
     * The first slot of {@code held} the owner cannot have overwritten by now. A retired store shares its pages
     * with the current one, which overwrites them once it wraps, so both stores' laps bound it.
     */
    long firstUnoverwritten(Store held) {
        if (keepFirst) return highFor(held) - held.capacity;
        long lapOfHeld = highFor(held) - held.capacity + 1;
        long lapOfCurrent = written - store.capacity + 1;
        return Math.max(lapOfHeld, lapOfCurrent);
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
        Store into = room();
        if (into == null) {
            dropped++;
            stack[depth++] = DROPPED;
            return;
        }
        long slot = written;
        into.set(slot, now, OPEN, name, pack(channelIndex, depth, threadId));
        WRITTEN.lazySet(this, slot + 1);
        stack[depth++] = (int) slot;
        CgTraceNames.seen(name, now);
    }

    /**
     * Writes a zone that is already over, at the current depth, without touching the stack.
     *
     * <p>For an instrumentation shape that is an <em>additive bucket</em> rather than a stack — a
     * start stamp taken here and a duration attributed there, which is what
     * {@code CgTrace.stamp()} then {@code zoneDone(channel, bucket, t)} is. Recording those as push/pop would
     * impose a nesting discipline they never had; recording them at the current depth means they nest
     * correctly the moment an enclosing bracket becomes a real zone, and read as siblings until then.</p>
     */
    void record(int name, int channelIndex, long startNanos, long endNanos) {
        Store into = room();
        if (into == null) {
            dropped++;
            return;
        }
        long slot = written;
        into.set(slot, startNanos, endNanos, name, pack(channelIndex, depth, threadId));
        WRITTEN.lazySet(this, slot + 1);
        CgTraceNames.seen(name, startNanos);
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
        Store held = store;
        // Recycled while it was open: writing an end now would land on somebody else's zone.
        if (written - Integer.toUnsignedLong(slot) > held.capacity) {
            dropped++;
            return;
        }
        held.setEnd(Integer.toUnsignedLong(slot), now);
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
