package io.github.somehussar.crystalgraphics.harness;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

/**
 * Unified scene lifecycle contract (v2) for the debug harness.
 *
 * <p>This interface defines the canonical lifecycle hooks that both managed
 * (single-shot FBO) and interactive (continuous render loop) runtimes use
 * to drive scene execution with one coherent set of hooks:</p>
 *
 * <ol>
 *   <li>{@link #init(HarnessContext)} — one-time GL resource setup</li>
 *   <li>{@link #render(HarnessContext, FrameInfo)} — called once per frame
 *       (or exactly once for managed single-shot scenes)</li>
 *   <li>{@link #onResize(int, int)} — optional display resize notification</li>
 *   <li>{@link #dispose()} — release GL resources</li>
 * </ol>
 *
 * <h3>Runtime separation</h3>
 * <p>Managed and interactive runtimes remain separate execution strategies.
 * The managed runtime calls {@code init → render (once) → dispose}.
 * The interactive runtime calls {@code init → render (loop) → dispose}.
 * This interface does NOT force managed scenes through the interactive runner
 * or vice versa.</p>
 *
 * <h3>Interactive extension</h3>
 * <p>Interactive scenes that need render-loop control (running state, camera
 * mode, shutdown control) should implement the {@link InteractiveSceneLifecycle}
 * sub-interface, which adds the required interactive hooks without polluting
 * this base interface.</p>
 *
 * @see InteractiveSceneLifecycle
 * @see FrameInfo
 */
public interface HarnessSceneLifecycle {

    /**
     * Initializes scene GL resources. Called once before any render calls.
     *
     * <p>For managed scenes, the context provides output settings and config.
     * For interactive scenes, it additionally provides camera, scheduler, and
     * runtime services.</p>
     *
     * @param ctx the harness context containing all configuration and shared resources
     */
    void init(HarnessContext ctx);

    /**
     * Renders a single frame of scene content.
     *
     * <p>For managed single-shot scenes, this is called exactly once after init.
     * For interactive scenes, this is called once per frame in the render loop.
     * The {@link FrameInfo} parameter provides timing and frame numbering.</p>
     *
     * <p>In the interactive runtime, floor rendering, HUD, and pause overlays
     * are handled by the runtime — scenes should only render their own content.</p>
     *
     * @param ctx   the harness context
     * @param frame the current frame's timing and numbering information
     */
    void render(HarnessContext ctx, FrameInfo frame);

    /**
     * Called when the display is resized. Optional — the default implementation
     * does nothing.
     *
     * <p>Only relevant for interactive scenes. Managed scenes render to a
     * fixed-size FBO and never receive resize events.</p>
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    default void onResize(int width, int height) {
        // Default: no-op. Override if the scene needs resize-aware behavior.
    }

    /**
     * Releases all GL resources held by this scene. Called once after all
     * rendering is complete.
     *
     * <p>This is guaranteed to be called even if rendering fails, as long
     * as {@link #init(HarnessContext)} completed successfully.</p>
     */
    void dispose();
}
