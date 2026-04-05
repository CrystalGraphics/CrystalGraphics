package io.github.somehussar.crystalgraphics.harness.runtime;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.GlStateResetHelper;
import io.github.somehussar.crystalgraphics.harness.util.RenderPassState;

import java.util.logging.Logger;

/**
 * Overlay rendering and capture callback orchestrator for the interactive loop.
 *
 * <p>Owns the sequencing of post-scene rendering passes (GL state reset,
 * pause overlay, HUD overlay) and the post-render capture callback.
 * Extracted from {@code InteractiveSceneRunner} to isolate overlay
 * composition and screenshot timing from the main loop body.</p>
 *
 * <p><b>Rendering order contract</b> (must be preserved exactly):</p>
 * <ol>
 *   <li>GL state reset after scene rendering ({@link GlStateResetHelper#resetAfterScene()})</li>
 *   <li>Overlay pass GL state setup ({@link RenderPassState#beginOverlayPass()})</li>
 *   <li>Overlay pipeline render (pause first, HUD second)</li>
 *   <li>Overlay pass GL state restore ({@link RenderPassState#endOverlayPass()})</li>
 *   <li>Capture point ({@link RenderPassState#beginCapturePoint()})</li>
 *   <li>Post-render capture callback (one-shot, after all overlays, before swap)</li>
 * </ol>
 *
 * <p><b>Capture callback semantics</b>: The callback is a one-shot Runnable
 * that fires after the full frame is rendered (scene + floor + HUD + pause)
 * but BEFORE {@code Display.update()}. This is the correct capture point
 * for screenshot timing. The callback is cleared after firing.</p>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 */
public final class OverlayCaptureOrchestrator {

    private static final Logger LOGGER = Logger.getLogger(OverlayCaptureOrchestrator.class.getName());

    private final HarnessContext ctx;
    private final OverlayPipeline overlayPipeline;

    /**
     * Post-render callback that fires after each frame is fully rendered
     * (including floor, HUD, pause overlay) but BEFORE Display.update().
     * Used by test scenes that need to capture screenshots of the current
     * frame's rendered content rather than the previous frame.
     */
    private Runnable postRenderCallback = null;

    /**
     * Creates a new OverlayCaptureOrchestrator.
     *
     * @param ctx           the harness context
     * @param overlayPipeline the owned overlay pipeline (HUD + pause render order)
     */
    public OverlayCaptureOrchestrator(HarnessContext ctx,
                                      OverlayPipeline overlayPipeline) {
        this.ctx = ctx;
        this.overlayPipeline = overlayPipeline;
    }

    /**
     * Executes the post-scene overlay and capture sequence.
     *
     * <p>This method must be called once per frame AFTER the scene's
     * {@code renderFrame()} and floor rendering are complete. It performs
     * the following steps in exact order:</p>
     * <ol>
     *   <li>Reset GL state after scene rendering (unbind shaders, VAOs, etc.)</li>
     *   <li>Set overlay GL state (depth OFF, blend ON with alpha)</li>
     *   <li>Render pause overlay if paused</li>
     *   <li>Render HUD overlay if the scene uses a 3D camera</li>
     *   <li>Restore prior GL state from before overlay pass</li>
     *   <li>Fire and clear the post-render capture callback</li>
     * </ol>
     *
     * @param paused      whether the runner is currently paused
     * @param uses3DCamera whether the active scene uses the 3D camera system
     */
    public void executePostSceneSequence(boolean paused, boolean uses3DCamera) {
        // Post-scene reset: clean up whatever state the scene left dirty
        // (shaders, VAOs, textures, depth/blend flags)
        GlStateResetHelper.resetAfterScene();

        // Overlay pass: set overlay state (depth OFF, blend ON with alpha)
        // Both pause overlay and HUD share this state boundary.
        boolean hasOverlays = overlayPipeline.hasActiveOverlays(paused, uses3DCamera);
        if (hasOverlays) {
            RenderPassState.beginOverlayPass();
            overlayPipeline.renderOverlays(ctx, paused, uses3DCamera);
            RenderPassState.endOverlayPass();
        }

        // Capture point: post-render callback fires for screenshot capture
        RenderPassState.beginCapturePoint();
        if (postRenderCallback != null) {
            Runnable cb = postRenderCallback;
            postRenderCallback = null;
            cb.run();
        }
    }

    /**
     * Schedules a one-shot callback to fire after the CURRENT frame finishes
     * rendering (floor, HUD, pause overlay all drawn) but BEFORE the buffer
     * swap. This is the correct point to capture screenshots that show the
     * current frame's content.
     *
     * <p>Only one callback can be pending at a time. Setting a new callback
     * replaces any previously set but not-yet-fired callback.</p>
     *
     * @param callback the callback to fire after rendering, or null to clear
     */
    public void setPostRenderCallback(Runnable callback) {
        this.postRenderCallback = callback;
    }

    /**
     * Deletes GL resources owned by the overlay pipeline.
     * Called during runner shutdown to clean up HUD and pause screen resources.
     */
    public void deletePipeline() {
        overlayPipeline.delete();
    }

    @Override
    public String toString() {
        return "OverlayCaptureOrchestrator[hasPendingCallback=" + (postRenderCallback != null) + "]";
    }
}
