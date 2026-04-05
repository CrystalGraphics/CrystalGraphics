package io.github.somehussar.crystalgraphics.harness.capture;

import io.github.somehussar.crystalgraphics.harness.config.OutputSettings;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.util.logging.Logger;

/**
 * Framework-owned capture and artifact service for the debug harness.
 *
 * <p>Centralizes all artifact output concerns that were previously scattered
 * across individual scenes: output directory resolution, filename prefix
 * composition, and full-frame screenshot capture scheduling. Scenes interact
 * with this service semantically — they request a capture by suffix name
 * (e.g. "front-view", "paused") and the service handles path composition,
 * viewport dimension lookup, and post-render callback coordination.</p>
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li><b>No stale state</b>: Viewport dimensions are read live from
 *       {@link ViewportState} at capture time, never cached.</li>
 *   <li><b>Semantic requests</b>: Scenes call {@link #requestCapture(String)}
 *       with a descriptive suffix; the service composes the full filename
 *       from the configured output-name prefix.</li>
 *   <li><b>Post-render capture</b>: All interactive captures are scheduled
 *       as post-render callbacks via {@link CaptureCallback}, ensuring the
 *       screenshot includes the full frame (scene + floor + HUD + pause overlay).</li>
 *   <li><b>No asset pipeline</b>: This is a simple capture service, not a
 *       generic asset management framework.</li>
 * </ul>
 *
 * <h3>Usage from scenes</h3>
 * <pre>{@code
 * // In scene init:
 * ArtifactService artifacts = ctx.getArtifactService();
 *
 * // Schedule a capture (will fire after the next full frame render):
 * artifacts.requestCapture("front-view");
 * // → writes: {outputDir}/{outputName}-front-view.png
 *
 * // For FBO captures (not post-render):
 * artifacts.captureFbo(fboId, texId, width, height, "fbo-dump");
 * }</pre>
 *
 * @see OutputSettings
 * @see ViewportState
 * @see CaptureCallback
 */
public final class ArtifactService {

    private static final Logger LOGGER = Logger.getLogger(ArtifactService.class.getName());

    private final OutputSettings output;
    private final ViewportState viewport;
    private final CaptureCallback captureCallback;

    /**
     * Creates an artifact service with the given output settings, viewport state,
     * and capture callback for post-render screenshot scheduling.
     *
     * @param output          immutable output settings (directory + name prefix)
     * @param viewport        live viewport dimensions (read at capture time)
     * @param captureCallback callback sink for scheduling post-render captures
     * @throws IllegalArgumentException if any argument is null
     */
    public ArtifactService(OutputSettings output, ViewportState viewport,
                           CaptureCallback captureCallback) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        if (viewport == null) {
            throw new IllegalArgumentException("viewport must not be null");
        }
        if (captureCallback == null) {
            throw new IllegalArgumentException("captureCallback must not be null");
        }
        this.output = output;
        this.viewport = viewport;
        this.captureCallback = captureCallback;
    }

    /**
     * Requests a full-frame backbuffer capture with the given semantic suffix.
     *
     * <p>The capture is scheduled as a post-render callback, meaning it will
     * fire after the current frame is fully rendered (scene + floor + HUD +
     * pause overlay) but before the buffer swap. This ensures the screenshot
     * captures the complete composed frame.</p>
     *
     * <p>The filename is composed as: {@code {outputName}-{suffix}.png}</p>
     *
     * @param suffix the descriptive suffix (e.g. "front-view", "paused", "normal")
     */
    public void requestCapture(final String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            throw new IllegalArgumentException("suffix must not be null or empty");
        }
        final String filename = output.buildFilename(suffix);
        final String outputDir = output.getOutputDir();

        LOGGER.info("[ArtifactService] Scheduling capture: " + filename);

        // Schedule the actual capture as a post-render callback.
        // Viewport dimensions are read at capture time (not now) to avoid
        // stale values if a resize occurs between request and capture.
        captureCallback.schedulePostRenderCapture(new Runnable() {
            @Override
            public void run() {
                int w = viewport.getWidth();
                int h = viewport.getHeight();
                ScreenshotUtil.captureBackbuffer(w, h, outputDir, filename);
                LOGGER.info("[ArtifactService] Captured " + filename
                        + " (" + w + "x" + h + ")");
            }
        });
    }

    /**
     * Immediately captures an FBO's color attachment to a PNG file.
     *
     * <p>Unlike {@link #requestCapture(String)}, this method executes
     * synchronously because FBO captures don't need to wait for the
     * full frame pipeline to complete.</p>
     *
     * @param fboId   the framebuffer object ID
     * @param texId   the color attachment texture ID
     * @param width   FBO width in pixels
     * @param height  FBO height in pixels
     * @param suffix  the descriptive suffix for the filename
     */
    public void captureFbo(int fboId, int texId, int width, int height, String suffix) {
        String filename = output.buildFilename(suffix);
        String outputDir = output.getOutputDir();
        ScreenshotUtil.captureFboColorTexture(fboId, texId, width, height, outputDir, filename);
        LOGGER.info("[ArtifactService] Captured FBO " + filename
                + " (" + width + "x" + height + ")");
    }

    /**
     * Immediately captures a backbuffer screenshot with the given suffix.
     *
     * <p>Unlike {@link #requestCapture(String)}, this executes synchronously
     * using the current viewport dimensions. Use this only when you are
     * certain the backbuffer contains the desired content (e.g. inside a
     * post-render callback that you manage manually).</p>
     *
     * @param suffix the descriptive suffix for the filename
     */
    public void captureNow(String suffix) {
        String filename = output.buildFilename(suffix);
        String outputDir = output.getOutputDir();
        int w = viewport.getWidth();
        int h = viewport.getHeight();
        ScreenshotUtil.captureBackbuffer(w, h, outputDir, filename);
        LOGGER.info("[ArtifactService] Captured (sync) " + filename
                + " (" + w + "x" + h + ")");
    }

    /**
     * Writes a raw file (non-PNG artifact like a text report) to the output directory.
     *
     * <p>The filename is composed as: {@code {outputName}-{suffix}}</p>
     * <p>Note: unlike capture methods, no {@code .png} extension is appended.
     * The caller should include the full suffix with extension.</p>
     *
     * @param filename the exact filename to write (e.g. "report.txt")
     * @return the full file path for the caller to write to
     */
    public String resolveOutputPath(String filename) {
        return output.getOutputDir() + java.io.File.separator + filename;
    }

    /**
     * Returns the configured output name prefix.
     *
     * <p>Useful for scenes that need to compose filenames with custom
     * extensions or patterns not covered by the standard capture methods.</p>
     *
     * @return the output name prefix, never null
     */
    public String getOutputName() {
        return output.getOutputName();
    }

    /**
     * Returns the configured output directory.
     *
     * @return the output directory path, never null
     */
    public String getOutputDir() {
        return output.getOutputDir();
    }

    /**
     * Returns the underlying output settings.
     *
     * @return immutable output settings, never null
     */
    public OutputSettings getOutputSettings() {
        return output;
    }

    /**
     * Returns the live viewport state (for callers that need width/height
     * outside of capture operations).
     *
     * @return the viewport state, never null
     */
    public ViewportState getViewport() {
        return viewport;
    }
}
