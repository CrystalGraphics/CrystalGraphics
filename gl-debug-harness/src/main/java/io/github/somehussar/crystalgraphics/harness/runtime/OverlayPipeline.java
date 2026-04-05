package io.github.somehussar.crystalgraphics.harness.runtime;

import io.github.somehussar.crystalgraphics.harness.camera.HUDRenderer;
import io.github.somehussar.crystalgraphics.harness.camera.PauseScreenRenderer;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import java.util.logging.Logger;

/**
 * Overlay pipeline coordinator that groups HUD and pause overlays under
 * a single owner with explicit render order, resize notification, and
 * lifecycle management.
 *
 * <p>This coordinator replaces the pattern where individual overlay renderers
 * (HUD, pause) were created and managed as loose fields scattered across
 * the runner and orchestrator. Instead, the overlay pipeline owns all
 * overlay renderers and presents a unified interface for:</p>
 * <ul>
 *   <li><b>Initialization</b> — {@link #init()} creates all overlay GL resources</li>
 *   <li><b>Rendering</b> — {@link #renderOverlays(HarnessContext, boolean, boolean)}
 *       renders pause and HUD in the correct order</li>
 *   <li><b>Resize</b> — {@link #onResize(int, int)} propagates resize events to
 *       all overlay renderers through one coordinator path</li>
 *   <li><b>Cleanup</b> — {@link #delete()} releases all overlay GL resources</li>
 * </ul>
 *
 * <h3>Render order contract</h3>
 * <p>Overlays are rendered in a fixed order after the scene pass and
 * post-scene GL state reset:</p>
 * <ol>
 *   <li>Pause overlay (if paused) — semi-transparent bar at bottom of screen</li>
 *   <li>HUD overlay (if 3D camera active) — camera position/rotation text</li>
 * </ol>
 * <p>This order ensures the HUD text renders on top of the pause overlay
 * when both are active simultaneously.</p>
 *
 * <h3>Resize-aware ownership</h3>
 * <p>Each overlay renderer has different resize behavior:</p>
 * <ul>
 *   <li>{@link HUDRenderer} — invalidates cached dimensions so font size
 *       and orthographic projection are recalculated on next render</li>
 *   <li>{@link PauseScreenRenderer} — no-op (quad vertices are recomputed
 *       from screen dimensions each frame)</li>
 * </ul>
 * <p>The pipeline routes resize notifications to all renderers uniformly,
 * so each renderer's resize behavior is encapsulated within its own class.
 * External callers (e.g. {@link ResizeHandler}) only need to notify the
 * pipeline, not each renderer individually.</p>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * @see OverlayCaptureOrchestrator
 * @see io.github.somehussar.crystalgraphics.harness.util.RenderPassState
 */
public final class OverlayPipeline {

    private static final Logger LOGGER = Logger.getLogger(OverlayPipeline.class.getName());

    private final HUDRenderer hudRenderer;
    private final PauseScreenRenderer pauseRenderer;

    /**
     * Creates a new overlay pipeline owning the given HUD and pause renderers.
     *
     * <p>The pipeline takes ownership of both renderer instances — callers
     * should not init, render, resize, or delete them directly after passing
     * them to this constructor.</p>
     *
     * @param hudRenderer   the HUD overlay renderer (must not be null)
     * @param pauseRenderer the pause screen overlay renderer (must not be null)
     */
    public OverlayPipeline(HUDRenderer hudRenderer, PauseScreenRenderer pauseRenderer) {
        if (hudRenderer == null) {
            throw new IllegalArgumentException("hudRenderer must not be null");
        }
        if (pauseRenderer == null) {
            throw new IllegalArgumentException("pauseRenderer must not be null");
        }
        this.hudRenderer = hudRenderer;
        this.pauseRenderer = pauseRenderer;
    }

    /**
     * Initializes all overlay renderers' GL resources.
     *
     * <p>Must be called once with a valid GL context before any rendering.
     * Delegates to each renderer's init method in dependency order.</p>
     */
    public void init() {
        hudRenderer.init();
        pauseRenderer.init();
        LOGGER.info("[OverlayPipeline] All overlay renderers initialized.");
    }

    /**
     * Renders active overlays in the correct order.
     *
     * <p>This method should be called during the overlay pass, after
     * {@link io.github.somehussar.crystalgraphics.harness.util.RenderPassState#beginOverlayPass()}
     * has set the required GL state (depth OFF, blend ON with alpha).</p>
     *
     * <p><b>Render order</b>: pause overlay first, then HUD. This ensures
     * the HUD text is drawn on top of the pause overlay when both are
     * visible.</p>
     *
     * @param ctx          the harness context (provides screen dimensions, camera)
     * @param paused       whether the runner is currently paused
     * @param uses3DCamera whether the active scene uses the 3D camera system
     */
    public void renderOverlays(HarnessContext ctx, boolean paused, boolean uses3DCamera) {
        // Pause overlay renders first (below HUD text)
        if (paused) {
            pauseRenderer.render(ctx);
        }

        // HUD renders second (on top of pause overlay if both active)
        if (uses3DCamera) {
            hudRenderer.render(ctx);
        }
    }

    /**
     * Propagates a display resize event to all overlay renderers.
     *
     * <p>This is the single resize notification path for overlays.
     * External callers (e.g. {@link ResizeHandler}) should call this
     * method rather than notifying individual renderers directly.</p>
     *
     * <p>Each renderer handles resize according to its own needs:</p>
     * <ul>
     *   <li>HUD — invalidates cached dimensions for font/projection recalc</li>
     *   <li>Pause — no-op (recomputes quad geometry each frame anyway)</li>
     * </ul>
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onResize(int newWidth, int newHeight) {
        hudRenderer.onDisplayResize(newWidth, newHeight);
        pauseRenderer.onDisplayResize(newWidth, newHeight);
    }

    /**
     * Releases all GL resources held by overlay renderers.
     *
     * <p>Called during runner shutdown. After this call, no overlay
     * methods should be invoked.</p>
     */
    public void delete() {
        hudRenderer.delete();
        pauseRenderer.delete();
        LOGGER.info("[OverlayPipeline] All overlay renderers deleted.");
    }

    /**
     * Returns whether any overlay would render given the current state.
     *
     * <p>Used by the orchestrator to decide whether to set up and tear
     * down overlay GL state (depth OFF, blend ON). If no overlays are
     * active, the state boundary can be skipped entirely.</p>
     *
     * @param paused       whether the runner is currently paused
     * @param uses3DCamera whether the active scene uses the 3D camera system
     * @return true if at least one overlay would render
     */
    public boolean hasActiveOverlays(boolean paused, boolean uses3DCamera) {
        return paused || uses3DCamera;
    }

    /**
     * Returns the HUD renderer owned by this pipeline.
     *
     * <p><b>Internal use only</b> — exposed for the orchestrator and
     * resize handler during the migration period. External code should
     * interact with the pipeline, not individual renderers.</p>
     *
     * @return the HUD renderer
     */
    HUDRenderer getHudRenderer() {
        return hudRenderer;
    }

    /**
     * Returns the pause renderer owned by this pipeline.
     *
     * <p><b>Internal use only</b> — exposed for the orchestrator and
     * resize handler during the migration period. External code should
     * interact with the pipeline, not individual renderers.</p>
     *
     * @return the pause screen renderer
     */
    PauseScreenRenderer getPauseRenderer() {
        return pauseRenderer;
    }

    @Override
    public String toString() {
        return "OverlayPipeline[hud=" + hudRenderer + ", pause=" + pauseRenderer + "]";
    }
}
