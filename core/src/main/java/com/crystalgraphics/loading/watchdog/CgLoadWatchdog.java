package com.crystalgraphics.loading.watchdog;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.variable.CgVar;

/**
 * Names whoever holds the render thread while the loading screen shows, Unreal's game-thread hitch heartbeat
 * ({@code FGameThreadHitchHeartBeatThreaded}): the render thread beats once a frame, a thread checks every 8 ms, and a
 * frame held past {@code crystalgraphics.loading.stallMs} is sampled until it ends, then reported once: how long, how
 * much of it the render thread ran, and where its samples landed.
 *
 * <pre>{@code
 * // what a report reads like
 * [loading] the render thread was held 840 ms (cpu 790 ms) by mymod.Foo.onContextInit: its work belongs in a load task
 *     (CgLoadPlan), off the render thread or in render slices
 *     96% mymod.Foo.bakeAtlas(Foo.java:88)
 *     at mymod.Foo.bakeAtlas(Foo.java:88) ...
 * [loading] the render thread was held 150 ms (cpu 4 ms), waiting: the GPU or driver is behind on work handed it
 *     earlier (the render thread barely ran: a busy machine adds to it)
 *     90% the driver, in nglTexSubImage2D
 * }</pre>
 *
 * <ul>
 *   <li>Armed only while the screen shows: in play a slow frame is the profiler's business.</li>
 *   <li>A hold is a <b>wait</b> when most of its samples sit in a GL call, a swap, a fence or a sleep: the driver or
 *       GPU catching up, or when the render thread barely ran and was in no read: the machine had it descheduled.
 *       Else it is <b>work</b>, a read from disk included, and so is a pipeline built or linked at its draw (a
 *       driver call or a wait on a worker's build), reported as one to precache. {@link #stalls()} counts the work;
 *       {@link #waits()} the rest.</li>
 *   <li>{@code crystalgraphics.loading.strict} fails the run at the hide when work stalled: for gates and dev runs
 *       that must not regress.</li>
 *   <li>The lifecycle marks its callbacks with {@link #enter} / {@link #exit}, allocating nothing.</li>
 * </ul>
 */
public final class CgLoadWatchdog {

    private static final CgVar.Int STALL_MS = CgVar.integer("crystalgraphics.loading.stallMs", 100, 0, 600_000,
            "Milliseconds the render thread may go without a frame while the loading screen shows before the watchdog names who held it; 0 turns it off.");
    private static final CgVar.Bool STRICT = CgVar.bool("crystalgraphics.loading.strict", false, CgVar.DEV,
            "Fail the run at the loading screen's hide when the watchdog saw the render thread held by work.");
    private static final Logger LOGGER = LogManager.getLogger(CgLoadWatchdog.class);
    private static final int STACK_LINES = 24, PLACES = 3;
    /** Below this share of a hold on the CPU, the render thread was not running: a wait. */
    private static final double RAN = 0.5;

    @Nullable private static volatile Thread render;
    private static volatile boolean armed;
    private static volatile long beatAt;
    @Nullable private static volatile Object inside;
    @Nullable private static volatile String insideWhat;
    private static final AtomicInteger STALLS = new AtomicInteger(), WAITS = new AtomicInteger();
    @Nullable private static Thread watcher;

    private CgLoadWatchdog() {
    }

    /** Holds reported as work since launch. */
    public static int stalls() {
        return STALLS.get();
    }

    /** Holds reported as waits since launch: the render thread not running, or in a swap, fence or sleep. */
    public static int waits() {
        return WAITS.get();
    }

    /** A frame began on the render thread. Starts the watcher at its first call. */
    public static void beat() {
        beatAt = System.nanoTime();
        if (render == null) start();
    }

    /** Whether a stall fails the run ({@code crystalgraphics.loading.strict}). */
    public static boolean strict() {
        return STRICT.get();
    }

    /** Whether it watches: while the screen shows. The loading screen's. */
    public static void arm(boolean on) {
        if (on && !armed) beatAt = System.nanoTime();
        armed = on;
    }

