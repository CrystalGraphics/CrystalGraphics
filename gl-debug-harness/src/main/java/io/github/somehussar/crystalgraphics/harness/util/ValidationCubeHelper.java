package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

/**
 * Shared colored-cube geometry and shader setup for validation scenes.
 *
 * <p>Both {@code Camera3DValidationScene} and {@code RenderValidationScene} render
 * an identical colored cube to verify camera, floor, and overlay correctness.
 * This helper centralizes the cube vertex data, shader source, and GL resource
 * lifecycle so that changes to the reference geometry propagate consistently.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   // In scene init:
 *   ValidationCubeHelper cube = ValidationCubeHelper.create();
 *
 *   // In scene renderFrame:
 *   cube.render(mvpMatrix);
 *
 *   // In scene cleanup:
 *   cube.delete();
 * </pre>
 */
public final class ValidationCubeHelper {

    /** Number of vertices in the cube (6 faces × 2 triangles × 3 vertices). */
    public static final int CUBE_VERTEX_COUNT = 36;

    // ── Shader source for 3D colored geometry with MVP uniform ──

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

    private final int program;
    private final int vao;
    private final int vbo;
    private final int mvpLocation;

    private ValidationCubeHelper(int program, int vao, int vbo, int mvpLocation) {
        this.program = program;
        this.vao = vao;
        this.vbo = vbo;
        this.mvpLocation = mvpLocation;
    }

    /**
     * Creates the cube GL resources: compiles the shader, uploads vertex data,
     * and configures the VAO with position and color attributes.
     *
     * @return a ready-to-render cube helper
     */
    public static ValidationCubeHelper create() {
        int program = HarnessShaderUtil.compileProgram(CUBE_VERT, CUBE_FRAG);
        int mvpLocation = GL20.glGetUniformLocation(program, "u_mvp");

        int vao = GL30.glGenVertexArrays();
        int vbo = GL15.glGenBuffers();

        float[] vertices = buildCubeVertices();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        FloatBuffer buf = BufferUtils.createFloatBuffer(vertices.length);
        buf.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);

        // Stride: 6 floats per vertex (3 pos + 3 color) × 4 bytes
        int stride = 6 * 4;
        int posLoc = GL20.glGetAttribLocation(program, "a_pos");
        int colorLoc = GL20.glGetAttribLocation(program, "a_color");

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

        return new ValidationCubeHelper(program, vao, vbo, mvpLocation);
    }

    /**
     * Renders the cube using the given MVP matrix.
     *
     * <p>Binds the cube shader and VAO, uploads the MVP uniform, draws 36
     * vertices as triangles, then unbinds. The caller is responsible for
     * setting up the projection and view matrices and combining them into
     * the MVP before calling this method.</p>
     *
     * @param mvpBuf a flipped FloatBuffer containing the 4×4 column-major MVP matrix
     */
    public void render(FloatBuffer mvpBuf) {
        GL20.glUseProgram(program);
        GL20.glUniformMatrix4(mvpLocation, false, mvpBuf);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, CUBE_VERTEX_COUNT);
        GL30.glBindVertexArray(0);

        GL20.glUseProgram(0);
    }

    /**
     * Deletes the cube's GL resources (shader program, VAO, VBO).
     * Call this from the scene's {@code cleanup()} method.
     */
    public void delete() {
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
    }

    /**
     * Builds a unit cube (2×2×2) sitting on the floor plane (Y=0 to Y=2),
     * centered at the origin on X and Z axes.
     *
     * <p>Each face has a distinct color for easy identification from different angles:
     * front=red, back=cyan, left=green, right=magenta, top=blue, bottom=yellow.</p>
     *
     * <p>Layout: 36 vertices (6 faces × 2 triangles × 3 vertices),
     * each vertex has 6 floats (x, y, z, r, g, b).</p>
     */
    static float[] buildCubeVertices() {
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
