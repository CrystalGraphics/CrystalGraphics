package io.github.somehussar.crystalgraphics.harness.camera;

import io.github.somehussar.crystalgraphics.harness.config.WorldConfig;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Renders a gray grid-like floor plane at Y=0 using immediate-mode style
 * vertex data uploaded to a VAO/VBO.
 *
 * <p>The floor is a large quad centered at the origin, rendered as two triangles.
 * Floor color is sourced from {@link WorldConfig} singleton, defaulting to gray
 * (0.5, 0.5, 0.5).</p>
 *
 * <p>The floor plane is drawn with depth testing enabled. Scenes using this
 * renderer should ensure GL_DEPTH_TEST is active.</p>
 */
public class FloorRenderer {

    private static final Logger LOGGER = Logger.getLogger(FloorRenderer.class.getName());

    // Shader sources for floor rendering with uniform MVP matrix and per-vertex color
    private static final String FLOOR_VERT =
            "#version 130\n" +
            "uniform mat4 u_mvp;\n" +
            "in vec3 a_pos;\n" +
            "in vec3 a_color;\n" +
            "out vec3 v_color;\n" +
            "void main() {\n" +
            "    gl_Position = u_mvp * vec4(a_pos, 1.0);\n" +
            "    v_color = a_color;\n" +
            "}\n";

    private static final String FLOOR_FRAG =
            "#version 130\n" +
            "in vec3 v_color;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = vec4(v_color, 1.0);\n" +
            "}\n";

    // Two triangles forming a quad on the XZ plane at Y=0
    // Format: x, y, z, r, g, b per vertex
    // Vertex data is built at init() time from WorldConfig

    private int program;
    private int vao;
    private int vbo;
    private int mvpLocation;
    private boolean initialized = false;

    /**
     * Initializes GL resources (shader program, VAO, VBO).
     * Must be called once with a valid GL context before {@link #render}.
     *
     * <p>Floor color and extent are read from {@link WorldConfig} at init time.</p>
     */
    public void init() {
        if (initialized) {
            return;
        }

        WorldConfig worldConfig = WorldConfig.get();
        float halfSize = worldConfig.getFloorHalfSize();
        float r = worldConfig.getFloorR();
        float g = worldConfig.getFloorG();
        float b = worldConfig.getFloorB();

        float[] floorVertices = {
            -halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f,  halfSize, r, g, b,

            -halfSize, 0.0f, -halfSize, r, g, b,
             halfSize, 0.0f,  halfSize, r, g, b,
            -halfSize, 0.0f,  halfSize, r, g, b
        };

        program = HarnessShaderUtil.compileProgram(FLOOR_VERT, FLOOR_FRAG);

        // Explicitly bind the fragment output to color attachment 0.
        // While GLSL 130 implicitly maps a single 'out' to location 0 on most
        // drivers, being explicit prevents issues on Intel/Mesa drivers that
        // may not auto-assign the output.
        GL30.glBindFragDataLocation(program, 0, "fragColor");
        // Re-link after binding frag data location
        GL20.glLinkProgram(program);

        mvpLocation = GL20.glGetUniformLocation(program, "u_mvp");

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(floorVertices.length);
        buf.put(floorVertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        int stride = 6 * 4; // 6 floats * 4 bytes
        int posLoc = GL20.glGetAttribLocation(program, "a_pos");
        int colorLoc = GL20.glGetAttribLocation(program, "a_color");

        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 3, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        } else {
            LOGGER.warning("[FloorRenderer] a_pos attribute not found in shader!");
        }
        if (colorLoc >= 0) {
            GL20.glVertexAttribPointer(colorLoc, 3, GL11.GL_FLOAT, false, stride, 12);
            GL20.glEnableVertexAttribArray(colorLoc);
        } else {
            LOGGER.warning("[FloorRenderer] a_color attribute not found in shader!");
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Check for any GL errors during initialization
        int glError = GL11.glGetError();
        if (glError != GL11.GL_NO_ERROR) {
            LOGGER.warning("[FloorRenderer] GL error during init: 0x" + Integer.toHexString(glError));
        }

        initialized = true;
        LOGGER.info("[FloorRenderer] Initialized: program=" + program + " vao=" + vao
                + " vbo=" + vbo + " posLoc=" + posLoc + " colorLoc=" + colorLoc
                + " mvpLoc=" + mvpLocation);
    }

    /**
     * Renders the floor plane using the given model-view-projection matrix.
     *
     * <p>The MVP matrix should be projection * view (no model transform needed,
     * as the floor is at the world origin).</p>
     *
     * @param mvpMatrix the combined model-view-projection matrix stored in a float[16] column-major
     */
    public void render(float[] mvpMatrix) {
        if (!initialized) {
            throw new IllegalStateException("FloorRenderer.init() must be called before render()");
        }

        // Save GL state that we modify so we don't corrupt other renderers
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);

        // Floor must render with depth testing enabled and blending disabled
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glDisable(GL11.GL_BLEND);

        FloatBuffer mvpBuf = BufferUtils.createFloatBuffer(16);
        mvpBuf.put(mvpMatrix).flip();

        GL20.glUseProgram(program);
        GL20.glUniformMatrix4(mvpLocation, false, mvpBuf);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);

        // Restore prior GL state
        if (!depthWasEnabled) {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (blendWasEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        }
    }

    /**
     * Handles display resize events. The floor renderer uses the MVP matrix
     * passed to render(), so no cached state needs updating on resize.
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onDisplayResize(int newWidth, int newHeight) {
        // No-op: floor uses MVP computed fresh each frame from current viewport aspect
    }

    /**
     * Releases all GL resources held by this renderer.
     */
    public void delete() {
        if (!initialized) {
            return;
        }
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteProgram(program);
        vbo = 0;
        vao = 0;
        program = 0;
        initialized = false;
        LOGGER.info("[FloorRenderer] Deleted.");
    }
}
