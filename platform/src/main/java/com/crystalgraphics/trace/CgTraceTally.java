package com.crystalgraphics.trace;

import java.util.Arrays;

/**
 * One thread's running totals for {@link CgTrace#add}: a sum per name for the frame it belongs to,
 * written as that frame's counter value once the thread adds to it in a later frame, or at the frame
 * thread's next {@link CgTrace#frameBegin}.
 *
 * <p>Arrays indexed by name id, so an add allocates nothing and boxes nothing. A name is "touched" while
 * it holds a total not yet written.</p>
 */
final class CgTraceTally {

    private int generation = -1;
    private long[] sum = new long[64];
    private long[] frame = filled(64);
    private int[] touched = new int[16];
    private int touchedCount;

    void add(int nameId, long frameIndex, long delta, int currentGeneration) {
        if (currentGeneration != generation) reset(currentGeneration);
        if (nameId >= frame.length) grow(nameId);
        long held = frame[nameId];
        if (held != frameIndex) {
            if (held >= 0L) {
                CgTrace.writeCounter(nameId, held, sum[nameId]);
            } else {
                if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touchedCount * 2);
                touched[touchedCount++] = nameId;
            }
            frame[nameId] = frameIndex;
            sum[nameId] = 0L;
        }
        sum[nameId] += delta;
    }

    /** Writes every total belonging to a frame before {@code frameIndex}. */
    void flushBefore(long frameIndex, int currentGeneration) {
        if (currentGeneration != generation) {
            reset(currentGeneration);
            return;
        }
        for (int i = touchedCount - 1; i >= 0; i--) {
            int id = touched[i];
            if (frame[id] >= frameIndex) continue;
            CgTrace.writeCounter(id, frame[id], sum[id]);
            frame[id] = -1L;
            touched[i] = touched[--touchedCount];
        }
    }

    /** A clear since: every total held is for a frame that no longer exists. */
    private void reset(int currentGeneration) {
        for (int i = 0; i < touchedCount; i++) frame[touched[i]] = -1L;
        touchedCount = 0;
        generation = currentGeneration;
    }

    private void grow(int nameId) {
        int size = Math.max(nameId + 1, frame.length * 2);
        sum = Arrays.copyOf(sum, size);
        long[] grown = filled(size);
        System.arraycopy(frame, 0, grown, 0, frame.length);
        frame = grown;
    }

    private static long[] filled(int size) {
        long[] out = new long[size];
        Arrays.fill(out, -1L);
        return out;
    }
}
