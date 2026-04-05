package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.HarnessScene;
import io.github.somehussar.crystalgraphics.harness.InteractiveHarnessScene;
import io.github.somehussar.crystalgraphics.harness.InteractiveSceneRunner;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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
public class RenderValidationScene implements HarnessScene, InteractiveHarnessScene {

    private static final Logger LOGGER = Logger.getLogger(RenderValidationScene.class.getName());

    private static final float FOV_DEGREES = 60.0f;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;

    private boolean running = true;
    private boolean shutdownOnComplete = true;

    private HarnessContext ctx;

    private int cubeProgram;
    private int cubeVao;
    private int cubeVbo;
    private int cubeMvpLocation;

    private static final String CUBE_VERT =
            "#version 130\n" +
            "uniform mat4 u_mvp;\n" +
            "in vec3 a_pos;\n" +
            "in vec3 a_color;\n" +
            "out vec3 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = u_mvp * vec4(a_pos, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";

    private static final String CUBE_FRAG =
            "#version 130\n" +
            "in vec3 v_color;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(v_color, 1.0);\n" +
            "}\n";

    private static final float[] CUBE_VERTICES = buildCubeVertices();

    @Override
    public void run(HarnessContext ctx) throws Exception {
        throw new UnsupportedOperationException(
                "RenderValidationScene requires INTERACTIVE lifecycle mode");
    }

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;

        initCubeGeometry();
        scheduleScreenshots();

