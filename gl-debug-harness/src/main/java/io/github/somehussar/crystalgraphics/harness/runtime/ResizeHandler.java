package io.github.somehussar.crystalgraphics.harness.runtime;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.logging.Logger;

/**
 * Resize propagation service for the interactive render loop.
 *
 * <p>Detects LWJGL Display resize events and propagates the new dimensions
 * to the context viewport, GL viewport, and the dedicated world/overlay
 * coordinators that own resize-aware render contributors.</p>
 *
 * <p>Extracted from {@code InteractiveSceneRunner} to centralize resize
 * handling in one place rather than inlining it in the main loop body.
 * This makes the resize path explicit and machine-verifiable: all resize
 * consumers are listed in this class's constructor and updated in
 * {@link #checkAndPropagate()}.</p>
 *
 * <p><b>Resize propagation order</b>:</p>
 * <ol>
 *   <li>Read new dimensions from {@code Display.getWidth()/getHeight()}</li>
 *   <li>Update {@code HarnessContext} viewport state</li>
 *   <li>Update GL viewport via {@code glViewport}</li>
 *   <li>Notify the overlay pipeline (HUD + pause ownership)</li>
 *   <li>Notify the world-pass coordinator (floor/world-base ownership)</li>
 * </ol>
 *
 * <p><b>Thread safety</b>: Only used on the LWJGL render thread.</p>
 *
 * <p><b>Frame ordering contract</b>: {@link #checkAndPropagate()} must be
 * called once per frame at the start of the frame loop, after timing but
 * before input polling, camera updates, and rendering.</p>
 */
public final class ResizeHandler {

    private static final Logger LOGGER = Logger.getLogger(ResizeHandler.class.getName());

    private final HarnessContext ctx;
    private final OverlayPipeline overlayPipeline;
    private final WorldPassCoordinator worldPassCoordinator;

    /**
     * Creates a new ResizeHandler with all resize-aware consumers.
     *
     * @param ctx           the harness context (owns viewport state)
     * @param worldPassCoordinator the world-base coordinator that owns floor contributions
     * @param overlayPipeline      the overlay pipeline that owns HUD and pause renderers
     */
    public ResizeHandler(HarnessContext ctx,
                         WorldPassCoordinator worldPassCoordinator,
                         OverlayPipeline overlayPipeline) {
        this.ctx = ctx;
        this.worldPassCoordinator = worldPassCoordinator;
        this.overlayPipeline = overlayPipeline;
    }

    /**
     * Checks if the LWJGL Display was resized since the last frame and,
     * if so, propagates the new dimensions to all resize-aware consumers.
     *
     * <p>This method is idempotent within a frame: if the display was not
     * resized, it returns immediately with no side effects.</p>
     *
     * @return true if a resize was detected and propagated, false otherwise
     */
    public boolean checkAndPropagate() {
        if (!Display.wasResized()) {
            return false;
        }

        int newWidth = Display.getWidth();
        int newHeight = Display.getHeight();

        // Update context viewport state — single source of truth for dimensions
        ctx.setScreenDimensions(newWidth, newHeight);

        // Update GL viewport to match new window size
        GL11.glViewport(0, 0, newWidth, newHeight);

        overlayPipeline.onResize(newWidth, newHeight);
        worldPassCoordinator.onResize(newWidth, newHeight);

        LOGGER.fine("[ResizeHandler] Display resized to " + newWidth + "x" + newHeight);
        return true;
    }

    @Override
    public String toString() {
        return "ResizeHandler[viewport=" + ctx.getViewport() + "]";
    }
}
