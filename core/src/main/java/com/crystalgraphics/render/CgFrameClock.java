package com.crystalgraphics.render;

/**
 * The frame's time: one clock, read by every pass that records it — the world, the UI, a shader-graph preview — so
 * their animations agree. It holds still for the whole of a frame.
 *
 * <pre>{@code
 * constants.time(CgFrameClock.seconds());   // a pass's cg_Time
 * }</pre>
 *
 * <p>{@code CgGraphicsLifecycle.tickFrame}, a host's frame end, advances it to the time since start. A driver with a
 * clock of its own sets it before each frame instead — the harness does, from its fixed delta, so a capture is the same
 * on every run. Once set, the clock is that driver's alone and {@link #advanceToNow} does nothing.</p>
 */
public final class CgFrameClock {

    private static final long START = System.nanoTime();
    private static volatile float seconds;
    // Two writers disagree mid-frame: a document recording on its own thread reads whichever wrote last.
    private static volatile boolean driven;

    private CgFrameClock() {}

    /** The current frame's time, in seconds. */
    public static float seconds() {
        return seconds;
    }

    /** Sets the frame's time: a driver with its own clock, before the frame. From then on only this sets it. */
    public static void set(float seconds) {
        driven = true;
        CgFrameClock.seconds = seconds;
    }

    /** Advances to the time since start, for the next frame. The lifecycle's frame end; nothing once {@link #set}. */
    public static void advanceToNow() {
        if (driven) return;
        seconds = (System.nanoTime() - START) * 1e-9f;
    }
}
