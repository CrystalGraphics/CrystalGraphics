package com.crystalgraphics.util.profiling;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GPU-side timing via {@code GL_TIME_ELAPSED} queries, as the counterpart to {@link CgProfiler}'s
 * CPU-side scopes.
 *
 * <p>Every performance number this engine produced before this class existed was CPU time. That is
 * a real blind spot rather than a small one: a frame can be entirely GPU-bound while every CPU scope
 * reports microseconds, and nothing in the CPU profile would hint at it. Fill rate, shader cost, and
 * texture bandwidth are invisible to a {@code System.nanoTime()} bracket, because the driver returns
 * from a draw call long before the GPU has finished it.
 *
 * <h3>Why results arrive late</h3>
 *
 * <p>Reading a query result in the frame it was issued forces the CPU to wait for the GPU to drain,
 * which both destroys the measurement and tanks the frame it is measuring. So results are polled
 * with a non-blocking availability check and collected some frames later — {@link #endFrame()} takes
 * whatever has become ready and leaves the rest. Timings are therefore attributed to the scope, not
 * to a particular frame, and are reported as an average over the run.
 *
 * <h3>Nesting is not allowed</h3>
 *
 * <p>OpenGL permits only one active {@code GL_TIME_ELAPSED} query at a time — this is a hardware
 * counter, not a stack. A nested {@link #begin} is therefore ignored rather than silently producing
 * garbage, and counted under {@code gpu.nestedIgnored} so the omission is visible instead of quietly
 * skewing a total.
 *
 * <h3>Availability</h3>
 *
 * <p>Requires GL 3.3 / {@code ARB_timer_query}. Where unavailable ({@link CgCapabilities#supportsTimerQueries})
 * every method is a no-op and {@link #report()} is empty — GPU timing is a diagnostic, and a context
 * that cannot provide it should still render.
 */
public final class CgGpuProfiler {

    /**
     * How many frames of in-flight queries to keep before recycling. Three is comfortably more than
     * the one-or-two frames a driver typically needs, and bounds the GL object count regardless of
     * how far behind the GPU falls.
     */
    private static final int MAX_IN_FLIGHT = 64;

    private static boolean enabled;
    private static boolean available;

    /** Query objects issued but not yet read back. */
    private static final List<Pending> pending = new ArrayList<>();
    /** Recycled query object ids, so steady-state costs no allocation. */
    private static final List<Integer> freeQueries = new ArrayList<>();
    /** Accumulated results per scope name. */
    private static final Map<String, Accum> totals = new LinkedHashMap<>();

    private static boolean queryActive;

    private CgGpuProfiler() {
    }

    private static final class Pending {
        final int query;
        final String name;

        Pending(int query, String name) {
            this.query = query;
            this.name = name;
        }
    }

    /** Total GPU nanoseconds and sample count for one scope. */
    public static final class Accum {
        long totalNanos;
        long samples;

        public long totalNanos() {
            return totalNanos;
        }

        public long samples() {
            return samples;
        }

        public double avgMillis() {
            return samples == 0 ? 0 : totalNanos / 1_000_000.0 / samples;
        }
    }

    /** Enables GPU timing if the context supports it. Safe to call on any context. */
    public static void enable() {
        available = CgCapabilities.detect().isTimerQueriesSupported();
        enabled = available;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Begins timing GPU work under {@code name}. Must be paired with {@link #end()}.
     *
     * <p>Ignored if another scope is already open — see the class note on nesting.
     */
    public static void begin(String name) {
        if (!enabled) return;
        if (queryActive) {
            CgProfiler.count("gpu.nestedIgnored");
            return;
        }
        if (pending.size() >= MAX_IN_FLIGHT) {
            // GPU is far enough behind that recycling would overwrite unread results. Skipping is
            // preferable to blocking: this profiler must not become the thing that stalls the frame.
            CgProfiler.count("gpu.droppedInFlightFull");
            return;
        }
        int query = freeQueries.isEmpty() ? CgGL.glGenQuery() : freeQueries.remove(freeQueries.size() - 1);
        CgGL.glBeginTimeElapsedQuery(query);
        pending.add(new Pending(query, name));
        queryActive = true;
    }

    public static void end() {
        if (!enabled || !queryActive) return;
        CgGL.glEndTimeElapsedQuery();
        queryActive = false;
    }

    /**
     * Collects whatever results the GPU has finished, without blocking. Call once per frame.
     *
     * <p>Stops at the first unfinished query rather than scanning the whole list: queries complete
     * in issue order, so anything after the first incomplete one is necessarily incomplete too.
     */
    public static void endFrame() {
        if (!enabled) return;
        int collected = 0;
        while (collected < pending.size()) {
            Pending p = pending.get(collected);
            if (!CgGL.glIsQueryResultAvailable(p.query)) break;
            long nanos = CgGL.glGetQueryResultNanos(p.query);
            Accum accum = totals.computeIfAbsent(p.name, k -> new Accum());
            accum.totalNanos += nanos;
            accum.samples++;
            freeQueries.add(p.query);
            collected++;
        }
        if (collected > 0) {
            pending.subList(0, collected).clear();
        }
    }

    /** @return accumulated GPU time per scope, insertion-ordered */
    public static Map<String, Accum> report() {
        return totals;
    }

    public static void reset() {
        totals.clear();
    }

    /** Releases every query object. GL thread only. */
    public static void dispose() {
        if (!available) return;
        for (Pending p : pending) CgGL.glDeleteQuery(p.query);
        for (Integer q : freeQueries) CgGL.glDeleteQuery(q);
        pending.clear();
        freeQueries.clear();
        totals.clear();
        queryActive = false;
        enabled = false;
    }
}
