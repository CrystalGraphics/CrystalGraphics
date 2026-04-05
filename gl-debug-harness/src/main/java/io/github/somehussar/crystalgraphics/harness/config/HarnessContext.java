package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;

import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import java.util.logging.Logger;

/**
 * Central context object for the debug harness, serving as the single source
 * of truth for all configuration, screen dimensions, and shared resources.
 *
 * <p>Holds the GL context information (version, vendor, renderer), screen
 * dimensions (mutable, updated on resize), output directory/name settings,
 * and references to shared subsystems (Camera3D, TaskScheduler, runner).</p>
 *
 * <p>All scenes and renderers should read configuration from this context
 * rather than using hardcoded values or separate parameter passing.</p>
 */
public final class HarnessContext {

    private static final Logger LOGGER = Logger.getLogger(HarnessContext.class.getName());

    /** Default initial screen width. */
    public static final int DEFAULT_WIDTH = 800;
    /** Default initial screen height. */
    public static final int DEFAULT_HEIGHT = 600;

    // ── Immutable GL context info (set at creation) ──
    private final String glVersion;
    private final String glVendor;
    private final String glRenderer;

    // ── Mutable screen dimensions (updated on Display resize) ──
    private int screenWidth;
    private int screenHeight;

    // ── Output configuration ──
    private String outputDir;
    private String outputName;

    // ── Shared subsystem references (set by InteractiveSceneRunner for interactive scenes) ──
    private Camera3D camera3D;
    private TaskScheduler taskScheduler;

    /**
     * Reference to the InteractiveSceneRunner driving this context.
     * Stored as Object to avoid circular dependency — scenes that need the runner
     * should cast to InteractiveSceneRunner. Only non-null for INTERACTIVE scenes.
     */
    private Object runner;

    private HarnessContext(String glVersion, String glVendor, String glRenderer,
                           int screenWidth, int screenHeight) {
        this.glVersion = glVersion;
        this.glVendor = glVendor;
        this.glRenderer = glRenderer;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * Creates a new HarnessContext by initializing the LWJGL Display and
     * OpenGL 3.0 context with the default dimensions (800×600).
     *
     * @return a fully initialized context with GL info populated
     * @throws RuntimeException if the GL context cannot be created
     */
    public static HarnessContext create() {
        return create(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Creates a new HarnessContext by initializing the LWJGL Display and
     * OpenGL 3.0 context with the specified dimensions.
     *
     * @param width  initial window width in pixels
     * @param height initial window height in pixels
     * @return a fully initialized context with GL info populated
     * @throws RuntimeException if the GL context cannot be created
     */
    public static HarnessContext create(int width, int height) {
        try {
            Display.setDisplayMode(new DisplayMode(width, height));
            Display.setTitle("CrystalGraphics Debug Harness");

            // Enable window resizing and maximize button
            Display.setResizable(true);

            // Request OpenGL 3.0 forward-compatible context
            ContextAttribs attribs = new ContextAttribs(3, 0)
                    .withForwardCompatible(false);

            Display.create(new PixelFormat(), attribs);
        } catch (LWJGLException e) {
            throw new RuntimeException("Failed to create OpenGL 3.0 context: " + e.getMessage(), e);
        }

        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        String glVendor = GL11.glGetString(GL11.GL_VENDOR);
        String glRenderer = GL11.glGetString(GL11.GL_RENDERER);

        LOGGER.info("[Harness] GL Version:  " + glVersion);
        LOGGER.info("[Harness] GL Vendor:   " + glVendor);
        LOGGER.info("[Harness] GL Renderer: " + glRenderer);

        return new HarnessContext(glVersion, glVendor, glRenderer, width, height);
    }

    /**
     * Destroys the LWJGL Display and releases all GL resources.
     */
    public void destroy() {
        Display.destroy();
        LOGGER.info("[Harness] Display destroyed.");
    }

    // ── GL context info (immutable) ──

    public String getGlVersion() { return glVersion; }
    public String getGlVendor() { return glVendor; }
    public String getGlRenderer() { return glRenderer; }

    // ── Screen dimensions (mutable, updated on resize) ──

    /**
     * Returns the current screen/viewport width in pixels.
     * Updated automatically when {@code Display.wasResized()} is detected.
     */
    public int getScreenWidth() { return screenWidth; }

    /**
     * Returns the current screen/viewport height in pixels.
     * Updated automatically when {@code Display.wasResized()} is detected.
     */
    public int getScreenHeight() { return screenHeight; }

    /**
     * Updates the stored screen dimensions. Called by the InteractiveSceneRunner
     * when the Display is resized.
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void setScreenDimensions(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
    }

    // ── Output configuration ──

    /**
     * Returns the output directory for screenshots and artifacts.
     * This is the scene-specific subdirectory (e.g. {@code harness-output/world-text-3d/}).
     */
    public String getOutputDir() { return outputDir; }

    /**
     * Sets the output directory path.
     *
     * @param outputDir the scene-specific output directory path
     */
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    /**
     * Returns the output name prefix for filenames.
     * For example, "test1" causes screenshots to be named "test1-normal.png".
     */
    public String getOutputName() { return outputName; }

    /**
     * Sets the output name prefix.
     *
     * @param outputName the filename prefix (from {@code --output-name} or scene mode ID)
     */
    public void setOutputName(String outputName) { this.outputName = outputName; }

    // ── Shared subsystem references ──

    /**
     * Returns the shared 3D camera, or null if not in interactive mode.
     */
    public Camera3D getCamera3D() { return camera3D; }

    /**
     * Sets the shared 3D camera reference. Called by InteractiveSceneRunner
     * before scene init.
     */
    public void setCamera3D(Camera3D camera3D) { this.camera3D = camera3D; }

    /**
     * Returns the shared task scheduler, or null if not in interactive mode.
     */
    public TaskScheduler getTaskScheduler() { return taskScheduler; }

    /**
     * Sets the shared task scheduler reference. Called by InteractiveSceneRunner
     * before scene init.
     */
    public void setTaskScheduler(TaskScheduler taskScheduler) { this.taskScheduler = taskScheduler; }

    /**
     * Returns the InteractiveSceneRunner driving this context, or null if not
     * in interactive mode. Callers should cast to InteractiveSceneRunner.
     */
    public Object getRunner() { return runner; }

    /**
     * Sets the runner reference. Called by InteractiveSceneRunner before scene init.
     */
    public void setRunner(Object runner) { this.runner = runner; }
}
