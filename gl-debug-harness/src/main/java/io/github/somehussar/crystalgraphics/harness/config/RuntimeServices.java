package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.harness.InteractiveSceneRunner;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;

/**
 * Typed accessor for interactive runtime services.
 *
 * <p>Replaces the unsafe {@code Object}-typed runner reference that was
 * previously stored in {@link HarnessContext}. Scenes interact with this
 * narrowed facade for pause control, viewport queries, and one-shot post-render
 * callbacks instead of depending on the full runner type.</p>
 *
 * <p>This object is only populated for INTERACTIVE lifecycle scenes.
 * For MANAGED/DIAGNOSTIC scenes, {@link HarnessContext#getRuntimeServices()}
 * returns {@code null}.</p>
 *
 * <p><b>Design rationale</b>: The runner provides post-render callback
 * scheduling, pause control, and current viewport dimensions. By exposing
 * these through a typed object, we eliminate unsafe casts and make the
 * dependency explicit at the type level.</p>
 *
 * @see HarnessContext#getRuntimeServices()
 */
public final class RuntimeServices {

    private final InteractiveSceneRunner runner;

    /**
     * Creates a new runtime services accessor wrapping the given runner.
     *
     * @param runner the interactive scene runner (must not be null)
     * @throws IllegalArgumentException if runner is null
     */
    public RuntimeServices(InteractiveSceneRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("runner must not be null");
        }
        this.runner = runner;
    }

    /**
     * Sets a one-shot post-render callback for the current frame.
     *
     * <p>The callback fires after the full frame is rendered (scene + floor +
     * HUD + pause overlay) but BEFORE the buffer swap. This is the correct
     * point for screenshot capture.</p>
     *
     * @param callback the callback to fire after rendering, or null to clear
     */
    public void setPostRenderCallback(Runnable callback) {
        runner.setPostRenderCallback(callback);
    }

    /**
     * Programmatically sets the paused state.
     *
     * @param paused true to pause, false to resume
     */
    public void setPaused(boolean paused) {
        runner.setPaused(paused);
    }

    /**
     * Returns whether the runner is currently paused.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return runner.isPaused();
    }

    /**
     * Returns the current viewport width from the runner.
     *
     * @return viewport width in pixels
     */
    public int getCurrentWidth() {
        return runner.getCurrentWidth();
    }

    /**
     * Returns the current viewport height from the runner.
     *
     * @return viewport height in pixels
     */
    public int getCurrentHeight() {
        return runner.getCurrentHeight();
    }

    /**
     * Returns the live viewport state.
     *
     * @return the viewport state, never null after runner startup
     */
    public ViewportState getViewport() {
        return runner.getContext().getViewport();
    }

    /**
     * Returns the active artifact service.
     *
     * @return the artifact service, never null after runner startup
     */
    public ArtifactService getArtifactService() {
        return runner.getArtifactService();
    }

    @Override
    public String toString() {
        return "RuntimeServices[runner=" + runner.getClass().getSimpleName() + "]";
    }
}
