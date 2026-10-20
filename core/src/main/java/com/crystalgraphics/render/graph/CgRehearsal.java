package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.CgWarmBudget;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Warms the JIT on a move's frame-thread paths before its first cast, as PSO precaching warms pipelines: its
 * {@link Act} records into a recording of its own, which is sealed and built and recycled, and never executed. Run on
 * the render thread once its bundle is ready, a few passes a frame under {@link CgWarmBudget}, until the JIT has settled.
 *
 * <pre>{@code
 * CgRehearsal rehearsal = CgRehearsal.of(rec -> {          // the real definitions, in a hidden world of their own
 *     hidden.update(clock += 1.0 / 60.0);
 *     hidden.record(rec);
 * });
 * // each frame, on the render thread, until it answers true:
 * if (rehearsal.step()) play(move);
 * }</pre>
 *
 * <p>Its length is measured, not set: it stops once a {@link #window} of passes in a row has loaded no class and
 * compiled nothing, after {@link #minPasses} and before {@link #maxPasses}. A JIT busy with other threads' work can
 * hold it to {@code maxPasses}.</p>
 *
 * <ul>
 *   <li>The act must record exactly what the cast will, with the definitions it will use: synthetic ones shape the
 *       JIT's inlining for the wrong types, and the first real cast deoptimises.</li>
 *   <li>A pass may not resize or release a buffer ({@link CgRecording#resize}, {@link CgRecording#release}): its
 *       recording never executes, so the copy and the release would never run and the handle would lose its bytes.
 *       It throws, naming the buffer; size it before the move rehearses.
 *       {@code -Dcrystalgraphics.rehearsal.listGrowth=true} lists each instead of throwing ({@link #grown()}), which
 *       corrupts what it lists: a diagnostic run only.</li>
 *   <li>Render thread; one rehearsal's recording at a time.</li>
 * </ul>
 */
public final class CgRehearsal {

    /** One pass of what is rehearsed: records into {@code recording} as the cast's frame would. */
    @FunctionalInterface
    public interface Act {
        void record(CgRecording recording);
    }

    private static final boolean LIST_GROWTH = Boolean.getBoolean("crystalgraphics.rehearsal.listGrowth");
    /** What passes resized or released, under {@link #LIST_GROWTH}: what to size ahead. */
    private static final Set<String> GROWN = new LinkedHashSet<>();
    /** A pass is running: its recording never executes. */
    private static boolean active;

    private static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();

    private final Act act;
    private final CgRecording recording = new CgRecording();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private int minPasses = 64, maxPasses = 20_000, window = 32;
    private int passes, quiet;
    private long compiledMillis = -1, loadedClasses = -1, lastPassNanos;
    private boolean done;

    private CgRehearsal(Act act) {
        this.act = act;
    }

    /** A rehearsal of {@code act}. */
    public static CgRehearsal of(Act act) {
        return new CgRehearsal(act);
    }

    /** Passes it always runs, however quiet the JIT: 64 unless given. */
    public CgRehearsal minPasses(int passes) {
        this.minPasses = passes;
        return this;
    }

    /** Passes it stops at whatever the JIT does: 20,000 unless given. */
    public CgRehearsal maxPasses(int passes) {
        this.maxPasses = passes;
        return this;
    }

    /** Passes in a row with no class loaded and nothing compiled that end it: 32 unless given. */
    public CgRehearsal window(int passes) {
        this.window = passes;
        return this;
    }

    /** Runs passes while this frame's warm budget allows, at least one; true once it is done. Render thread. */
    public boolean step() {
        if (done) return true;
        do {
            long start = System.nanoTime();
            pass();
            lastPassNanos = System.nanoTime() - start;
            CgWarmBudget.spend(lastPassNanos);
            if (settled()) done = true;
        } while (!done && CgWarmBudget.allows());
        return done;
    }

    /** Runs it to the end at once: for a test, or a loading screen with no frame to keep. */
    public CgRehearsal runAll() {
        while (!done) {
            pass();
            if (settled()) done = true;
        }
        return this;
    }

    private void pass() {
        active = true;
        try {
            act.record(recording);
            graph.add(recording.seal());
            CgFrame frame = builder.build(graph);
            builder.recycle(frame);
        } finally {
            active = false;
            graph.clear();
            recording.reset();
        }
        passes++;
    }

    /** Whether a rehearsal's pass is running: what records now is built and never executed. Render thread. */
    public static boolean active() {
        return active;
    }

    /** What passes resized or released under {@code crystalgraphics.rehearsal.listGrowth}, in the order first seen. */
    public static List<String> grown() {
        return new ArrayList<>(GROWN);
    }

    /** A pass's resize or release of {@code buffer}: refused, or listed under {@link #LIST_GROWTH}. */
    static void refuse(String what, CgGraphBuffer buffer) {
        if (!active) return;
        if (LIST_GROWTH) {
            GROWN.add(what + " " + buffer.name());
            return;
        }
        throw new IllegalStateException("a rehearsal " + what + " " + buffer.name()
                + ": its recording never executes, so size it before the move rehearses");
    }

    private boolean settled() {
        long compiled = JIT != null && JIT.isCompilationTimeMonitoringSupported() ? JIT.getTotalCompilationTime() : 0L;
        long loaded = CLASSES.getTotalLoadedClassCount();
        quiet = compiled == compiledMillis && loaded == loadedClasses ? quiet + 1 : 0;
        compiledMillis = compiled;
        loadedClasses = loaded;
        return passes >= maxPasses || passes >= minPasses && quiet >= window;
    }

    /** Whether it has finished. */
    public boolean done() {
        return done;
    }

    /** Passes run so far. */
    public int passes() {
        return passes;
    }

    /** Whether it ended on a quiet JIT rather than at {@link #maxPasses}. */
    public boolean settledByMeasurement() {
        return done && quiet >= window;
    }

    /** Nanoseconds the last pass took. */
    public long lastPassNanos() {
        return lastPassNanos;
    }
}
