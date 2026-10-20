package com.crystalgraphics.render;

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
 * kernel.prepare();
 * CgWarmBudget.spend(System.nanoTime() - start);
 * }</pre>
 *
 * <ul>
 *   <li>{@code CgPipeline.prepare} and {@code CgVfxSystem}'s warming ask it, so {@code CgWorldRenderer.prepare} and
 *       {@code CgVfxOwner.prepare} spread by themselves.</li>
 *   <li>Unlimited until {@code CgGraphicsLifecycle.initContext} starts it: a process with no context (a test) warms at
 *       once.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgWarmBudget {

    private static final CgVar.Num MILLIS = CgVar.number("crystalgraphics.warmBudget", 4f, 0f, 1000f, CgVar.DEV,
            "Milliseconds a frame may spend starting programs, kernels and meshes ahead of their first use; 0 for no limit.");

    private static boolean started;
    private static long spent;
    /** What a start costs: the largest lately, decaying by an eighth a charge. */
    private static long estimate;

    private CgWarmBudget() {}

    /** Whether a warm-up start may run this frame. */
    public static boolean allows() {
        if (!started || spent == 0) return true;
        float millis = MILLIS.get();
        return millis <= 0f || spent + estimate <= (long) (millis * 1_000_000L);
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
        estimate = Math.max(n, estimate - (estimate >> 3));
    }

    /** A context started: warming is budgeted from now on. The lifecycle's. */
    public static void start() {
        started = true;
        spent = 0;
    }

    /** The frame's end: the next frame starts with its whole budget. The lifecycle's. */
    public static void nextFrame() {
        spent = 0;
    }
}
