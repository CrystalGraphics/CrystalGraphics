package io.github.somehussar.crystalgraphics.harness;

/**
 * Immutable snapshot of per-frame timing and numbering information.
 *
 * <p>Passed to {@link HarnessSceneLifecycle#render(io.github.somehussar.crystalgraphics.harness.config.HarnessContext, FrameInfo)}
 * each frame. For managed single-shot scenes, a single FrameInfo is created
 * with frame number 1 and zero timing.</p>
 *
 * <p>This is a simple value object — no GL calls, no side effects.</p>
 */
public final class FrameInfo {

    /** Singleton for managed single-shot scenes that render exactly one frame. */
    public static final FrameInfo SINGLE_FRAME = new FrameInfo(0.0f, 0.0, 1);

    private final float deltaTime;
    private final double elapsedTime;
    private final long frameNumber;

    /**
     * Creates a new frame info snapshot.
     *
     * @param deltaTime   time since last frame in seconds (0 for single-shot)
     * @param elapsedTime total time since scene start in seconds
     * @param frameNumber the current frame number (1-based)
     */
    public FrameInfo(float deltaTime, double elapsedTime, long frameNumber) {
        this.deltaTime = deltaTime;
        this.elapsedTime = elapsedTime;
        this.frameNumber = frameNumber;
    }

    /**
     * Returns the time since the last frame in seconds.
     *
     * <p>For managed single-shot scenes, this is always 0.</p>
     *
     * @return delta time in seconds
     */
    public float getDeltaTime() {
        return deltaTime;
    }

    /**
     * Returns the total elapsed time since the scene started in seconds.
     *
     * @return elapsed time in seconds
     */
    public double getElapsedTime() {
        return elapsedTime;
    }

    /**
     * Returns the current frame number (1-based).
     *
     * <p>For managed single-shot scenes, this is always 1.</p>
     *
     * @return frame number, always >= 1
     */
    public long getFrameNumber() {
        return frameNumber;
    }

    @Override
    public String toString() {
        return "FrameInfo[frame=" + frameNumber
                + ", dt=" + String.format("%.4f", deltaTime)
                + ", elapsed=" + String.format("%.3f", elapsedTime) + "]";
    }
}
