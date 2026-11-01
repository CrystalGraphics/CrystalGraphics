package com.crystalgraphics.render;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

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
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgWarmBudget {

    private static final CgVar.Num MILLIS = CgVar.number("crystalgraphics.warmBudget", 4f, 0f, 1000f, CgVar.DEV,
            "Milliseconds a frame may spend starting programs, kernels and meshes ahead of their first use; 0 for no limit.");

    private static final int SPENT = CgTrace.name("warm.spent-us"), REFUSED = CgTrace.name("warm.refused");

    private static boolean started;
    private static long spent;
    private static int refused;
    /** What a start costs: the largest lately, decaying by an eighth a frame, so many small starts never hide a large one. */
    private static long estimate;

    private CgWarmBudget() {}

    /** Whether a warm-up start may run this frame. */
    public static boolean allows() {
        if (!started || spent == 0) return true;
        float millis = MILLIS.get();
        if (millis <= 0f || spent + estimate <= (long) (millis * 1_000_000L)) return true;
        refused++;
        return false;
    }

    /** What is left of this frame's budget, in nanoseconds: 0 once it is spent, unlimited before a context starts it. */
    public static long remaining() {
        float millis = MILLIS.get();
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

    /** A context started: warming is budgeted from now on. The lifecycle's. */
    public static void start() {
        started = true;
        spent = 0;
    }

    /** A frame's start, before anything warms in it: its whole budget. The lifecycle's. */
    public static void nextFrame() {
        CgTrace.counter(CgChannels.MISC, SPENT, spent / 1000);
        CgTrace.counter(CgChannels.MISC, REFUSED, refused);
        spent = 0;
        refused = 0;
        estimate -= estimate >> 3;
    }
}
