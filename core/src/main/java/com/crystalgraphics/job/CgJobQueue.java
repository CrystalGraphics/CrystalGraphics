package com.crystalgraphics.job;

import java.util.function.LongSupplier;

/**
 * Which queued job a free worker takes: starved first, then by lane, then first come; the last thread only ever
 * interactive, and background work held to its cap. CrystalGUI's {@code JobScheduler} policy, moved from its drain to the pool so every loop shares it. Not
 * thread-safe: {@link CgJobPool} holds its lock around every call. Allocates nothing: entries link themselves.
 *
 * <p>The last thread is kept from LATENCY as well as BACKGROUND work: kept from background only, a glyph a screen
 * waited on queued ~23 ms behind a loading task on the one thread distance fields left free.</p>
 */
final class CgJobQueue {

    static final long STARVATION_GUARD_NANOS = 2_000_000_000L;
    private static final int INTERACTIVE = CgJobLane.INTERACTIVE.ordinal();
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
    private final int backgroundCap;
    private final LongSupplier nanos;
    /** Latency and background jobs running: never every thread. */
    private int deferrableRunning;
    private int backgroundRunning;

    /** No cap on background work beyond the last thread's. */
    CgJobQueue(int threads, LongSupplier nanos) {
        this(threads, threads, nanos);
    }

    /** @param backgroundCap background jobs running at once, at most */
    CgJobQueue(int threads, int backgroundCap, LongSupplier nanos) {
        this.threads = threads;
        this.backgroundCap = Math.max(1, backgroundCap);
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

    /** The entry to run next, or null when none may run now. A non-interactive one counts until {@link #finished}. */
    Entry take() {
        boolean deferrable = threads <= 1 || deferrableRunning < threads - 1;
        boolean background = deferrable && backgroundRunning < backgroundCap;
        long now = nanos.getAsLong();
        int pick = -1;
        for (int lane = 0; lane < heads.length && pick < 0; lane++) {
            Entry head = heads[lane];
            if (head != null && may(lane, deferrable, background)
                    && now - head.queuedAtNanos >= STARVATION_GUARD_NANOS) pick = lane;
        }
        for (int lane = 0; lane < heads.length && pick < 0; lane++) {
            if (heads[lane] != null && may(lane, deferrable, background)) pick = lane;
        }
        if (pick < 0) return null;
        Entry e = heads[pick];
        heads[pick] = e.next;
        if (heads[pick] == null) tails[pick] = null;
        e.next = null;
        if (pick != INTERACTIVE) deferrableRunning++;
        if (pick == BACKGROUND) backgroundRunning++;
        return e;
    }

    private static boolean may(int lane, boolean deferrable, boolean background) {
        return lane == INTERACTIVE || (lane == BACKGROUND ? background : deferrable);
    }

    /** {@code e}, taken by {@link #take}, has run. */
    void finished(Entry e) {
        if (e.lane != CgJobLane.INTERACTIVE) deferrableRunning--;
        if (e.lane == CgJobLane.BACKGROUND) backgroundRunning--;
    }
}
