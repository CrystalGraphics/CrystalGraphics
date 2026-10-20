package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bytes allocated per group, thread and frame: each thread sums what its groups allocated for the open frame, and the
 * totals are written here once a frame, at the frame thread's next {@link CgTrace#frameBegin}.
 *
 * <p>Not public; {@link CgTrace} writes and {@link CgTraceSnapshot} reads. Shaped like {@link CgTraceTally}: arrays
 * by name id, so a group's end allocates nothing and boxes nothing. A thread's lock is taken by that thread and, once
 * a frame, by the flush, so it is never contended.</p>
 */
final class CgTraceAllocations {

    /** Rows kept: a frame has a handful (groups times threads), so this is hundreds of frames. */
    private static final int CAPACITY = 1 << 14;

    private static final Object LOCK = new Object();
    private static final List<Tally> ALL = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Tally> LOCAL = ThreadLocal.withInitial(() -> {
        Tally tally = new Tally(Thread.currentThread().getName());
        ALL.add(tally);
        return tally;
    });

    private static final int[] GROUP = new int[CAPACITY];
    private static final String[] THREAD = new String[CAPACITY];
    private static final long[] FRAME = new long[CAPACITY];
    private static final long[] BYTES = new long[CAPACITY];
    private static long written;

    private CgTraceAllocations() {
    }

    static void add(int groupId, long frameIndex, long delta, int generation) {
        LOCAL.get().add(groupId, frameIndex, delta, generation);
    }

    /** Writes every thread's totals belonging to a frame before {@code frameIndex}. */
    static void flushBefore(long frameIndex, int generation) {
        for (Tally tally : ALL) tally.flushBefore(frameIndex, generation);
    }

    static void clear() {
        synchronized (LOCK) {
            written = 0L;
        }
    }

    /** What the frame's groups allocated, by group and thread. */
    static List<CgTraceSnapshot.AllocationView> in(long frameIndex) {
        List<CgTraceSnapshot.AllocationView> out = new ArrayList<>();
        for (CgTraceSnapshot.FrameAllocation row : all()) if (row.frame() == frameIndex) out.add(row.view());
        return out;
    }

    /** Every row held, with its frame: what a snapshot copies. */
    static List<CgTraceSnapshot.FrameAllocation> all() {
        List<CgTraceSnapshot.FrameAllocation> out = new ArrayList<>();
        synchronized (LOCK) {
            for (long slot = Math.max(0L, written - CAPACITY); slot < written; slot++) {
                int at = (int) (slot % CAPACITY);
                out.add(new CgTraceSnapshot.FrameAllocation(FRAME[at],
                        new CgTraceSnapshot.AllocationView(CgTraceNames.nameOf(GROUP[at]), THREAD[at], BYTES[at])));
            }
        }
        return out;
    }

    private static void write(int groupId, String threadName, long frameIndex, long total) {
        if (frameIndex < 0L) return;
        synchronized (LOCK) {
            int at = (int) (written++ % CAPACITY);
            GROUP[at] = groupId;
            THREAD[at] = threadName;
            FRAME[at] = frameIndex;
            BYTES[at] = total;
        }
    }

    /** One thread's running totals. */
    private static final class Tally {
        private final String name;
        private int generation = -1;
        private long[] sum = new long[16];
        private long[] frames = filled(16);
        private int[] touched = new int[8];
        private int touchedCount;

        Tally(String name) {
            this.name = name;
        }

        synchronized void add(int id, long frameIndex, long delta, int currentGeneration) {
            if (currentGeneration != generation) reset(currentGeneration);
            if (id >= frames.length) grow(id);
            long held = frames[id];
            if (held != frameIndex) {
                if (held >= 0L) {
                    write(id, name, held, sum[id]);
                } else {
                    if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touchedCount * 2);
                    touched[touchedCount++] = id;
                }
                frames[id] = frameIndex;
                sum[id] = 0L;
            }
            sum[id] += delta;
        }

        synchronized void flushBefore(long frameIndex, int currentGeneration) {
            if (currentGeneration != generation) {
                reset(currentGeneration);
                return;
            }
            for (int i = touchedCount - 1; i >= 0; i--) {
                int id = touched[i];
                if (frames[id] >= frameIndex) continue;
                write(id, name, frames[id], sum[id]);
                frames[id] = -1L;
                touched[i] = touched[--touchedCount];
            }
        }

        /** A clear since: every total held is for a frame that no longer exists. */
        private void reset(int currentGeneration) {
            for (int i = 0; i < touchedCount; i++) frames[touched[i]] = -1L;
            touchedCount = 0;
            generation = currentGeneration;
        }

        private void grow(int id) {
            int size = Math.max(id + 1, frames.length * 2);
            sum = Arrays.copyOf(sum, size);
            long[] grown = filled(size);
            System.arraycopy(frames, 0, grown, 0, frames.length);
            frames = grown;
        }

        private static long[] filled(int size) {
            long[] out = new long[size];
            Arrays.fill(out, -1L);
            return out;
        }
    }
}