        LOGGER.info("[RenderValidation] Initialized. " + ctx.getTaskScheduler().pendingCount() + " tasks scheduled.");
    }

    private void scheduleScreenshots() {
        final Camera3D camera = ctx.getCamera3D();
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final InteractiveSceneRunner runner = (InteractiveSceneRunner) ctx.getRunner();
        final String outputDir = ctx.getOutputDir();
        final String outputName = ctx.getOutputName();

        // Screenshot 1: normal — front view at t=0.5s
        // Position camera, then capture on the NEXT frame via post-render callback
        scheduler.schedule(0.5, "normal-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 3.0f, 8.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-15.0f);
                LOGGER.info("[RenderValidation] Camera positioned for normal screenshot");
            }
        });
        scheduler.schedule(0.6, "normal-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-normal.png";
                // Use post-render callback to capture AFTER this frame renders
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[RenderValidation] Captured " + filename);
                    }
                });
            }
        });

        // Screenshot 2: paused — same view, paused state at t=1.0s
        scheduler.schedule(1.0, "paused-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 3.0f, 8.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-15.0f);
                runner.setPaused(true);
                LOGGER.info("[RenderValidation] Camera positioned + paused for paused screenshot");
            }
        });
        scheduler.schedule(1.1, "paused-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-paused.png";
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[RenderValidation] Captured " + filename);
                        runner.setPaused(false);
                    }
                });
            }
        });

        // Screenshot 3: top-down — looking straight down at t=1.5s
        scheduler.schedule(1.5, "topdown-setup", new Runnable() {
            @Override
            public void run() {
                camera.moveCamera(0.0f, 15.0f, 0.1f);
                camera.setYaw(0.0f);
                camera.setPitch(-89.0f);
                LOGGER.info("[RenderValidation] Camera positioned for top-down screenshot");
            }
        });
        scheduler.schedule(1.6, "topdown-capture", new Runnable() {
            @Override
            public void run() {
                final String filename = outputName + "-top-down.png";
                runner.setPostRenderCallback(new Runnable() {
                    @Override
                    public void run() {
                        ScreenshotUtil.captureBackbuffer(
                                runner.getCurrentWidth(), runner.getCurrentHeight(),
                                outputDir, filename);
                        LOGGER.info("[RenderValidation] Captured " + filename);
                    }
                });
            }
        });

        // Shutdown at t=2.0s
        scheduler.schedule(2.0, "shutdown", new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[RenderValidation] All screenshots captured. Stopping.");
                running = false;
            }
        });
    }

    @Override
    public void renderFrame(float deltaTime, double elapsedTime, long frameNumber) {
        renderCube();
    }

    private void renderCube() {
        float aspect = (float) ctx.getScreenWidth() / (float) ctx.getScreenHeight();
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(FOV_DEGREES), aspect, NEAR_PLANE, FAR_PLANE);
        Matrix4f view = ctx.getCamera3D().getViewMatrix();

        Matrix4f mvp = new Matrix4f();
        projection.mul(view, mvp);

        float[] mvpArray = new float[16];
        mvp.get(mvpArray);

        FloatBuffer mvpBuf = BufferUtils.createFloatBuffer(16);
        mvpBuf.put(mvpArray).flip();

        GL20.glUseProgram(cubeProgram);
        GL20.glUniformMatrix4(cubeMvpLocation, false, mvpBuf);

        GL30.glBindVertexArray(cubeVao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    @Override
    public void cleanup() {
        if (cubeVbo != 0) {
            GL15.glDeleteBuffers(cubeVbo);
        }
        if (cubeVao != 0) {
            GL30.glDeleteVertexArrays(cubeVao);
        }
        if (cubeProgram != 0) {
            GL20.glDeleteProgram(cubeProgram);
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

    private void initCubeGeometry() {
        cubeProgram = HarnessShaderUtil.compileProgram(CUBE_VERT, CUBE_FRAG);
        cubeMvpLocation = GL20.glGetUniformLocation(cubeProgram, "u_mvp");

        cubeVao = GL30.glGenVertexArrays();
        cubeVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(cubeVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cubeVbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(CUBE_VERTICES.length);
        buf.put(CUBE_VERTICES).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 6 * 4;
        int posLoc = GL20.glGetAttribLocation(cubeProgram, "a_pos");
        int colorLoc = GL20.glGetAttribLocation(cubeProgram, "a_color");

        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }
        if (colorLoc >= 0) {
            GL20.glVertexAttribPointer(colorLoc, 3, GL11.GL_FLOAT, false, stride, 12);
            GL20.glEnableVertexAttribArray(colorLoc);
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Builds a cube sitting on the floor plane (Y=0 to Y=2),
     * centered at the origin on X and Z axes. Each face has a
     * distinct color for easy identification.
     */
    private static float[] buildCubeVertices() {
        float x0 = -1.0f, x1 = 1.0f;
        float y0 = 0.0f, y1 = 2.0f;
        float z0 = -1.0f, z1 = 1.0f;

        return new float[] {
            // Front face (z=z1) - RED
            x0, y0, z1,  1.0f, 0.2f, 0.2f,
            x1, y0, z1,  1.0f, 0.2f, 0.2f,
            x1, y1, z1,  1.0f, 0.2f, 0.2f,
            x0, y0, z1,  1.0f, 0.2f, 0.2f,
            x1, y1, z1,  1.0f, 0.2f, 0.2f,
            x0, y1, z1,  1.0f, 0.2f, 0.2f,

            // Back face (z=z0) - CYAN
            x1, y0, z0,  0.2f, 1.0f, 1.0f,
            x0, y0, z0,  0.2f, 1.0f, 1.0f,
            x0, y1, z0,  0.2f, 1.0f, 1.0f,
            x1, y0, z0,  0.2f, 1.0f, 1.0f,
            x0, y1, z0,  0.2f, 1.0f, 1.0f,
            x1, y1, z0,  0.2f, 1.0f, 1.0f,

            // Left face (x=x0) - GREEN
            x0, y0, z0,  0.2f, 1.0f, 0.2f,
            x0, y0, z1,  0.2f, 1.0f, 0.2f,
            x0, y1, z1,  0.2f, 1.0f, 0.2f,
            x0, y0, z0,  0.2f, 1.0f, 0.2f,
            x0, y1, z1,  0.2f, 1.0f, 0.2f,
            x0, y1, z0,  0.2f, 1.0f, 0.2f,

            // Right face (x=x1) - MAGENTA
            x1, y0, z1,  1.0f, 0.2f, 1.0f,
            x1, y0, z0,  1.0f, 0.2f, 1.0f,
            x1, y1, z0,  1.0f, 0.2f, 1.0f,
            x1, y0, z1,  1.0f, 0.2f, 1.0f,
            x1, y1, z0,  1.0f, 0.2f, 1.0f,
            x1, y1, z1,  1.0f, 0.2f, 1.0f,

            // Top face (y=y1) - BLUE
            x0, y1, z1,  0.2f, 0.2f, 1.0f,
            x1, y1, z1,  0.2f, 0.2f, 1.0f,
            x1, y1, z0,  0.2f, 0.2f, 1.0f,
            x0, y1, z1,  0.2f, 0.2f, 1.0f,
            x1, y1, z0,  0.2f, 0.2f, 1.0f,
            x0, y1, z0,  0.2f, 0.2f, 1.0f,

            // Bottom face (y=y0) - YELLOW
            x0, y0, z0,  1.0f, 1.0f, 0.2f,
            x1, y0, z0,  1.0f, 1.0f, 0.2f,
            x1, y0, z1,  1.0f, 1.0f, 0.2f,
            x0, y0, z0,  1.0f, 1.0f, 0.2f,
            x1, y0, z1,  1.0f, 1.0f, 0.2f,
            x0, y0, z1,  1.0f, 1.0f, 0.2f,
        };
    }
}
