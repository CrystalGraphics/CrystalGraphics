package com.crystalgraphics.util.profiling;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.trace.CgTraceNames;
import com.crystalgraphics.trace.CgTraceSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalGraphics' named scopes, counters and samples — recorded into {@link CgTrace}, so they show in
 * the Frame Profiler beside everything else a frame did, and still readable here as a per-thread
 * call tree.
 *
 * <pre>{@code
 * try (CgProfiler.Scope ignored = CgProfiler.scope("shape.run")) {
 *     CgProfiler.count("glyph.atlasHit");
 *     CgProfiler.sample("async.pendingGlyphs", registry.getPendingAsyncGlyphCount());
 * }
 * }</pre>
 *
 * <h3>Turning it on</h3>
 * <p>It records whenever a {@code crystalgraphics} channel records — from the Frame Profiler's channel
 * menu, from {@code CgTrace.enable("crystalgraphics")}, or from {@link #setEnabled(boolean)}, which is
 * that same call. With nothing recording, every entry point is one volatile read.</p>
 *
 * <h3>Which channel a name lands on</h3>
 * <p>The text before a name's first {@code '.'} — the whole name when there is none — picks the
 * channel, so one subsystem can be recorded without the rest:</p>
 * <pre>{@code
 * CgProfiler.scope("shape.run");        // crystalgraphics.text
 * CgProfiler.scope("doBind.stateSave"); // crystalgraphics.gl
 * CgProfiler.scope("worker.generate");  // crystalgraphics.async
 * CgProfiler.scope("myThing");          // crystalgraphics.misc — any prefix not in the table
 *
 * CgTrace.enable("crystalgraphics.text"); // record text alone
 * }</pre>
 *
 * <h3>Reading it back without the window</h3>
 * <pre>{@code
 * CgProfiler.setEnabled(true);
 * CgProfiler.reset();                        // this thread's report starts now
 * runTheWorkload();
 * System.out.println(CgProfiler.report().format());
 *
 * CgProfilerReport frame = CgProfiler.endFrame(); // report, then reset — once per frame
 * Map<String, CgProfilerReport> all = CgProfiler.reportAllThreads(); // workers too
 * }</pre>
 *
 * <p>A report's scopes are paths: {@code "flatten"} opened inside {@code "resolve"} is reported as
 * {@code "resolve/flatten"}, with inclusive and self time.</p>
 *
 * <h3>Easy to get wrong</h3>
 * <ul>
 *   <li>Every {@link #push(String)} needs one {@link #pop()} on the same thread; prefer
 *       {@link #scope(String)} in try-with-resources. {@link #endFrame()} with a scope open throws.</li>
 *   <li>{@link #endFrame()} ends this thread's REPORT, not the trace's frame — the host owns that.</li>
 *   <li>A report holds only zones still in the trace's ring: on a long run, older ones have been
 *       overwritten, and a report covers what is left.</li>
 *   <li>In the trace a sample is a whole number ({@code 0.4} records 0), and a counter's frame total is
 *       written when that name is next counted in a later frame. Reports keep the exact values.</li>
 * </ul>
 *
 * @see CgProfilerReport
 */
public final class CgProfiler {

    private CgProfiler() {}

    static {
        CgTraceNames.addForwarder(CgProfiler.class.getName());
    }

    private static final CgTraceChannel TEXT = CgTrace.channel("crystalgraphics.text");
    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    private static final CgTraceChannel ASYNC = CgTrace.channel("crystalgraphics.async");
    private static final CgTraceChannel MISC = CgTrace.channel("crystalgraphics.misc");

    private static final Set<String> OWN_CHANNELS =
            Set.of(TEXT.name(), GL.name(), ASYNC.name(), MISC.name());

    /** First name segment to channel. Anything absent is {@link #MISC}. */
    private static final Map<String, CgTraceChannel> PREFIXES = new HashMap<>();

    static {
        for (String prefix : new String[] {
                "text", "draw", "shape", "hb", "lineBreak", "wrap", "paragraphLayout", "layoutCache",
                "font", "freetype", "ftRaster", "msdfgen", "glyph", "atlas", "packer", "registry",
                "placementCache", "asyncCommit", "resolvePlacements", "resolveGlyphs", "resolveDecorations",
                "drainCompletedGlyphs", "atlasTick", "flatten", "quadLoop", "planShadows", "sortKeys",
                "submitSortedQuads", "materialTransition", "syncProjection"}) {
            PREFIXES.put(prefix, TEXT);
        }
        for (String prefix : new String[] {
                "batch", "doBind", "material", "texArray", "quadRenderer", "curveRenderer",
                "streamBuffer", "cull", "gpu", "glFlush"}) {
            PREFIXES.put(prefix, GL);
        }
        PREFIXES.put("worker", ASYNC);
        PREFIXES.put("async", ASYNC);
    }

    private record Resolved(int nameId, CgTraceChannel channel) {}

    private static final Map<String, Resolved> RESOLVED = new ConcurrentHashMap<>();

    private static final Map<Thread, ThreadState> REGISTRY = new ConcurrentHashMap<>();

    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    // ── Switching ───────────────────────────────────────────────────────────────────────────

    /** Whether any of this profiler's channels is recording. */
    public static boolean isEnabled() {
        return CgTrace.isRecording() && (CgTrace.isEnabled(TEXT) || CgTrace.isEnabled(GL)
                || CgTrace.isEnabled(ASYNC) || CgTrace.isEnabled(MISC));
    }

    /** {@code CgTrace.enable("crystalgraphics")} or {@code disable} — every CrystalGraphics channel. */
    public static void setEnabled(boolean enabled) {
        CgTrace.setEnabled("crystalgraphics", enabled);
    }

    // ── Scopes ──────────────────────────────────────────────────────────────────────────────

    /**
     * Opens a scope; close the handle to end it. Nests under whatever scope this thread has open.
     *
     * <p>The handle is shared per thread and closes the innermost scope, which try-with-resources
     * guarantees is this one. Disabled, it is a no-op.</p>
     */
    public static Scope scope(String name) {
        if (!isEnabled()) return Scope.NOOP;
        ThreadState state = STATE.get();
        state.push(name);
        return state.handle;
    }

    /** Opens a scope without a handle. Pair it with exactly one {@link #pop()} on this thread. */
    public static void push(String name) {
        if (isEnabled()) STATE.get().push(name);
    }

    /**
     * Closes the innermost scope {@link #push(String)} opened.
     *
     * @throws IllegalStateException when enabled and this thread has nothing open
     */
    public static void pop() {
        ThreadState state = STATE.get();
        if (state.depth == 0) {
            if (isEnabled()) {
                throw new IllegalStateException(
                        "CgProfiler.pop() called with no matching push() on thread " + state.threadName);
            }
            return;
        }
        state.pop();
    }

    /** What {@link #scope(String)} returns; {@link #close()} ends the scope. */
    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null);

        private final ThreadState owner;

        private Scope(ThreadState owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            // Unconditional: a channel switched off mid-scope must still close the zone it opened.
            if (owner != null) owner.pop();
        }
    }

    // ── Counters and samples ────────────────────────────────────────────────────────────────

    public static void count(String name) {
        count(name, 1L);
    }

    /** Adds {@code delta} to counter {@code name}. */
    public static void count(String name, long delta) {
        if (isEnabled()) STATE.get().count(name, delta);
    }

    /** Records one value of {@code name}; a report keeps count, sum, min, max and last. */
    public static void sample(String name, double value) {
        if (isEnabled()) STATE.get().sample(name, value);
    }

    // ── Reports ─────────────────────────────────────────────────────────────────────────────

    /**
     * This thread's report since its last reset, then a reset. Does not end the trace's frame.
     *
     * @return null when disabled
     * @throws IllegalStateException if this thread still has a scope open
     */
    public static CgProfilerReport endFrame() {
        if (!isEnabled()) return null;
        ThreadState state = STATE.get();
        if (state.depth > 0) {
            throw new IllegalStateException("CgProfiler.endFrame(): " + state.depth
                    + " scope(s) still open on thread " + state.threadName + " (missing pop()?): "
                    + Arrays.asList(state.names).subList(0, state.depth));
        }
        CgProfilerReport report = state.snapshot();
        state.reset();
        return report;
    }

    /**
     * This thread's report since its last reset. A scope still open is left out.
     *
     * @return null when disabled
     */
    public static CgProfilerReport report() {
        return isEnabled() ? STATE.get().snapshot() : null;
    }

    /** Starts this thread's report afresh. Open scopes keep working; they are reported if they close later
     * and began after this call. */
    public static void reset() {
        if (isEnabled()) STATE.get().reset();
    }

    /**
     * A report for every thread that has recorded anything, keyed by thread name — to see workers beside
     * the render thread. Another thread's counters are read unsynchronised, so they may be a moment stale.
     */
    public static Map<String, CgProfilerReport> reportAllThreads() {
        Map<String, CgProfilerReport> result = new LinkedHashMap<>();
        for (ThreadState state : REGISTRY.values()) {
            result.put(state.threadName, state.snapshot());
        }
        return result;
    }

    // ── Per-thread state ────────────────────────────────────────────────────────────────────

    private static Resolved resolve(String name) {
        Resolved known = RESOLVED.get(name);
        if (known != null) return known;
        int dot = name.indexOf('.');
        CgTraceChannel channel = PREFIXES.getOrDefault(dot < 0 ? name : name.substring(0, dot), MISC);
        Resolved made = new Resolved(CgTraceNames.intern(name), channel);
        Resolved raced = RESOLVED.putIfAbsent(name, made);
        return raced != null ? raced : made;
    }

    private static final class CounterStat {
        final Resolved resolved;
        /** Since the last reset, for reports. Written by the owner, read by anyone. */
        long total;
        /** The trace frame {@link #frameSum} belongs to, or -1. */
        long frame = -1L;
        long frameSum;

        CounterStat(Resolved resolved) {
            this.resolved = resolved;
        }

        void add(long delta) {
            total += delta;
            long now = CgTrace.currentFrameIndex();
            if (now != frame) {
                flush();
                frame = now;
            }
            frameSum += delta;
        }

        void flush() {
            if (frame >= 0L) CgTrace.counterAt(resolved.channel(), resolved.nameId(), frame, frameSum);
            frameSum = 0L;
        }
    }

    private static final class ThreadState {
        final Thread thread = Thread.currentThread();
        final String threadName = thread.getName();
        final Scope handle = new Scope(this);

        String[] names = new String[16];
        boolean[] opened = new boolean[16];
        int depth;

        /** Report zones start strictly after this. */
        volatile long cursor = Long.MIN_VALUE;

        final Map<String, CounterStat> counters = new ConcurrentHashMap<>();
        final Map<String, SampleAccum> samples = new ConcurrentHashMap<>();

        private boolean registered;

        private void register() {
            if (registered) return;
            registered = true;
            REGISTRY.put(thread, this);
        }

        void push(String name) {
            register();
            Resolved resolved = resolve(name);
            boolean open = CgTrace.begin(resolved.channel(), resolved.nameId()) != 0L;
            if (depth == names.length) {
                names = Arrays.copyOf(names, depth * 2);
                opened = Arrays.copyOf(opened, depth * 2);
            }
            names[depth] = name;
            opened[depth] = open;
            depth++;
        }

        void pop() {
            if (depth == 0) {
                throw new IllegalStateException(
                        "CgProfiler scope closed with none open on thread " + threadName);
            }
            depth--;
            names[depth] = null;
            // Any non-zero token closes the innermost zone.
            if (opened[depth]) CgTrace.end(1L);
        }

        void count(String name, long delta) {
            register();
            CounterStat stat = counters.get(name);
            if (stat == null) {
                stat = new CounterStat(resolve(name));
                counters.put(name, stat);
            }
            stat.add(delta);
        }

        void sample(String name, double value) {
            register();
            samples.computeIfAbsent(name, k -> new SampleAccum()).record(value);
            Resolved resolved = resolve(name);
            CgTrace.counterAt(resolved.channel(), resolved.nameId(), CgTrace.currentFrameIndex(),
                    Math.round(value));
        }

        void reset() {
            for (CounterStat stat : counters.values()) stat.flush();
            counters.clear();
            samples.clear();
            cursor = System.nanoTime();
        }

        CgProfilerReport snapshot() {
            Map<String, Long> totals = new HashMap<>();
            for (Map.Entry<String, CounterStat> e : counters.entrySet()) totals.put(e.getKey(), e.getValue().total);
            return CgProfilerReport.build(threadName, scopeStats(), totals, samples);
        }

        /** This thread's closed zones on our channels since the cursor, nested by containment into paths. */
        private Map<String, ScopeAccum> scopeStats() {
            long from = cursor + 1;
            List<CgTraceSnapshot.ZoneView> zones = new ArrayList<>();
            for (CgTraceSnapshot.ZoneView zone : CgTrace.zonesOfThread(thread, from)) {
                if (OWN_CHANNELS.contains(zone.channel())) zones.add(zone);
            }
            // Parent before child: earliest start, then the longer of two starting together.
            zones.sort((a, b) -> a.startNanos() != b.startNanos()
                    ? Long.compare(a.startNanos(), b.startNanos())
                    : Long.compare(b.endNanos(), a.endNanos()));

            Map<String, ScopeAccum> stats = new HashMap<>();
            Deque<CgTraceSnapshot.ZoneView> open = new ArrayDeque<>();
            Deque<String> paths = new ArrayDeque<>();
            for (CgTraceSnapshot.ZoneView zone : zones) {
                // Sorted so every candidate parent started first; it contains this zone unless it ends earlier.
                while (!open.isEmpty() && zone.endNanos() > open.peek().endNanos()) {
                    open.pop();
                    paths.pop();
                }
                String path = paths.isEmpty() ? zone.name() : paths.peek() + "/" + zone.name();
                stats.computeIfAbsent(path, k -> new ScopeAccum()).record(zone.endNanos() - zone.startNanos());
                open.push(zone);
                paths.push(path);
            }
            return stats;
        }
    }

    /** Per-path timing, read by {@link CgProfilerReport#build}. */
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

    /** Per-name sample summary, read by {@link CgProfilerReport#build}. */
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
