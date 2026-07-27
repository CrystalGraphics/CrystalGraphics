package com.crystalgraphics.util.profiling;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * General-purpose, low-overhead profiler: hierarchical scoped timers (a call-tree, like
 * Minecraft's own {@code ProfilerFiller}), flat named event counters, and flat named numeric
 * samples (min/max/avg/last) — everything needed to answer "where did the time/calls go"
 * across an arbitrary call chain, without pulling in a sampling profiler or touching native
 * (JNI) frames that tools like async-profiler can't always resolve cleanly.
 *
 * <h3>Zero-cost when disabled</h3>
 * <p>{@link #setEnabled(boolean)} defaults to {@code false}. Every public entry point checks
 * the single {@code volatile boolean enabled} flag first and returns immediately — a no-op
 * {@link Scope} for {@link #scope(String)}, a no-op for everything else — when disabled. This
 * makes it safe to leave instrumentation calls in permanently, not just during a profiling
 * session; re-enabling later needs no code changes.</p>
 *
 * <h3>Per-thread, not global</h3>
 * <p>Each thread accumulates its own independent scope stack/counters/samples — correct by
 * construction for profiling work that spans, say, a render thread and a background
 * thread-pool's workers at the same time without cross-thread interference on the hot path.
 * {@link #reportAllThreads()} aggregates every thread that has ever recorded anything, for the
 * cases where you do want the cross-thread view (e.g. comparing render-thread wait time against
 * background-worker generation time). Cross-thread reads are eventually-consistent, best-effort
 * — acceptable staleness for a diagnostic tool; see that method's javadoc.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * CgProfiler.setEnabled(true);
 * ...
 * try (CgProfiler.Scope ignored = CgProfiler.scope("resolvePlacements")) {
 *     CgProfiler.count("glyph.atlasHit");
 *     CgProfiler.sample("async.pendingGlyphs", registry.getPendingAsyncGlyphCount());
 *     ...
 * }
 * ...
 * CgProfilerReport report = CgProfiler.endFrame(); // snapshots + resets this thread's state
 * System.out.println(report.format());
 * </pre>
 *
 * <h3>Scopes are paths, not stand-alone timers</h3>
 * <p>{@link #push(String)}/{@link #scope(String)} build a {@code "/"}-joined path from
 * whatever's currently open on this thread's stack — pushing {@code "flatten"} while
 * {@code "resolve"} is open records time under {@code "resolve/flatten"}, not a bare
 * {@code "flatten"} shared across every caller. {@link CgProfilerReport} reconstructs the tree
 * from these paths to report both inclusive ({@code total}) and exclusive ({@code self}) time
 * per node.</p>
 *
 * @see CgProfilerReport
 */
public final class CgProfiler {

    private CgProfiler() {}

    /**
     * -- SETTER --
     * Enables/disables every {@code CgProfiler} entry point process-wide. Defaults to {@code false}.
     */
    @Getter
    @Setter
    private static volatile boolean enabled = false;

    private static final Map<Thread, ThreadProfiler> REGISTRY = new ConcurrentHashMap<>();

    private static final ThreadLocal<ThreadProfiler> STATE = ThreadLocal.withInitial(() -> {
        ThreadProfiler profiler = new ThreadProfiler(Thread.currentThread().getName());
        REGISTRY.put(Thread.currentThread(), profiler);
        return profiler;
    });

    // ────────────────────────────────────────────────────────────────
    //  Scoped hierarchical timing
    // ────────────────────────────────────────────────────────────────

    /**
     * Opens a scope named {@code name}, nested under whatever scope (if any) is currently open
     * on this thread. Returns a {@link Scope} handle whose {@link Scope#close()} pops it —
     * intended for try-with-resources so a thrown exception inside the block still pops
     * correctly. Returns a shared no-op instance when disabled.
     */
    public static Scope scope(String name) {
        if (!enabled) return Scope.NOOP;
        ThreadProfiler profiler = STATE.get();
        profiler.push(name);
        return new Scope(profiler);
    }

    /** Raw push — prefer {@link #scope(String)} unless the region genuinely can't be expressed
     * as a single try-with-resources block. Every {@code push} must be matched by exactly one
     * {@link #pop()} on the same thread, in LIFO order. */
    public static void push(String name) {
        if (enabled) STATE.get().push(name);
    }

    /** Raw pop — see {@link #push(String)}. */
    public static void pop() {
        if (enabled) STATE.get().pop();
    }

    /** try-with-resources handle returned by {@link #scope(String)}. {@link #close()} pops the
     * scope it was opened for — exception-safe by construction, since a thrown block body still
     * runs {@code close()}. */
    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null);

        private final ThreadProfiler owner;

        private Scope(ThreadProfiler owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner != null) owner.pop();
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Counters & samples
    // ────────────────────────────────────────────────────────────────

    /** Increments named counter {@code name} by 1. */
    public static void count(String name) {
        count(name, 1L);
    }

    /** Adds {@code delta} to named counter {@code name} (starting from 0). */
    public static void count(String name, long delta) {
        if (enabled) STATE.get().count(name, delta);
    }

    /** Records one observation of {@code value} under named sample {@code name} — tracks
     * count/sum/min/max/last; see {@link CgProfilerReport.SampleSummary}. */
    public static void sample(String name, double value) {
        if (enabled) STATE.get().sample(name, value);
    }

    // ────────────────────────────────────────────────────────────────
    //  Frame lifecycle & reporting
    // ────────────────────────────────────────────────────────────────

    /**
     * Snapshots this thread's accumulated scopes/counters/samples into an immutable
     * {@link CgProfilerReport}, then resets all three for the next frame — mirroring the
     * per-frame reset pattern {@code CgFontRegistry.tickFrame()}/{@code CgMsdfGenerator.tickFrame()}
     * already use elsewhere in this codebase.
     *
     * @return the report, or {@code null} when disabled
     * @throws IllegalStateException if this thread has one or more scopes still open (a
     *                                {@link #push(String)}/{@link #scope(String)} without a
     *                                matching {@link #pop()} — almost always a bug, so this is
     *                                surfaced loudly rather than silently corrupting every
     *                                subsequent frame's totals)
     */
    public static CgProfilerReport endFrame() {
        return enabled ? STATE.get().snapshotAndReset() : null;
    }

    /**
     * Snapshots this thread's current state without resetting it — for ad-hoc inspection at any
     * point, including mid-frame while scopes are still open (any such open scopes simply don't
     * contribute to this snapshot's totals yet; they will once popped).
     *
     * @return the report, or {@code null} when disabled
     */
    public static CgProfilerReport report() {
        return enabled ? STATE.get().snapshot() : null;
    }

    /** Clears this thread's accumulated scopes/counters/samples without producing a report.
     * Does not touch the open-scope stack (an in-flight {@link #scope(String)} keeps working). */
    public static void reset() {
        if (enabled) STATE.get().reset();
    }

    /**
     * Snapshots every thread that has ever recorded {@code CgProfiler} data, without resetting
     * any of them — the cross-thread view needed to see e.g. a background worker pool's
     * generation time alongside the render thread's in one report.
     *
     * <p>Best-effort: a snapshot of a thread other than the caller reads that thread's
     * in-progress counters without synchronization, so it may observe a slightly stale or
     * torn-in-time view if that thread is actively recording concurrently. Acceptable for a
     * diagnostic tool; do not use this for anything correctness-sensitive.</p>
     */
    public static Map<String, CgProfilerReport> reportAllThreads() {
        Map<String, CgProfilerReport> result = new LinkedHashMap<>();
        for (ThreadProfiler profiler : REGISTRY.values()) {
            result.put(profiler.threadName, profiler.snapshot());
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────
    //  Per-thread mutable state
    // ────────────────────────────────────────────────────────────────

    private static final class ThreadProfiler {
        final String threadName;

        // Only ever touched by this profiler's own thread (via the ThreadLocal) -- never read
        // cross-thread, so a plain ArrayDeque (not thread-safe) is fine here.
        private final Deque<OpenScope> stack = new ArrayDeque<>();

        // ConcurrentHashMap, not LinkedHashMap: reportAllThreads() iterates another thread's
        // maps without synchronization (see that method's javadoc) -- a plain HashMap/
        // LinkedHashMap would risk ConcurrentModificationException or corrupted internal
        // structure under a concurrent put() from the owning thread. ConcurrentHashMap's
        // weakly-consistent iterators make that safe; CgProfilerReport.build sorts keys itself
        // for deterministic output, so losing insertion order here costs nothing.
        final Map<String, ScopeAccum> scopeStats = new ConcurrentHashMap<>();
        final Map<String, Long> counters = new ConcurrentHashMap<>();
        final Map<String, SampleAccum> samples = new ConcurrentHashMap<>();

        ThreadProfiler(String threadName) {
            this.threadName = threadName;
        }

        void push(String name) {
            String parentPath = stack.isEmpty() ? "" : stack.peek().path;
            String path = parentPath.isEmpty() ? name : parentPath + "/" + name;
            stack.push(new OpenScope(path, System.nanoTime()));
        }

        void pop() {
            if (stack.isEmpty()) {
                throw new IllegalStateException(
                        "CgProfiler.pop() called with no matching push() on thread " + threadName);
            }
            OpenScope open = stack.pop();
            long elapsed = System.nanoTime() - open.startNanos;
            ScopeAccum accum = scopeStats.computeIfAbsent(open.path, k -> new ScopeAccum());
            accum.record(elapsed);
        }

        void count(String name, long delta) {
            counters.merge(name, delta, Long::sum);
        }

        void sample(String name, double value) {
            samples.computeIfAbsent(name, k -> new SampleAccum()).record(value);
        }

        CgProfilerReport snapshot() {
            return CgProfilerReport.build(threadName, scopeStats, counters, samples);
        }

        CgProfilerReport snapshotAndReset() {
            if (!stack.isEmpty()) {
                throw new IllegalStateException("CgProfiler.endFrame(): " + stack.size()
                        + " scope(s) still open on thread " + threadName + " (missing pop()?): " + stack);
            }
            CgProfilerReport report = snapshot();
            reset();
            return report;
        }

        void reset() {
            scopeStats.clear();
            counters.clear();
            samples.clear();
        }
    }

    private record OpenScope(String path, long startNanos) {

        @Override
        public String toString() {
            return path;
        }
    }

    /** Mutable per-path timing accumulator. Package-private (not {@code private}) so
     * {@link CgProfilerReport#build} can read it directly without a getter per field. */
    static final class ScopeAccum {
        long totalNanos;
        long callCount;
        long maxNanos;

        void record(long elapsedNanos) {
            totalNanos += elapsedNanos;
            callCount++;
            if (elapsedNanos > maxNanos) maxNanos = elapsedNanos;
        }
    }

    /** Mutable per-name sample accumulator. Package-private for the same reason as {@link ScopeAccum}. */
    static final class SampleAccum {
        long count;
        double sum;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double last;

        void record(double value) {
            count++;
            sum += value;
            last = value;
            if (value < min) min = value;
            if (value > max) max = value;
        }
    }
}
