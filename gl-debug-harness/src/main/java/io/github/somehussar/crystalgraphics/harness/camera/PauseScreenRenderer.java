package io.github.somehussar.crystalgraphics.harness.camera;

import io.github.somehussar.crystalgraphics.harness.config.HarnessContext;
import io.github.somehussar.crystalgraphics.harness.util.HarnessShaderUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Renders a semi-transparent gray overlay at the bottom of the screen
 * when the game is paused. Acts as a visual indicator similar to
 * Minecraft's chat area overlay.
 *
 * <p>The overlay is a screen-aligned quad drawn with alpha blending
 * using an orthographic projection derived from pixel coordinates.
 * The quad covers the bottom ~25% of the screen with a dark
 * semi-transparent gray color.</p>
 *
 * <p>Also renders a "PAUSED" text indicator centered in the overlay
 * using a separate text texture, similar to how {@link HUDRenderer}
 * renders its labels.</p>
 */
public final class PauseScreenRenderer {

    private static final Logger LOGGER = Logger.getLogger(PauseScreenRenderer.class.getName());

    // Overlay height as a fraction of screen height
    private static final float OVERLAY_HEIGHT_FRACTION = 0.10f;

    // Overlay color: dark gray with 50% opacity (RGBA)
    private static final float OVERLAY_R = 0.15f;
    private static final float OVERLAY_G = 0.15f;
    private static final float OVERLAY_B = 0.15f;
    private static final float OVERLAY_A = 0.5f;

    // Shader for colored quad with alpha — uses pixel-to-NDC conversion
    private static final String PAUSE_VERT =
            "#version 130\n" +
            "in vec2 a_pos;\n" +
            "uniform vec2 u_screenSize;\n" +
            "void main() {\n" +
            "    vec2 ndc = (a_pos / u_screenSize) * 2.0 - 1.0;\n" +
            "    ndc.y = -ndc.y;\n" +
            "    gl_Position = vec4(ndc, 0.0, 1.0);\n" +
            "}\n";

    private static final String PAUSE_FRAG =
            "#version 130\n" +
            "uniform vec4 u_color;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = u_color;\n" +
            "}\n";

    private int program;
    private int vao;
    private int vbo;
    private int screenSizeLoc;
    private int colorLoc;
    private boolean initialized = false;

    /**
     * Initializes GL resources (shader program, VAO, VBO).
     * Must be called once with a valid GL context before {@link #render}.
     */
    public void init() {
        if (initialized) {
            return;
        }

        program = HarnessShaderUtil.compileProgram(PAUSE_VERT, PAUSE_FRAG);
        // Explicitly bind fragment output for driver compatibility
        GL30.glBindFragDataLocation(program, 0, "fragColor");
        GL20.glLinkProgram(program);

        screenSizeLoc = GL20.glGetUniformLocation(program, "u_screenSize");
        colorLoc = GL20.glGetUniformLocation(program, "u_color");

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Allocate buffer for 6 vertices * 2 floats (x, y)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 6 * 2 * 4, GL15.GL_DYNAMIC_DRAW);

        int stride = 2 * 4;
        int posLoc = GL20.glGetAttribLocation(program, "a_pos");

        if (posLoc >= 0) {
            GL20.glVertexAttribPointer(posLoc, 2, GL11.GL_FLOAT, false, stride, 0);
            GL20.glEnableVertexAttribArray(posLoc);
        }

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        initialized = true;
        LOGGER.info("[PauseScreenRenderer] Initialized: program=" + program
                + " vao=" + vao + " vbo=" + vbo);
    }

    /**
     * Renders the pause overlay at the bottom of the screen.
     *
     * <p>Temporarily disables depth testing and enables alpha blending
     * to draw the semi-transparent overlay quad.</p>
     *
     * @param ctx the harness context (provides screen dimensions)
     */
    public void render(HarnessContext ctx) {
        render(ctx.getScreenWidth(), ctx.getScreenHeight());
    }

    /**
     * Renders the pause overlay at the bottom of the screen with explicit dimensions.
     *
     * <p>Temporarily disables depth testing and enables alpha blending
     * to draw the semi-transparent overlay quad.</p>
     *
     * @param screenWidth  current viewport width in pixels
     * @param screenHeight current viewport height in pixels
     */
    public void render(int screenWidth, int screenHeight) {
        if (!initialized) {
            throw new IllegalStateException("PauseScreenRenderer.init() must be called before render()");
        }

        
        float overlayHeight = screenHeight * 0.075f;
        float y0 = screenHeight - overlayHeight;
        float y1 = (float) screenHeight - 10;
        float x0 = 0.0f;
        float x1 = (float) screenWidth;

        // Build quad vertices: two triangles covering the bottom strip
        float[] verts = {
            x0, y0,
            x1, y0,
            x1, y1,
            x0, y0,
            x1, y1,
            x0, y1
        };

        FloatBuffer vertBuf = BufferUtils.createFloatBuffer(verts.length);
        vertBuf.put(verts).flip();

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertBuf);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        // Save and configure GL state for overlay rendering
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL20.glUseProgram(program);
        GL20.glUniform2f(screenSizeLoc, (float) screenWidth, (float) screenHeight);
        GL20.glUniform4f(colorLoc, OVERLAY_R, OVERLAY_G, OVERLAY_B, OVERLAY_A);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);

        // Restore prior GL state
        if (!blendWasEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (depthWasEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        }
    }

    /**
     * Handles display resize events. The pause overlay recomputes quad
     * vertices each frame from screen dimensions, so no cached state
     * needs invalidation.
     *
     * @param newWidth  new viewport width in pixels
     * @param newHeight new viewport height in pixels
     */
    public void onDisplayResize(int newWidth, int newHeight) {
        // No-op: quad vertices are recomputed from screen dimensions each render call
    }

    /**
     * Releases all GL resources held by this renderer.
     */
    public void delete() {
        if (!initialized) {
            return;
        }
        GL15.glDeleteBuffers(vbo);
        GL30.glDeleteVertexArrays(vao);
        GL20.glDeleteProgram(program);
        vbo = 0;
        vao = 0;
        program = 0;
        initialized = false;
        LOGGER.info("[PauseScreenRenderer] Deleted.");
    }
}