    /** The render thread is inside {@code who}'s {@code what} (a listener's {@code onContextInit}); returns what it was in. */
    @Nullable
    public static Object enter(Object who, String what) {
        Object was = inside;
        inside = who;
        insideWhat = what;
        return was;
    }

    /** Back out of a callback {@link #enter}ed: {@code was} is what it returned. */
    public static void exit(@Nullable Object was) {
        inside = was;
        if (was == null) insideWhat = null;
    }

    private static synchronized void start() {
        if (render != null) return;
        render = Thread.currentThread();
        if (STALL_MS.get() <= 0) return;
        Thread t = new Thread(CgLoadWatchdog::watch, "crystalgraphics-load-watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 1);
        t.start();
        watcher = t;
    }

    /** The watcher: samples a frame held past the limit until it ends, then reports it. */
    @SuppressWarnings("deprecation")   // Thread.getId: threadId() is Java 19's, and this runs on 8
    private static void watch() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean timed = threads.isThreadCpuTimeSupported();
        long seen = 0L, cpuAtBeat = 0L;
        Hold hold = null;
        while (true) {
            try {
                Thread.sleep(8);
            } catch (InterruptedException stop) {
                return;
            }
            Thread t = render;
            if (t == null) continue;
            long beat = beatAt, now = System.nanoTime(), cpu = timed ? threads.getThreadCpuTime(t.getId()) : -1L;
            if (beat != seen) {
                if (hold != null) hold.report((beat - hold.beat) / 1_000_000L, cpu < 0 ? -1L : (cpu - hold.cpuAtBeat) / 1_000_000L);
                hold = null;
                seen = beat;
                cpuAtBeat = cpu;
            }
            long limit = STALL_MS.get() * 1_000_000L;
            if (!armed || limit <= 0 || now - beat < limit) continue;
            if (hold == null) hold = new Hold(beat, cpuAtBeat);
            hold.sample(t.getStackTrace(), inside, insideWhat);
        }
    }

    /** One frame held past the limit: where its samples landed. Watcher thread only. */
    private static final class Hold {
        final long beat, cpuAtBeat;
        final Map<String, Integer> places = new HashMap<>();
        final Map<String, StackTraceElement[]> stacks = new HashMap<>();
        /** The places that are waits, never named as the work. */
        final Set<String> waits = new HashSet<>();
        int samples, waiting, blocked;

        Hold(long beat, long cpuAtBeat) {
            this.beat = beat;
            this.cpuAtBeat = cpuAtBeat;
        }

        void sample(StackTraceElement[] stack, @Nullable Object who, @Nullable String what) {
            samples++;
            String compile = who == null ? compileIn(stack) : null;
            String wait = who == null && compile == null ? waitIn(stack) : null;
            if (wait != null) waiting++;
            // A compile is the render thread's own work however little of it ran on its CPU
            if (wait == null && (compile != null || readsIn(stack))) blocked++;
            String place = who != null ? who.getClass().getName() + "." + what : compile != null ? compile
                    : wait != null ? wait : culprit(stack);
            if (wait != null) waits.add(place);
            places.merge(place, 1, Integer::sum);
            stacks.putIfAbsent(place, stack);
        }

