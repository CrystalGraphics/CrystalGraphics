package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.config.RuntimeServices;
import io.github.somehussar.crystalgraphics.harness.config.ViewportState;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.HarnessProjectionUtil;
import io.github.somehussar.crystalgraphics.harness.util.ValidationCubeHelper;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationCaptureStep;
import io.github.somehussar.crystalgraphics.harness.validation.ValidationChoreographer;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Validation scene that captures 3 screenshots demonstrating floor, HUD, and pause
 * rendering correctness. Uses the post-render callback mechanism for accurate
 * screenshot timing — captures happen AFTER the current frame is fully rendered.
 *
 * <p>Screenshots captured:</p>
 * <ol>
 *   <li><b>normal.png</b> — Front view with floor, cube, and HUD visible</li>
 *   <li><b>paused.png</b> — Same view with pause overlay at bottom</li>
 *   <li><b>top-down.png</b> — Camera looking straight down at floor + cube</li>
 * </ol>
 */
public class RenderValidationScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(RenderValidationScene.class.getName());

    private boolean running = true;
    private boolean shutdownOnComplete = true;

    private HarnessContext ctx;

    // Shared cube rendering helper (shader + geometry + draw)
    private ValidationCubeHelper cubeHelper;

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;

        cubeHelper = ValidationCubeHelper.create();
        scheduleScreenshots();

        LOGGER.info("[RenderValidation] Initialized. " + ctx.getTaskScheduler().pendingCount() + " tasks scheduled.");
    }

    private void scheduleScreenshots() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final Camera3D camera = ctx.getCamera3D();
        final RuntimeServices runtime = ctx.getRuntimeServices();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, runtime);

        choreographer.addStep(ValidationCaptureStep.builder("normal", 0.5)
                .cameraPosition(0.0f, 3.0f, 8.0f)
                .cameraOrientation(0.0f, -15.0f)
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("paused", 1.0)
                .cameraPosition(0.0f, 3.0f, 8.0f)
                .cameraOrientation(0.0f, -15.0f)
                .paused()
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("top-down", 1.5)
                .cameraPosition(0.0f, 15.0f, 0.1f)
                .cameraOrientation(0.0f, -89.0f)
                .build());

        choreographer.onShutdown(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[RenderValidation] All screenshots captured. Stopping.");
                running = false;
            }
        });
        choreographer.scheduleAll();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        renderCube();
    }

    private void renderCube() {
        ViewportState vp = ctx.getViewport();
        Matrix4f projection = HarnessProjectionUtil.perspective(
                vp.getWidth(), vp.getHeight());
        Matrix4f view = ctx.getCamera3D().getViewMatrix();

        Matrix4f mvp = new Matrix4f();
        projection.mul(view, mvp);

        float[] mvpArray = new float[16];
        mvp.get(mvpArray);

        FloatBuffer mvpBuf = BufferUtils.createFloatBuffer(16);
        mvpBuf.put(mvpArray).flip();

        cubeHelper.render(mvpBuf);
    }

    @Override
    public void dispose() {
        if (cubeHelper != null) {
            cubeHelper.delete();
        }
        LOGGER.info("[RenderValidation] Cleaned up.");
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return shutdownOnComplete;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return true;
    }

}
