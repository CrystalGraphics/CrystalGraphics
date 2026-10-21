package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.trace.CgGpuTrace;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Warms the JIT on a move's frame-thread paths before its first cast, as PSO precaching warms pipelines: each pass
 * its {@link Act} records into a recording of its own, which is built and executed, so the cast runs no code a pass
 * did not. Run on the render thread once its bundle is ready, a few passes a frame under {@link CgWarmBudget}, until the
 * JIT has settled. Each pass is a host section of its own, timed on the GPU as {@code rehearse}.
 *
 * <pre>{@code
 * CgRehearsal rehearsal = CgRehearsal.of(pass -> {          // the real definitions, in a hidden world of their own
 *     hidden.update(clock += 1.0 / 60.0);
 *     hidden.record(pass.recording());
 * });
 * // each frame, on the render thread, until it answers true:
 * if (rehearsal.step()) play(move);
 *
 * CgRehearsal.of(pass -> {
 *     simulation.record(pass.recording());
 *     pass.execute();                                       // what draws next reads what this wrote
 *     CgRenderStage.WORLD_OPAQUE.rehearse();
 * });
 * }</pre>
 *
 * <p>Its length is measured, not set: it stops once a {@link #window} of passes in a row has loaded no class and
 * compiled nothing, after {@link #minPasses} and before {@link #maxPasses}. A JIT busy with other threads' work can
 * hold it to {@code maxPasses}.</p>
 *
 * <ul>
 *   <li>The act must record exactly what the cast will, with the definitions it will use: synthetic ones shape the
 *       JIT's inlining for the wrong types, and the first real cast deoptimises.</li>
 *   <li>What it draws reaches the GPU: draw into targets of the rehearsal's own, never the host's
 *       ({@code CgRenderStage.rehearse} does).</li>
 *   <li>What a pass resizes or releases ({@link CgRecording#resize}, {@link CgRecording#release}) is listed under
 *       {@code -Dcrystalgraphics.rehearsal.listGrowth=true} ({@link #grown()}): what to size ahead.</li>
 *   <li>Render thread; one rehearsal's recording at a time.</li>
 * </ul>
 */
public final class CgRehearsal {

    /** One pass of what is rehearsed: records as the cast's frame would; what is left unexecuted runs after. */
    @FunctionalInterface
    public interface Act {
        void play(Pass pass);
    }

    /** A pass's recording, and its execution so far. */
    public interface Pass {
        CgRecording recording();

        /** Builds and executes what was recorded since the last, as the frame would at that point. */
        void execute();
    }

    private static final boolean LIST_GROWTH = Boolean.getBoolean("crystalgraphics.rehearsal.listGrowth");
    /** What passes resized or released, under {@link #LIST_GROWTH}: what to size ahead. */
    private static final Set<String> GROWN = new LinkedHashSet<>();
    /** A pass is running. */
    private static boolean active;
    private static final int GPU_NAME = CgGpuTrace.name("rehearse");

    private static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();
    private static final ClassLoadingMXBean CLASSES = ManagementFactory.getClassLoadingMXBean();

    private final Act act;
    private final CgRecording recording = new CgRecording();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final Pass pass = new Pass() {
        @Override
        public CgRecording recording() {
            return recording;
        }

        @Override
        public void execute() {
            executeRecorded();
        }
    };
    private int minPasses = 3_000, maxPasses = 20_000, window = 32;
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

    /**
     * Passes it always runs, however quiet the JIT: 3,000 unless given, where a move's first cast matched its tenth
     * ({@code vfx-prepare}): a JIT compiling in the background can look quiet for a window well before C2 has caught up.
     */
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
        // A host section of its own, as a stage's firing is: it runs between the host's frames.
        if (!CgGL.inHostSection()) CgGlState.invalidateAllIfPresent();
        CgGL.fromHost();
        active = true;
        try {
            CgGpuTrace.begin(GPU_NAME);
            try {
                act.play(pass);
                executeRecorded();
            } finally {
                CgGpuTrace.end();
            }
        } finally {
            active = false;
            graph.clear();
            recording.reset();
            CgGL.toHost();
        }
        passes++;
    }

    private void executeRecorded() {
        graph.add(recording.seal());
        CgFrame frame = builder.build(graph);
        try {
            CgExecutor.execute(frame);
        } finally {
            builder.recycle(frame);
            graph.clear();
            recording.reset();
        }
    }

    /** Whether a rehearsal's pass is running: what records now draws into the rehearsal's targets. Render thread. */
    public static boolean active() {
        return active;
    }

    /** What passes resized or released under {@code crystalgraphics.rehearsal.listGrowth}, in the order first seen. */
    public static List<String> grown() {
        return new ArrayList<>(GROWN);
    }

    /** A pass's resize or release of {@code buffer}, listed under {@link #LIST_GROWTH}. */
    static void grew(String what, CgGraphBuffer buffer) {
        if (active && LIST_GROWTH) GROWN.add(what + " " + buffer.name());
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
