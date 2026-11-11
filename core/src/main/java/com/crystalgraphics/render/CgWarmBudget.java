package com.crystalgraphics.render;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A frame's CPU time for warming: starting programs, kernels and meshes ahead of their first use. A warm-up list
 * polled each frame asks before each start and charges what it took, so a scene or a look warmed at once spreads over
 * frames instead of holding one for seconds. The first start of a frame always runs; after it, a start is refused when
 * what one has cost lately would take the frame past its budget, so the frame ends near it rather than a start over.
 *
 * <pre>{@code
 * if (!CgWarmBudget.allows()) return false;     // this frame's time is spent: ask again next frame
 * long start = System.nanoTime();
 * kernel.precache();
 * CgWarmBudget.spend(System.nanoTime() - start);
 * }</pre>
 *
 * <ul>
 *   <li>{@code CgPipeline.precache} and {@code CgVfxSystem}'s warming ask it, so {@code CgWorldRenderer.precache} and
 *       {@code CgVfxOwner.precache} spread by themselves.</li>
 *   <li>Unlimited until {@code CgEngineLifecycle.ensureGraphicsContext} starts it: a process with no context (a test) warms at
 *       once.</li>
 *   <li>{@code -Dcrystalgraphics.warmBudget.log=true} logs each frame what it spent and refused, and each warm-up what it
 *       still waits on ({@link #logging()}): where a warm-up's frames go.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgWarmBudget {

    private static final CgVar.Num MILLIS = CgVar.number("crystalgraphics.warmBudget", 4f, 0f, 1000f, CgVar.DEV,
            "Milliseconds a frame may spend starting programs, kernels and meshes ahead of their first use; 0 for no limit.");
    // 12 holds the screen near 60 fps: at 50 it warmed only a little faster, at 20 fps
    private static final CgVar.Num LOADING_MILLIS = CgVar.number("crystalgraphics.warmBudget.loading", 12f, 0f, 1000f,
            CgVar.DEV, "The warm budget while the loading screen shows, as Unity's High background priority; 0 for no limit.");

    private static final CgVar.Bool LOG = CgVar.bool("crystalgraphics.warmBudget.log", false, CgVar.DEV,
            "Log each frame the warm budget spent and refused, and what each warm-up still waits on.");
    private static final Logger LOGGER = LogManager.getLogger("CgWarmBudget");

    private static final int SPENT = CgTrace.name("warm.spent-us"), REFUSED = CgTrace.name("warm.refused");

    private static boolean started, loading;
    private static long spent;
    private static int refused;
    /** What a start costs: the largest lately, decaying by an eighth a frame, so many small starts never hide a large one. */
    private static long estimate;

    private CgWarmBudget() {}

    /** Whether a warm-up start may run this frame. */
    public static boolean allows() {
        if (!started || spent == 0) return true;
        float millis = millis();
        if (millis <= 0f || spent + estimate <= (long) (millis * 1_000_000L)) return true;
        refused++;
        return false;
    }

    /** What is left of this frame's budget, in nanoseconds: 0 once it is spent, unlimited before a context starts it. */
    public static long remaining() {
        float millis = millis();
        if (!started || millis <= 0f) return Long.MAX_VALUE;
        return Math.max(0L, (long) (millis * 1_000_000L) - spent);
    }

    /** Charges {@code nanos} of warming to this frame. */
    public static void spend(long nanos) {
        long n = Math.max(nanos, 1L);
        spent += n;
        estimate = Math.max(n, estimate);
    }

    /**
     * Charges {@code nanos} of work that runs after the frame's warm starts (a rehearsal's passes) to this frame alone:
     * unlike {@link #spend}, it leaves the estimate of what a start costs, which a 20 ms pass would otherwise raise until
     * every start after a frame's first was refused.
     */
    public static void spendAfter(long nanos) {
        spent += Math.max(nanos, 1L);
    }

    /** Whether the loading screen shows: its frames warm at {@code crystalgraphics.warmBudget.loading}. Its own. */
    public static void loading(boolean shown) {
        loading = shown;
    }

    private static float millis() {
        return loading ? LOADING_MILLIS.get() : MILLIS.get();
    }

    /** A context started: warming is budgeted from now on. The lifecycle's. */
    public static void start() {
        started = true;
        spent = 0;
    }

    /** Whether warm-ups log each frame what they wait on: {@code crystalgraphics.warmBudget.log}. */
    public static boolean logging() {
        return LOG.get();
    }

    /** A frame's start, before anything warms in it: its whole budget. The lifecycle's. */
    public static void nextFrame() {
        if (LOG.get() && (spent > 0 || refused > 0)) {
            LOGGER.info("[warm] spent {} us of {} ms, {} starts refused", spent / 1000, millis(), refused);
        }
        CgTrace.counter(CgChannels.MISC, SPENT, spent / 1000);
        CgTrace.counter(CgChannels.MISC, REFUSED, refused);
        spent = 0;
        refused = 0;
        estimate -= estimate >> 3;
    }
}
