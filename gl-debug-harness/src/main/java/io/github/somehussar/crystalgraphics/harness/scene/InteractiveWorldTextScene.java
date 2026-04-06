package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.*;
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
    private static final float MOTION_CAM_X = 1.36f;
    private static final float MOTION_CAM_Y = 0.70f;
    private static final float MOTION_CAM_Z = -4.71f;
    private static final float MOTION_PITCH = -12.0f;
    private static final float[][] INVESTIGATION_CAPTURES = new float[][] {
            {-1.58f, 0.93f, -4.31f, 356.0f, -4.0f},
            {-1.90f, 0.22f, -4.37f, 337.0f, -4.0f},
            {3.83f, 0.84f, -4.40f, 358.0f, -7.0f},
            {1.02f, 0.22f, -4.31f, 1.0f, -1.0f}
    };
    private static final double INVESTIGATION_PREWARM_SECONDS = 3.0;
    private static final double INVESTIGATION_CAPTURE_SPACING_SECONDS = 0.5;
    private static final String[] INVESTIGATION_CAPTURE_NAMES = new String[] {
            "jp-intersection-gap-a",
            "ar-baseline-gap-a",
            "jp-punct-gap-bad",
            "ar-punct-gap-good-control"
    };

    // ── Interactive mode state ──
    private boolean running = true;
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;
    private WorldTextRenderHelper helper;
    private WorldTextRenderHelper arHelper;
    private WorldTextRenderHelper jpHelper;
    

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
        LOGGER.info("[Harness] World text scene (interactive): size=" + fontSizePx + "px"
                + ", mtsdf=" + config.isMtsdf());

        // Initialize the shared render helper (validates GL caps, loads font, builds layouts)
        helper = new WorldTextRenderHelper(fontPath, fontSizePx, text, layoutWidth, layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        helper.init();

        jpHelper = new WorldTextRenderHelper(HarnessConfig.JAPANESE_FONT, fontSizePx,
                "さあ 剽悍な双眸を エーカム そうさ 先頭に", layoutWidth, layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        jpHelper.init();

        arHelper = new WorldTextRenderHelper(HarnessConfig.ARABIC_FONT, fontSizePx, "بيانات الاستفسار", layoutWidth,
                layoutHeight,
                config.getAtlasSize(), config.isMtsdf());
        arHelper.init();

        camera.moveCamera(MOTION_CAM_X, MOTION_CAM_Y, MOTION_CAM_Z);
        camera.setYaw(337.0f);
        camera.setPitch(MOTION_PITCH);

        // Schedule automated screenshots for validation if runtime services are available
        if (ctx.getRuntimeServices() != null) {
            scheduleValidationScreenshots();
        }

        LOGGER.info("[Harness] World text scene (interactive) initialized.");
    }

    /**
     * Schedules automated screenshots through the shared validation choreography.
     */
    private void scheduleValidationScreenshots() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final Camera3D camera = ctx.getCamera3D();
        final RuntimeServices runtime = ctx.getRuntimeServices();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, runtime);

        double captureTime = INVESTIGATION_PREWARM_SECONDS;
        for (int i = 0; i < INVESTIGATION_CAPTURES.length; i++) {
            float[] capture = INVESTIGATION_CAPTURES[i];
            String name = INVESTIGATION_CAPTURE_NAMES[i];
            choreographer.addStep(ValidationCaptureStep.builder(name, captureTime)
                    .cameraPosition(capture[0], capture[1], capture[2])
                    .cameraOrientation(capture[3], capture[4])
                    .build());
            captureTime += INVESTIGATION_CAPTURE_SPACING_SECONDS;
        }

        choreographer.onShutdown(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[InteractiveWorldTextScene] All screenshots captured.");
//                running = false;
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

        PoseStack poseStack = new PoseStack();
        Matrix4f modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        float worldScale = 0.01f;
        float textWorldWidth = arHelper.getWorldLayout().getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.75f, -5f);
        modelView.scale(worldScale, -worldScale, worldScale);
        jpHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);


        poseStack = new PoseStack();
        modelView = poseStack.last().pose();
        modelView.set(viewMatrix);
        worldScale = 0.01f;
        textWorldWidth = arHelper.getWorldLayout().getTotalWidth() * worldScale;
        modelView.translate(-textWorldWidth * 0.5f, 0.15f, -5f);
        modelView.scale(worldScale, -worldScale, worldScale);
        arHelper.renderWorld(screenWidth, screenHeight, frame.getFrameNumber(), poseStack);

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
