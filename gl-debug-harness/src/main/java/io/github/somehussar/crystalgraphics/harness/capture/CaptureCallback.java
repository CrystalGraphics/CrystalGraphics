package io.github.somehussar.crystalgraphics.harness.capture;

/**
 * Callback interface for scheduling post-render capture operations.
 *
 * <p>Implemented by the {@link io.github.somehussar.crystalgraphics.harness.InteractiveSceneRunner}
 * to allow the {@link ArtifactService} to schedule screenshot captures at the
 * correct point in the frame pipeline — after all rendering (scene + floor +
 * HUD + pause overlay) but before the buffer swap.</p>
 *
 * <p>This abstraction decouples the artifact service from the runner
 * implementation, allowing scenes to request captures without knowing
 * about runner internals.</p>
 */
public interface CaptureCallback {

    /**
     * Schedules a one-shot callback to execute after the current frame
     * is fully rendered but before the buffer swap.
     *
     * <p>The callback will be consumed after firing (one-shot semantics).
     * If multiple captures are requested in the same frame, only the last
     * one is honored (consistent with the runner's single-callback design).</p>
     *
     * @param capture the capture operation to execute post-render
     */
    void schedulePostRenderCapture(Runnable capture);
}
