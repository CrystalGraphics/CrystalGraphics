package io.github.somehussar.crystalgraphics.harness.scene;

import io.github.somehussar.crystalgraphics.harness.HarnessScene;
import io.github.somehussar.crystalgraphics.harness.InteractiveHarnessScene;
import io.github.somehussar.crystalgraphics.harness.camera.Camera3D;
import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.debug.HarnessDebugTools;
import io.github.somehussar.crystalgraphics.harness.scheduler.TaskScheduler;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

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
public class Camera3DValidationScene implements HarnessScene, InteractiveHarnessScene {

    private static final Logger LOGGER = Logger.getLogger(Camera3DValidationScene.class.getName());

    private static final float FOV_DEGREES = 60.0f;
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE = 1000.0f;

    private boolean running = true;
    private boolean shutdownOnComplete = false;

    private HarnessContext ctx;

    // Cube rendering resources
    private int cubeProgram;
    private int cubeVao;
    private int cubeVbo;
    private int cubeMvpLocation;

    // Shader for 3D colored geometry with MVP uniform
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

    // Cube vertex data: 36 vertices (6 faces × 2 triangles × 3 verts)
    // Each vertex: x, y, z, r, g, b
    // Cube sits on the floor (Y=0 to Y=2), centered at origin on XZ
    private static final float[] CUBE_VERTICES = buildCubeVertices();

    // ── HarnessScene (single-shot mode, for backward compat) ──

    @Override
    public void run(HarnessContext ctx) throws Exception {
        LOGGER.info("[Camera3DValidation] Single-shot mode not supported; use --mode=camera-3d-validation with INTERACTIVE lifecycle");
        throw new UnsupportedOperationException(
                "Camera3DValidationScene requires INTERACTIVE lifecycle mode (--mode=camera-3d-validation)");
    }

    // ── InteractiveHarnessScene ──

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        Camera3D camera = ctx.getCamera3D();
        HarnessDebugTools debugTools = new HarnessDebugTools(camera, ctx.getOutputDir(),
                ctx.getOutputName(), ctx.getScreenWidth(), ctx.getScreenHeight());

        initCubeGeometry();

        // Schedule screenshot captures at specific timestamps
        // Each task positions the camera then captures a frame
        scheduleValidationScreenshots();

        LOGGER.info("[Camera3DValidation] Initialized. " + ctx.getTaskScheduler().pendingCount() + " screenshots scheduled.");
    }

    private void scheduleValidationScreenshots() {
        final Camera3D camera = ctx.getCamera3D();
        final TaskScheduler scheduler = ctx.getTaskScheduler();
        final HarnessDebugTools debugTools = new HarnessDebugTools(camera, ctx.getOutputDir(),
                ctx.getOutputName(), ctx.getScreenWidth(), ctx.getScreenHeight());
        final String outputName = ctx.getOutputName();
        // Screenshot 1: Front view at t=0.5s
        scheduler.schedule(0.5, "front-view", new Runnable() {
            @Override
            public void run() {
                debugTools.moveCamera(0.0f, 2.0f, 8.0f);
                camera.setYaw(0.0f);
                camera.setPitch(-10.0f);
            }
        });
        scheduler.schedule(0.6, "front-view-capture", new Runnable() {
            @Override
            public void run() {
                debugTools.screenshot(outputName + "-front-view.png");
                LOGGER.info("[Camera3DValidation] Captured " + outputName + "-front-view.png");
            }
        });

        // Screenshot 2: Side view at t=1.0s
        // yaw=90° → look direction (-1, 0, 0), camera at +X looking toward origin
        scheduler.schedule(1.0, "side-view", new Runnable() {
            @Override
            public void run() {
                debugTools.moveCamera(8.0f, 2.0f, 0.0f);
                camera.setYaw(90.0f);
                camera.setPitch(-10.0f);
            }
        });
        scheduler.schedule(1.1, "side-view-capture", new Runnable() {
            @Override
            public void run() {
                debugTools.screenshot(outputName + "-side-view.png");
                LOGGER.info("[Camera3DValidation] Captured " + outputName + "-side-view.png");
            }
        });

        // Screenshot 3: Top-down view at t=1.5s
        scheduler.schedule(1.5, "top-down-view", new Runnable() {
            @Override
            public void run() {
                debugTools.moveCamera(0.0f, 12.0f, 0.1f);
                camera.setYaw(0.0f);
                camera.setPitch(-89.0f);
            }
        });
        scheduler.schedule(1.6, "top-down-view-capture", new Runnable() {
            @Override
            public void run() {
                debugTools.screenshot(outputName + "-top-down-view.png");
                LOGGER.info("[Camera3DValidation] Captured " + outputName + "-top-down-view.png");
            }
        });

        // Screenshot 4: Diagonal view at t=2.0s
        // Camera at (+X, +Z) corner, yaw=45° → looks toward (-X, -Z) i.e. toward origin
        scheduler.schedule(2.0, "diagonal-view", new Runnable() {
            @Override
            public void run() {
                debugTools.moveCamera(5.0f, 3.5f, 5.0f);
                camera.setYaw(45.0f);
                camera.setPitch(-20.0f);
            }
        });
        scheduler.schedule(2.1, "diagonal-view-capture", new Runnable() {
            @Override
            public void run() {
                debugTools.screenshot(outputName + "-diagonal-view.png");
                LOGGER.info("[Camera3DValidation] Captured " + outputName + "-diagonal-view.png");
            }
        });

        // Stop the scene after all screenshots at t=2.5s
        scheduler.schedule(2.5, "shutdown", new Runnable() {
            @Override
            public void run() {
                LOGGER.info("[Camera3DValidation] All screenshots captured. Stopping.");
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

    // ── Cube geometry setup ──

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
     * Builds a unit cube (2×2×2) sitting on the floor plane (Y=0 to Y=2),
     * centered at the origin on X and Z axes.
     *
     * <p>Each face has a distinct color for easy identification from different angles:
     * front=red, back=cyan, left=green, right=magenta, top=blue, bottom=yellow.</p>
     */
    private static float[] buildCubeVertices() {
        // Half-extents: cube from (-1, 0, -1) to (1, 2, 1)
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
