package io.github.somehussar.crystalgraphics.harness.runtime;

/**
 * Frame-timing service for the interactive render loop.
 *
 * <p>Tracks high-resolution timing across frames using {@code System.nanoTime()}.
 * Each call to {@link #tick()} computes the delta since the last tick and
 * accumulates total elapsed time. The frame number is incremented on every tick.</p>
 *
 * <p>This service is one of the focused runtime collaborators extracted from
 * {@code InteractiveSceneRunner} to separate frame-timing concerns from
 * input handling, resize propagation, and overlay orchestration.</p>
 *
 * <p><b>Thread safety</b>: FrameClock is only used on the LWJGL render thread.
 * No synchronization is needed.</p>
 *
 * <p><b>Frame ordering contract</b>: {@code tick()} must be called exactly once
 * at the start of each frame iteration, before any other per-frame processing
 * (input, camera update, scheduler, rendering).</p>
 */
public final class FrameClock {

    private long lastTimeNanos;
    private float deltaTime;
    private double elapsedTime;
    private long frameNumber;

    /**
     * Creates a new FrameClock. The clock starts counting from the first
     * {@link #tick()} call.
     */
    public FrameClock() {
        this.lastTimeNanos = System.nanoTime();
        this.deltaTime = 0.0f;
        this.elapsedTime = 0.0;
        this.frameNumber = 0;
    }

    /**
     * Advances the clock by one frame.
     *
     * <p>Computes the time delta since the last tick (in seconds), accumulates
     * total elapsed time, and increments the frame counter. Must be called
     * exactly once per frame at the start of the frame loop iteration.</p>
     */
    public void tick() {
        long nowNanos = System.nanoTime();
        // Convert nanosecond delta to seconds as float for per-frame use
        deltaTime = (nowNanos - lastTimeNanos) / 1_000_000_000.0f;
        lastTimeNanos = nowNanos;
        elapsedTime += deltaTime;
        frameNumber++;
    }

    /**
     * Returns the time elapsed since the previous frame, in seconds.
     *
     * <p>This value is computed during {@link #tick()} and remains stable
     * for the entire frame. Useful for frame-rate-independent animation
     * and camera movement.</p>
     *
     * @return delta time in seconds (always >= 0)
     */
    public float getDeltaTime() {
        return deltaTime;
    }

    /**
     * Returns the total elapsed time since the first tick, in seconds.
     *
     * <p>Uses double precision to avoid floating-point drift over long
     * running sessions. This is the time value passed to the
     * {@code TaskScheduler} for event firing.</p>
     *
     * @return total elapsed time in seconds (always >= 0)
     */
    public double getElapsedTime() {
        return elapsedTime;
    }

    /**
     * Returns the current frame number (1-based).
     *
     * <p>Incremented on each {@link #tick()} call. Frame 1 is the first
     * rendered frame. This value is passed to scene {@code renderFrame()}
     * for frame-dependent logic like MSDF generation budgets.</p>
     *
     * @return the current frame number (>= 1 after first tick)
     */
    public long getFrameNumber() {
        return frameNumber;
    }

    @Override
    public String toString() {
        return "FrameClock[frame=" + frameNumber
                + ", dt=" + String.format("%.4f", deltaTime)
                + "s, elapsed=" + String.format("%.2f", elapsedTime) + "s]";
    }
}
