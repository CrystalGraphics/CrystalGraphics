package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.FrameInfo;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneLifecycle;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.capture.ArtifactService;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
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
 * Validation scene that renders a 3D reference object (colored cube) on the floor plane
 * and automatically captures screenshots from 4 distinct angles using the TaskScheduler.
 *
 * <p>This scene demonstrates all major features:</p>
 * <ul>
 *   <li>Camera3D with programmatic positioning</li>
 *   <li>Floor plane visibility at Y=0</li>
 *   <li>TaskScheduler firing at specific timestamps</li>
 *   <li>LLM debug tools (moveCamera, rotateCamera, screenshot)</li>
 *   <li>Scene shutdown control</li>
 * </ul>
 *
 * <p>Screenshots captured:</p>
 * <ol>
 *   <li><b>front-view.png</b>: Front view of the cube</li>
 *   <li><b>side-view.png</b>: Side view (camera rotated 90°)</li>
 *   <li><b>top-down-view.png</b>: Top-down view looking straight down</li>
 *   <li><b>diagonal-view.png</b>: Diagonal view (45° angle)</li>
 * </ol>
 */
public class Camera3DValidationScene implements InteractiveSceneLifecycle {

    private static final Logger LOGGER = Logger.getLogger(Camera3DValidationScene.class.getName());

    private boolean running = true;
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;

    // Shared cube rendering helper (shader + geometry + draw)
    private ValidationCubeHelper cubeHelper;

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;

        cubeHelper = ValidationCubeHelper.create();

        // Schedule screenshot captures at specific timestamps
        // Each task positions the camera then captures a frame
        scheduleValidationScreenshots();

        LOGGER.info("[Camera3DValidation] Initialized. " + ctx.getTaskScheduler().pendingCount() + " screenshots scheduled.");
    }

    private void scheduleValidationScreenshots() {
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final Camera3D camera = ctx.getCamera3D();
        final ArtifactService artifacts = ctx.getArtifactService();

        ValidationChoreographer choreographer = new ValidationChoreographer(
                camera, scheduler, artifacts, ctx.getRuntimeServices());

        choreographer.addStep(ValidationCaptureStep.builder("front-view", 0.5)
                .cameraPosition(0.0f, 2.0f, 8.0f)
                .cameraOrientation(0.0f, -10.0f)
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("side-view", 1.0)
                .cameraPosition(8.0f, 2.0f, 0.0f)
                .cameraOrientation(90.0f, -10.0f)
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("top-down-view", 1.5)
                .cameraPosition(0.0f, 12.0f, 0.1f)
                .cameraOrientation(0.0f, -89.0f)
                .build());

        choreographer.addStep(ValidationCaptureStep.builder("diagonal-view", 2.0)
                .cameraPosition(5.0f, 3.5f, 5.0f)
                .cameraOrientation(45.0f, -20.0f)
                .build());

        choreographer.onShutdown(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[Camera3DValidation] All screenshots captured. Stopping.");
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
        LOGGER.info("[Camera3DValidation] Cleaned up.");
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
