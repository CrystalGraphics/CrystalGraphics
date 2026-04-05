package io.github.somehussar.crystalgraphics.harness.debug;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.util.logging.Logger;

/**
 * LLM debug tools for programmatic camera control and screenshot capture.
 *
 * <p>These methods are designed for automated testing and validation by
 * LLM agents. They provide direct control over the camera and frame
 * capture without requiring user input.</p>
 */
public final class HarnessDebugTools {

    private static final Logger LOGGER = Logger.getLogger(HarnessDebugTools.class.getName());

    private final Camera3D camera;
    private final String outputDir;
    private final String outputNamePrefix;
    private final int viewportWidth;
    private final int viewportHeight;

    /**
     * @param camera           the active 3D camera to control
     * @param outputDir        directory for screenshot output
     * @param outputNamePrefix prefix for output filenames (e.g. "my-test" yields "my-test-front.png")
     * @param viewportWidth    current viewport width in pixels
     * @param viewportHeight   current viewport height in pixels
     */
    public HarnessDebugTools(Camera3D camera, String outputDir, String outputNamePrefix,
                             int viewportWidth, int viewportHeight) {
        this.camera = camera;
        this.outputDir = outputDir;
        this.outputNamePrefix = outputNamePrefix;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
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
     * Captures a screenshot of the current backbuffer to a PNG file.
     *
     * <p>The screenshot is written to the configured output directory.
     * Call this after rendering a frame to capture the current view.</p>
     *
     * @param filename the output filename (e.g. "top-down.png")
     */
    public void screenshot(String filename) {
        LOGGER.info("[DebugTools] screenshot: " + filename
                + " (" + viewportWidth + "x" + viewportHeight + ")"
                + " camera=" + camera);
        ScreenshotUtil.captureBackbuffer(viewportWidth, viewportHeight, outputDir, filename);
    }

    /**
     * Builds an output filename by combining the output name prefix with a suffix.
     * For example, prefix="my-test" + suffix="front" yields "my-test-front.png".
     *
     * @param suffix the descriptive suffix (without extension)
     * @return the full filename including .png extension
     */
    public String prefixedFilename(String suffix) {
        return outputNamePrefix + "-" + suffix + ".png";
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
        ScreenshotUtil.captureFboColorTexture(fboId, texId, width, height, outputDir, filename);
    }

    public Camera3D getCamera() { return camera; }
    public String getOutputDir() { return outputDir; }
    public String getOutputNamePrefix() { return outputNamePrefix; }
}
