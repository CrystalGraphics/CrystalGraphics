package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.RuntimeServices;
import io.github.somehussar.crystalgraphics.harness.config.TextSceneConfig;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.GlStateResetHelper;
import io.github.somehussar.crystalgraphics.harness.util.HarnessFontUtil;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationCaptureStep;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationChoreographer;

import org.joml.Matrix4f;

import java.util.logging.Logger;

/**
 * Interactive 3D world text scene for the {@code world-text-3d} mode.
 *
 * <p>Provides a continuous render loop with first-person camera controls
 * for inspecting world-space text from any angle. The scene includes:
 * floor plane, HUD overlay, pause support, and scheduled screenshot
 * capture for automated validation.</p>
 *
 * <h3>Output</h3>
 * <ul>
 *   <li>{@code harness-output/world-text-3d/{name}-normal.png}</li>
 *   <li>{@code harness-output/world-text-3d/{name}-paused.png}</li>
 *   <li>{@code harness-output/world-text-3d/{name}-topdown.png}</li>
 * </ul>
 *
 * <h3>Validation sequence</h3>
 * The scene schedules three automated screenshots:
 * <ol>
 *   <li><b>normal</b> (t=1.0s) — Front view: floor, text, HUD visible</li>
 *   <li><b>paused</b> (t=1.5s) — Same view with pause overlay at bottom</li>
 *   <li><b>topdown</b> (t=2.0s) — Camera looking straight down at floor + text</li>
 * </ol>
 *
 * <p>All shared rendering logic (font loading, layout construction, GL cap
 * validation, world-space text drawing) is delegated to
 * {@link WorldTextRenderHelper}. This class only owns the interactive
 * lifecycle, camera setup, and screenshot scheduling.</p>
 *
 * @see WorldTextRenderHelper
 * @see ManagedWorldTextScene
 */
public class InteractiveWorldTextScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(InteractiveWorldTextScene.class.getName());

    // ── Interactive mode state ──
    private boolean running = true;
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;
    private WorldTextRenderHelper helper;

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        Camera3D camera = ctx.getCamera3D();

        // Typed config is resolved before execution and available via context.
        // For interactive scenes, the config is set on ctx before init() is called.
        TextSceneConfig config = (TextSceneConfig) ctx.getSceneConfig();
        String fontPath = HarnessFontUtil.resolveFontPath(config.getFontPath());
        int fontSizePx = config.getFontSizePx();
        String text = config.getText();
        int layoutWidth = config.getWidth();
        int layoutHeight = config.getHeight();

        LOGGER.info("[Harness] World text scene (interactive): font=" + fontPath);
        LOGGER.info("[Harness] World text scene (interactive): size=" + fontSizePx + "px");

        // Initialize the shared render helper (validates GL caps, loads font, builds layouts)
        helper = new WorldTextRenderHelper(fontPath, fontSizePx, text, layoutWidth, layoutHeight);
        helper.init();

        // Position camera at feet level looking slightly down at the floor
        camera.moveCamera(0.0f, 1.5f, 10.0f);
        camera.setPitch(-15.0f);

        // Schedule automated screenshots for validation if runtime services are available
        if (ctx.getRuntimeServices() != null) {
            scheduleValidationScreenshots();
        }

        LOGGER.info("[Harness] World text scene (interactive) initialized.");
    }

    /**
     * Schedules three automated screenshots for visual validation:
     * <ol>
     *   <li><b>{name}-normal.png</b> — Front view: floor, text, HUD visible</li>
     *   <li><b>{name}-paused.png</b> — Same view with pause overlay at bottom</li>
     *   <li><b>{name}-topdown.png</b> — Camera looking straight down at floor + text</li>
     * </ol>
     *
     * <p>Uses shared choreography primitives and post-render callbacks via
     * RuntimeServices to capture screenshots AFTER the full frame (scene + floor +
     * HUD + pause) is rendered.</p>
     */
    private void scheduleValidationScreenshots() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final Camera3D camera = ctx.getCamera3D();
        final RuntimeServices runtime = ctx.getRuntimeServices();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, runtime);

        choreographer.addStep(ValidationCaptureStep.builder("normal", 1.0)
                .cameraPosition(0.0f, 1.5f, 10.0f)
                .cameraOrientation(0.0f, -15.0f)
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("paused", 1.5)
                .cameraPosition(0.0f, 1.5f, 10.0f)
                .cameraOrientation(0.0f, -15.0f)
                .paused()
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("topdown", 2.0)
                .cameraPosition(0.0f, 15.0f, 0.1f)
                .cameraOrientation(0.0f, -89.0f)
                .build());

        choreographer.onShutdown(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[InteractiveWorldTextScene] All screenshots captured.");
            }
        });
        choreographer.scheduleAll();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        // Build view matrix from camera
        Matrix4f viewMatrix = this.ctx.getCamera3D().getViewMatrix();

        ViewportState vp = this.ctx.getViewport();
        int screenWidth = vp.getWidth();
        int screenHeight = vp.getHeight();

        // Delegate all world-text rendering to the shared helper.
        // The helper handles perspective projection, model-view setup,
        // text positioning, and drawWorld() with correct winding order.
        helper.renderWorld(viewMatrix, screenWidth, screenHeight, frame.getFrameNumber());

        // ── GL state cleanup after world text rendering ──
        // drawWorld() internally saves/restores state via CgStateBoundary, but in the
        // standalone harness (no coremod), the GLStateMirror is in UNKNOWN state which
        // can cause incomplete restoration. Use the shared reset helper to guarantee
        // floor, HUD, and pause overlay render correctly in subsequent passes.
        GlStateResetHelper.resetAfterScene();
    }

    @Override
    public void dispose() {
        if (helper != null) {
            helper.dispose();
        }
        LOGGER.info("[Harness] World text scene (interactive) cleaned up.");
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return shutdownOnComplete;
    }

    /**
     * Configures whether the program should shut down when this scene completes.
     *
     * @param shutdown true to exit on completion (default), false to continue
     */
    public void setShutdownOnComplete(boolean shutdown) {
        this.shutdownOnComplete = shutdown;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Signals this scene to stop its render loop.
     */
    public void requestStop() {
        running = false;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }
}
