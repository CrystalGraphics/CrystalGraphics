package com.crystalgraphics.job;

import java.util.function.LongSupplier;

/**
 * Which queued job a free worker takes: starved first, then by lane, then first come; background never on the last
 * thread. CrystalGUI's {@code JobScheduler} policy, moved from its drain to the pool so every loop shares it. Not
 * thread-safe: {@link CgJobPool} holds its lock around every call. Allocates nothing: entries link themselves.
 */
final class CgJobQueue {

    static final long STARVATION_GUARD_NANOS = 2_000_000_000L;
    private static final int BACKGROUND = CgJobLane.BACKGROUND.ordinal();

    /** What the queue holds: a job, linked into its lane's list. */
    abstract static class Entry {
        CgJobLane lane = CgJobLane.LATENCY;
        long queuedAtNanos;
        Entry next;

        abstract void runQueued();
    }

    private final Entry[] heads = new Entry[CgJobLane.values().length];
    private final Entry[] tails = new Entry[heads.length];
    private final int threads;
    private final LongSupplier nanos;
    private int backgroundRunning;

    CgJobQueue(int threads, LongSupplier nanos) {
        this.threads = threads;
        this.nanos = nanos;
    }

    void add(Entry e) {
        int lane = e.lane.ordinal();
        e.queuedAtNanos = nanos.getAsLong();
        e.next = null;
        if (tails[lane] == null) heads[lane] = e;
        else tails[lane].next = e;
        tails[lane] = e;
    }

    /** The entry to run next, or null when none may run now. A background one counts until {@link #finished}. */
    Entry take() {
        boolean background = threads <= 1 || backgroundRunning < threads - 1;
        long now = nanos.getAsLong();
        int pick = -1;
        for (int lane = 0; lane < heads.length && pick < 0; lane++) {
            Entry head = heads[lane];
            if (head != null && (lane != BACKGROUND || background)
                    && now - head.queuedAtNanos >= STARVATION_GUARD_NANOS) pick = lane;
        }
        for (int lane = 0; lane < heads.length && pick < 0; lane++) {
            if (heads[lane] != null && (lane != BACKGROUND || background)) pick = lane;
        }
        if (pick < 0) return null;
        Entry e = heads[pick];
        heads[pick] = e.next;
        if (heads[pick] == null) tails[pick] = null;
        e.next = null;
        if (pick == BACKGROUND) backgroundRunning++;
        return e;
    }

    /** {@code e}, taken by {@link #take}, has run. */
    void finished(Entry e) {
        if (e.lane == CgJobLane.BACKGROUND) backgroundRunning--;
    }
}
