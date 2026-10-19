package com.crystalgraphics.render;

/**
 * The frame's time and index: one clock, read by every pass that records it — the world, the UI, a shader-graph
 * preview — so their animations agree. Both hold still for the whole of a frame.
 *
 * <pre>{@code
 * constants.time(CgFrameClock.seconds());                 // a pass's cg_Time
 * double age = CgFrameClock.seconds() - startedAt;        // CPU time stays a double: never keep it in a float
 * if (CgFrameClock.frameIndex() - lastUsed > 600) evict(); // frames, for what counts frames rather than seconds
 * }</pre>
 *
 * <p>{@code CgGraphicsLifecycle.tickFrame}, a host's frame end, advances it: the index by one, the seconds to the time
 * since start. A driver with a clock of its own sets the seconds before each frame instead — the harness does, from
 * its fixed delta, so a capture is the same on every run. Once set, the seconds are that driver's alone; the index
 * still advances once a host frame.</p>
 *
 * <ul>
 *   <li>Seconds are a double, as Unreal's {@code UWorld::TimeSeconds}: a float steps 8 ms after a day.</li>
 *   <li>Shader time is a float, narrowed once by {@code CgPassConstants.time}.</li>
 * </ul>
 */
public final class CgFrameClock {

    private static final long START = System.nanoTime();
    private static volatile double seconds;
    private static volatile long frameIndex;
    // Two writers disagree mid-frame: a document recording on its own thread reads whichever wrote last.
    private static volatile boolean driven;

    private CgFrameClock() {}

    /** The current frame's time, in seconds. */
    public static double seconds() {
        return seconds;
    }

    /** The current frame's index, from 0: one a host frame, never reset. */
    public static long frameIndex() {
        return frameIndex;
    }

    /** Sets the frame's time: a driver with its own clock, before the frame. From then on only this sets it. */
    public static void set(double seconds) {
        driven = true;
        CgFrameClock.seconds = seconds;
    }

    /** Advances to the next frame: its index, and its time to the time since start unless {@link #set}. */
    public static void advance() {
        frameIndex++;
        if (!driven) seconds = (System.nanoTime() - START) * 1e-9;
    }
}