        void report(long heldMs, long cpuMs) {
            // By where its samples sit, never its CPU time alone: a read from disk or a compile is ours though it
            // waits. A thread that barely ran with neither in hand was descheduled, wherever its samples stopped.
            boolean ran = cpuMs < 0 || cpuMs >= heldMs * RAN;
            boolean work = waiting * 2 < samples && (ran || blocked > 0);
            (work ? STALLS : WAITS).incrementAndGet();
            List<Map.Entry<String, Integer>> ranked = new ArrayList<>(places.entrySet());
            ranked.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder s = new StringBuilder("[loading] the render thread was held ").append(heldMs).append(" ms");
            if (cpuMs >= 0) s.append(" (cpu ").append(cpuMs).append(" ms)");
            String named = ranked.get(0).getKey();
            for (Map.Entry<String, Integer> e : ranked) {
                if (!work || !waits.contains(e.getKey())) {
                    named = e.getKey();
                    break;
                }
            }
            if (work && named.startsWith(COMPILE)) {
                s.append(" by ").append(named)
                        .append(": precache it (CgPipelinePrecache), or hold the draw until it is built");
            } else if (work) {
                s.append(" by ").append(named)
                        .append(": its work belongs in a load task (CgLoadPlan), off the render thread or in render slices");
            } else if (waiting * 2 < samples) {
                s.append(", waiting: the machine kept the render thread off the CPU, in no GL call or read");
            } else {
                s.append(", waiting: the GPU or driver is behind on work handed it earlier")
                        .append(ran ? "" : " (the render thread barely ran: a busy machine adds to it)");
            }
            for (int i = 0; i < Math.min(PLACES, ranked.size()); i++) {
                s.append("\n    ").append(100 * ranked.get(i).getValue() / samples).append("% ").append(ranked.get(i).getKey());
            }
            StackTraceElement[] stack = stacks.get(named);
            for (int i = 0; i < Math.min(STACK_LINES, stack.length); i++) s.append("\n    at ").append(stack[i]);
            LOGGER.warn(s.toString());
        }
    }

    /** How a place that is a pipeline built at its draw begins: work, but its fix is a precache, not a load task. */
    private static final String COMPILE = "a pipeline built at its draw, ";

    /**
     * The pipeline build or program link the stack's top frames sit in, or null: a driver call, or a wait on a worker's
     * build, that is still a compile the frame asked for (Unreal's PSO hitch), never the driver catching up.
     */
    @Nullable
    private static String compileIn(StackTraceElement[] stack) {
        for (int i = 0; i < Math.min(12, stack.length); i++) {
            String m = stack[i].getMethodName(), c = stack[i].getClassName();
            if (m.contains("CreateGraphicsPipelines") || m.contains("CreateComputePipelines") || m.contains("LinkProgram")
                    || m.contains("CompileShader") || m.equals("takeAhead")
                    || m.equals("await") && c.endsWith("CgLinkWorker$Job")) {
                return COMPILE + "in " + c.substring(c.lastIndexOf('.') + 1) + "." + m;
            }
        }
        return null;
    }

    /**
     * The wait the stack's top few frames sit in (a swap, a fence, a frame-rate sleep, a GL call: the driver catching
     * up with what it was handed), or null for work.
     */
    @Nullable
    private static String waitIn(StackTraceElement[] stack) {
        if (stack.length > 0) {
            String top = stack[0].getClassName();
            // LWJGL's GL entry, its JNI call, or the backend's own frame when the call is made from it directly
            if (top.startsWith("org.lwjgl.opengl.") && stack[0].isNativeMethod() || top.equals("org.lwjgl.system.JNI")
                    || top.endsWith("GLBackend")) {
                return "the driver, in " + stack[0].getMethodName();
            }
        }
        for (int i = 0; i < Math.min(6, stack.length); i++) {
            String m = stack[i].getMethodName();
            if (m.contains("SwapBuffers") || m.contains("swapBuffers") || m.contains("ClientWaitSync")
                    || m.equals("sync") || m.equals("sleep") || m.equals("waitForFence")) {
                return stack[i].getClassName() + "." + m;
            }
        }
        return null;
    }

    /** Whether the stack is in a read: a file, a jar or a resource. */
    private static boolean readsIn(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("java.io.") || c.startsWith("java.nio.") || c.startsWith("sun.nio.")
                    || c.startsWith("java.util.zip.") || c.startsWith("java.util.jar.")
                    || c.equals("com.crystalgraphics.util.io.CgIO")) return true;
        }
        return false;
    }

    /** The first frame of the stack outside the JDK and the engine's own plumbing: whose code it is in. */
    private static String culprit(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("org.lwjgl.")
                    || c.startsWith("com.crystalgraphics.platform.gl.") || c.startsWith("com.crystalgraphics.loading.")
                    || c.startsWith("com.crystalgraphics.lifecycle.")) continue;
            return e.getClassName() + "." + e.getMethodName() + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
        }
        return stack.length > 0 ? stack[0].toString() : "unknown";
    }
}
