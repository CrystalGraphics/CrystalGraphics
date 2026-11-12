package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;

/**
 * Storage async compute touched that the frame's queue has not waited for, each with the fence point covering it:
 * across a frame's executions, dropped at the next frame, whose start the device orders after all of it. The device's,
 * as Unreal keeps its async compute fences on the RHI, not on a graph.
 */
final class CgAsyncWaits {

    private static final int ASYNC_WAITS = CgTrace.name("graph.async-waits");

    /** Open-addressed by {@link CgResourceStates} key, 0 a free entry (no key is 0): a pass names hundreds. */
    private long[] keys = new long[64], points = new long[64];
    private int count;
    private long[] keptKeys = new long[64], keptPoints = new long[64];
    /** The newest point submitted, and the newest the frame's queue waited for. */
    long latest, waited;
    /** The frame {@link #latest} was submitted in. */
    private long frame = -1;
    /** Whether some point is not waited for yet. */
    boolean unwaited;

    /** At an execution's start: a new frame's start waited for all of the last one's. */
    void begin() {
        if (unwaited && CgFrameRing.frame() != frame) {
            clear();
            unwaited = false;
        }
    }

    /** Async work ending at {@code point} was submitted. */
    void submitted(long point) {
        latest = point;
        unwaited = true;
        frame = CgFrameRing.frame();
    }

    /** The newest point the frame's queue has waited for: the newest of all, once none is left unwaited. */
    long joined() {
        return unwaited ? waited : latest;
    }

    /** The newest point not yet waited for on {@code storage}, under either name a step keys it by; else 0. */
    long pending(CgGraphStorage storage) {
        if (!unwaited) return 0;
        long point = point(storage.key());
        CgGpuTexture color = storage.color(0);
        return color == null ? point : Math.max(point, point(CgGraphStorage.key(color)));
    }

    /** The point pending on {@code key}; 0 for none. */
    long point(long key) {
        int j = entry(key);
        return keys[j] != 0 ? points[j] : 0;
    }

    /** The frame's queue waits for {@code point}; what it covered is no longer pending. */
    void await(long point) {
        if (point <= waited) return;
        CgRHI.get().encoder().waitAsync(point);
        waited = point;
        CgTrace.add(CgChannels.GL, ASYNC_WAITS, 1);
        int kept = 0;
        if (keptKeys.length < keys.length) {
            keptKeys = new long[keys.length];
            keptPoints = new long[keys.length];
        }
        for (int j = 0; j < keys.length; j++) {
            if (keys[j] == 0 || points[j] <= point) continue;
            keptKeys[kept] = keys[j];
            keptPoints[kept++] = points[j];
        }
        clear();
        for (int j = 0; j < kept; j++) pend(keptKeys[j], keptPoints[j]);
        unwaited = point < latest;
    }

    /** {@code key} is pending on {@code point}. */
    void pend(long key, long point) {
        if ((count + 1) * 2 > keys.length) {
            long[] oldKeys = keys, oldPoints = points;
            keys = new long[oldKeys.length * 2];
            points = new long[oldKeys.length * 2];
            count = 0;
            for (int j = 0; j < oldKeys.length; j++) if (oldKeys[j] != 0) pend(oldKeys[j], oldPoints[j]);
        }
        int j = entry(key);
        if (keys[j] == 0) {
            keys[j] = key;
            count++;
        }
        points[j] = point;
    }

    /** The next device's timelines start again at 0. */
    void reset() {
        clear();
        latest = waited = 0;
        frame = -1;
        unwaited = false;
    }

    private void clear() {
        if (count > 0) Arrays.fill(keys, 0L);
        count = 0;
    }

    /** {@code key}'s entry, or the free entry where it would go. */
    private int entry(long key) {
        int mask = keys.length - 1, j = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
        while (keys[j] != 0 && keys[j] != key) j = (j + 1) & mask;
        return j;
    }
}
