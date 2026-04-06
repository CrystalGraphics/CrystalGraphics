package io.github.somehussar.crystalgraphics.harness.config;

import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;

import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;

import java.util.logging.Logger;

/**
 * Central context object for the debug harness, serving as the composition
 * root for all configuration, viewport state, output settings, and shared
 * runtime services.
 *
 * <p>Holds the GL context information (version, vendor, renderer) as immutable
 * fields, and composes typed sub-objects for viewport dimensions
 * ({@link ViewportState}), output configuration ({@link OutputSettings}),
 * and interactive runtime access ({@link RuntimeServices}).</p>
 *
 * <p>Scenes and renderers access configuration through typed accessors
 * ({@link #getViewport()}, {@link #getOutputSettings()},
 * {@link #getRuntimeServices()}) rather than a loose bag of mutable fields.
 * This design ensures that:</p>
 * <ul>
 *   <li>Viewport dimensions are tracked in one place and updated on resize</li>
 *   <li>Output settings are immutable once resolved before scene init</li>
 *   <li>Runtime services are strongly typed (no Object casts)</li>
 * </ul>
 *
 * <p>Compatibility accessors were removed after migration completion; callers
 * should use the typed accessors directly.</p>
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

    // ── Typed sub-objects ──

    /**
     * Mutable viewport state tracking current screen dimensions.
     * Updated by the runner on Display resize events.
     */
    private final ViewportState viewport;

    /**
     * Immutable output settings resolved before scene init.
     * Null until {@link #setOutputSettings(OutputSettings)} is called.
     */
    private OutputSettings outputSettings;

    /**
     * Typed runtime services for interactive scenes.
     * Null for MANAGED/DIAGNOSTIC scenes.
     */
    private RuntimeServices runtimeServices;

    /**
     * Framework-owned artifact capture service for interactive scenes.
     * Null for MANAGED/DIAGNOSTIC scenes.
     */
    private ArtifactService artifactService;

    // ── Typed scene configuration ──
    // The resolved HarnessConfig (or subclass like TextSceneConfig, AtlasDumpConfig)
    // built once in FontDebugHarnessMain from defaults → system props → CLI args.
    // Scenes read this instead of re-parsing raw CLI args from a global static.
    private HarnessConfig sceneConfig;

    /**
     * Immutable world settings resolved once per run from {@link WorldConfig}
     * defaults. Null until set by the main entry point or runner.
     */
    private WorldSettings worldSettings;

    // ── Shared subsystem references (set by InteractiveSceneRunner for interactive scenes) ──
    private Camera3D camera3D;
    private TaskScheduler taskScheduler;

    private HarnessContext(String glVersion, String glVendor, String glRenderer,
                           int screenWidth, int screenHeight) {
        this.glVersion = glVersion;
        this.glVendor = glVendor;
        this.glRenderer = glRenderer;
        this.viewport = new ViewportState(screenWidth, screenHeight);
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

    // ── Typed sub-object accessors ──

    /**
     * Returns the typed viewport state tracking current screen dimensions.
     *
     * <p>The viewport is always available (never null). Its dimensions are
     * updated by the runtime when the display is resized.</p>
     *
     * @return the viewport state, never null
     */
    public ViewportState getViewport() {
        return viewport;
    }

    /**
     * Returns the typed output settings, or null if not yet configured.
     *
     * <p>Output settings are resolved and set before scene init by the
     * main entry point. Once set, they are immutable for the duration
     * of the scene's execution.</p>
     *
     * @return the output settings, or null if not yet configured
     */
    public OutputSettings getOutputSettings() {
        return outputSettings;
    }

    /**
     * Sets the output settings. Called by the main entry point after
     * resolving the output directory and name prefix.
     *
     * @param settings the output settings (must not be null)
     */
    public void setOutputSettings(OutputSettings settings) {
        this.outputSettings = settings;
    }

    /**
     * Returns the typed runtime services for interactive scenes, or null
     * if this context is being used for a MANAGED/DIAGNOSTIC scene.
     *
     * <p>This replaces the previous untyped runner storage and exposes a
     * narrowed interactive runtime surface instead.</p>
     *
     * @return the runtime services, or null for non-interactive scenes
     */
    public RuntimeServices getRuntimeServices() {
        return runtimeServices;
    }

    /**
     * Sets the runtime services. Called by
     * {@link InteractiveSceneRunner} before scene init.
     *
     * @param services the runtime services wrapping the active runner
     */
    public void setRuntimeServices(RuntimeServices services) {
        this.runtimeServices = services;
    }

    // ── Screen dimensions — delegate to ViewportState ──

    /**
     * Returns the current screen/viewport width in pixels.
     * Updated automatically when {@code Display.wasResized()} is detected.
     *
     * <p><b>Prefer</b> {@code getViewport().getWidth()} for new code.</p>
     */
    public int getScreenWidth() { return viewport.getWidth(); }

    /**
     * Returns the current screen/viewport height in pixels.
     * Updated automatically when {@code Display.wasResized()} is detected.
     *
     * <p><b>Prefer</b> {@code getViewport().getHeight()} for new code.</p>
     */
    public int getScreenHeight() { return viewport.getHeight(); }

    /**
     * Updates the stored screen dimensions. Called by the InteractiveSceneRunner
     * when the Display is resized.
     *
     * <p><b>Prefer</b> {@code getViewport().update(w, h)} for new code.</p>
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void setScreenDimensions(int width, int height) {
        viewport.update(width, height);
    }

    // ── Output configuration — delegate to OutputSettings ──

    /**
     * Returns the output directory for screenshots and artifacts.
     * This is the scene-specific subdirectory (e.g. {@code harness-output/text-3d/}).
     *
     * <p><b>Prefer</b> {@code getOutputSettings().getOutputDir()} for new code.</p>
     */
    public String getOutputDir() {
        return outputSettings != null ? outputSettings.getOutputDir() : null;
    }

    /**
     * Returns the output name prefix for filenames.
     * For example, "test1" causes screenshots to be named "test1-normal.png".
     *
     * <p><b>Prefer</b> {@code getOutputSettings().getOutputName()} for new code.</p>
     */
    public String getOutputName() {
        return outputSettings != null ? outputSettings.getOutputName() : null;
    }

    // ── Typed scene configuration ──

    /**
     * Returns the typed scene configuration resolved before scene execution.
     * This is the concrete config subclass (e.g. {@link TextSceneConfig},
     * {@link AtlasDumpConfig}) built from defaults → system properties → CLI args
     * in {@code FontDebugHarnessMain}. Scenes should read from this instead of
     * re-parsing raw CLI arguments.
     *
     * @return the pre-resolved scene config, never null after context setup
     */
    public HarnessConfig getSceneConfig() { return sceneConfig; }

    /**
     * Sets the typed scene configuration. Called by {@code FontDebugHarnessMain}
     * after resolving CLI args into the appropriate config subclass.
     *
     * @param config the resolved config (may be {@link TextSceneConfig},
     *               {@link AtlasDumpConfig}, or base {@link HarnessConfig})
     */
    public void setSceneConfig(HarnessConfig config) { this.sceneConfig = config; }

    // ── World settings ──

    /**
     * Returns the immutable world settings for this run, or null if not yet
     * resolved.
     *
     * <p>World settings are resolved once at run startup from
     * {@link WorldConfig} defaults and frozen for the duration of the run.
     * The runner and renderers read sky/floor colors from this object
     * instead of calling {@link WorldConfig#get()} during rendering.</p>
     *
     * @return the resolved world settings, or null before resolution
     */
    public WorldSettings getWorldSettings() { return worldSettings; }

    /**
     * Sets the resolved world settings. Called by the main entry point
     * or runner before scene init.
     *
     * @param settings the resolved world settings (must not be null)
     */
    public void setWorldSettings(WorldSettings settings) { this.worldSettings = settings; }

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

    // ── Artifact service ──

    /**
     * Returns the framework-owned artifact capture service, or null if not
     * in interactive mode.
     *
     * <p>The artifact service centralizes screenshot capture, filename
     * composition, and post-render callback scheduling. Scenes should use
     * this instead of manually composing filenames and calling
     * {@link io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil}
     * directly.</p>
     *
     * @return the artifact service, or null for non-interactive scenes
     */
    public ArtifactService getArtifactService() { return artifactService; }

    /**
     * Sets the artifact service. Called by {@link InteractiveSceneRunner}
     * before scene init.
     *
     * @param artifactService the artifact service instance
     */
    public void setArtifactService(ArtifactService artifactService) {
        this.artifactService = artifactService;
    }

}
