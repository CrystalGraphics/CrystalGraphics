package io.github.somehussar.crystalgraphics.harness.debug;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.util.logging.Logger;

/**
 * LLM debug tools for programmatic camera control and screenshot capture.
 *
 * <p>These methods are designed for automated testing and validation by
 * LLM agents. They provide direct control over the camera and frame
 * capture without requiring user input.</p>
 *
 * <p>Screenshot capture is delegated to the framework-owned
 * {@link ArtifactService}, which handles output directory resolution,
 * filename composition, and viewport dimension lookup. This ensures
 * captures always use live viewport dimensions (no stale caching)
 * and follow the centralized artifact naming conventions.</p>
 */
public final class HarnessDebugTools {

    private static final Logger LOGGER = Logger.getLogger(HarnessDebugTools.class.getName());

    private final Camera3D camera;
    private final ArtifactService artifacts;

    /**
     * Creates debug tools backed by the given camera and artifact service.
     *
     * @param camera    the active 3D camera to control
     * @param artifacts the framework-owned artifact service for captures
     */
    public HarnessDebugTools(Camera3D camera, ArtifactService artifacts) {
        this.camera = camera;
        this.artifacts = artifacts;
    }

    /**
     * Rotates the camera by the given delta angles.
     *
     * @param yawDelta   horizontal rotation in degrees (positive = turn right)
     * @param pitchDelta vertical rotation in degrees (positive = look up)
     */
    public void rotateCamera(float yawDelta, float pitchDelta) {
        camera.rotateCamera(yawDelta, pitchDelta);
        LOGGER.info("[DebugTools] rotateCamera: yawDelta=" + yawDelta
                + " pitchDelta=" + pitchDelta
                + " -> yaw=" + camera.getYaw() + " pitch=" + camera.getPitch());
    }

    /**
     * Sets the camera to an absolute position. Y is clamped above the floor.
     *
     * @param x world X position
     * @param y world Y position (clamped above floor at Y=0)
     * @param z world Z position
     */
    public void moveCamera(float x, float y, float z) {
        camera.moveCamera(x, y, z);
        LOGGER.info("[DebugTools] moveCamera: (" + x + ", " + y + ", " + z + ")"
                + " -> actual=(" + camera.getPosX() + ", " + camera.getPosY() + ", " + camera.getPosZ() + ")");
    }

    /**
     * Requests a full-frame screenshot capture with the given semantic suffix.
     *
     * <p>The capture is scheduled as a post-render callback via the
     * {@link ArtifactService}, meaning it fires after the full frame
     * (scene + floor + HUD + pause overlay) is rendered. Viewport
     * dimensions are read live at capture time — never stale.</p>
     *
     * @param suffix the descriptive suffix (e.g. "front-view", "normal")
     */
    public void screenshotSemantic(String suffix) {
        LOGGER.info("[DebugTools] screenshotSemantic: suffix=" + suffix
                + " camera=" + camera);
        artifacts.requestCapture(suffix);
    }

    /**
     * Captures a screenshot of the current backbuffer to a PNG file.
     *
     * <p>The screenshot is scheduled as a post-render callback via the
     * {@link ArtifactService}. The filename is used as-is (without prefix
     * composition) for backward compatibility with scenes that pass
     * pre-composed filenames.</p>
     *
     * @param filename the output filename (e.g. "my-test-front-view.png")
     * @deprecated Use {@link #screenshotSemantic(String)} instead, which
     *             lets the artifact service compose the filename from the
     *             output-name prefix.
     */
    public void screenshot(String filename) {
        LOGGER.info("[DebugTools] screenshot: " + filename + " camera=" + camera);
        // Extract the semantic suffix from the pre-composed filename and route
        // through the artifact service's post-render capture path. This fixes the
        // historical bug where screenshot() captured the PREVIOUS frame's content
        // because it was called during a scheduler tick, not after rendering.
        artifacts.requestCapture(extractSuffix(filename));
    }

    /**
     * Extracts the semantic suffix from a pre-composed filename.
     *
     * <p>Given a filename like "capture-check-front-view.png" and an output-name
     * prefix of "capture-check", extracts "front-view". Falls back to the raw
     * filename (minus .png) if the prefix doesn't match.</p>
     */
    private String extractSuffix(String filename) {
        String prefix = artifacts.getOutputName();
        String base = filename;
        if (base.endsWith(".png")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.startsWith(prefix + "-")) {
            return base.substring(prefix.length() + 1);
        }
        return base;
    }

    /**
     * Builds an output filename by combining the output name prefix with a suffix.
     * For example, prefix="my-test" + suffix="front" yields "my-test-front.png".
     *
     * @param suffix the descriptive suffix (without extension)
     * @return the full filename including .png extension
     */
    public String prefixedFilename(String suffix) {
        return artifacts.getOutputSettings().buildFilename(suffix);
    }

    /**
     * Captures a screenshot from an FBO's color texture.
     *
     * @param fboId     the framebuffer object ID
     * @param texId     the color attachment texture ID
     * @param width     FBO width
     * @param height    FBO height
     * @param filename  output filename
     */
    public void screenshotFbo(int fboId, int texId, int width, int height, String filename) {
        LOGGER.info("[DebugTools] screenshotFbo: fbo=" + fboId + " tex=" + texId
                + " " + width + "x" + height + " -> " + filename);
        ScreenshotUtil.captureFboColorTexture(
                fboId, texId, width, height, artifacts.getOutputDir(), filename);
    }

    public Camera3D getCamera() { return camera; }
    public String getOutputDir() { return artifacts.getOutputDir(); }
    public String getOutputNamePrefix() { return artifacts.getOutputName(); }

    /**
     * Returns the underlying artifact service.
     */
    public ArtifactService getArtifactService() { return artifacts; }
}
