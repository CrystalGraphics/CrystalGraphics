package io.github.somehussar.crystalgraphics.gl.shader;

import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Shader program implementation using Core OpenGL 2.0 entry points.
 *
 * <p>This backend uses {@link GL20} methods ({@code glCreateShader},
 * {@code glUseProgram}, {@code glUniform*}, etc.) for all shader operations.
 * It is the preferred shader backend on hardware that supports OpenGL 2.0
 * or higher.</p>
 *
 * <h3>Compilation and Linking</h3>
 * <p>New programs are created via the static {@link #compile(String, String)}
 * factory method, which compiles vertex and fragment shader sources, links
 * them into a program, and detaches the individual shader objects (they are
 * no longer needed after linking).</p>
 *
 * <h3>Ownership</h3>
 * <p>Programs created via {@link #compile(String, String)} are always owned.
 * The package-private constructor is available for wrapping externally-created
 * programs.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are <strong>not</strong> thread-safe.  All methods must be
 * called on the thread that owns the OpenGL context.</p>
 *
 * @see AbstractCgShaderProgram
 * @see ArbShaderProgram
 */
public class CoreShaderProgram extends AbstractCgShaderProgram {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * Constructs a Core GL20 shader program wrapper.
     *
     * @param programId the OpenGL program object ID
     * @param owned     {@code true} if CrystalGraphics owns this program
     */
    CoreShaderProgram(int programId, boolean owned) {
        super(programId, owned);
    }

    /**
     * Compiles vertex and fragment shaders, links them into a program.
     *
     * <p>The compilation/link pipeline is:</p>
     * <ol>
     *   <li>Create and compile the vertex shader</li>
     *   <li>Create and compile the fragment shader</li>
     *   <li>Create a program, attach both shaders, and link</li>
     *   <li>Delete the individual shader objects (no longer needed)</li>
     * </ol>
     *
     * <p>If any step fails, an {@link IllegalStateException} is thrown with
     * the GL info log included in the message.</p>
     *
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @return a new owned {@code CoreShaderProgram}
     * @throws IllegalStateException if compilation or linking fails, with
     *         the info log in the message
     */
    public static CoreShaderProgram compile(String vertexSource, String fragmentSource) {
        int vertId = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertId, vertexSource);
        GL20.glCompileShader(vertId);
        if (GL20.glGetShaderi(vertId, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(vertId, 4096);
            GL20.glDeleteShader(vertId);
            throw new IllegalStateException("Vertex shader compile failed: " + log);
        }

        int fragId = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragId, fragmentSource);
        GL20.glCompileShader(fragId);
        if (GL20.glGetShaderi(fragId, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(fragId, 4096);
            GL20.glDeleteShader(vertId);
            GL20.glDeleteShader(fragId);
            throw new IllegalStateException("Fragment shader compile failed: " + log);
        }

        int progId = GL20.glCreateProgram();
        GL20.glAttachShader(progId, vertId);
        GL20.glAttachShader(progId, fragId);
        GL20.glLinkProgram(progId);
        if (GL20.glGetProgrami(progId, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetProgramInfoLog(progId, 4096);
            GL20.glDeleteShader(vertId);
            GL20.glDeleteShader(fragId);
            GL20.glDeleteProgram(progId);
            throw new IllegalStateException("Shader program link failed: " + log);
        }

        GL20.glValidateProgram(progId);
        if (GL20.glGetProgrami(progId, GL20.GL_VALIDATE_STATUS) != GL11.GL_TRUE) {
            String validateLog = GL20.glGetProgramInfoLog(progId, 4096);
            LOGGER.warn("Shader validation warning for program {}: {}", progId, validateLog);
        }

        GL20.glDeleteShader(vertId);
        GL20.glDeleteShader(fragId);

        return new CoreShaderProgram(progId, true);
    }

    // ── Abstract hook implementations ──────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@link CallFamily#CORE_GL20}
     */
    @Override
    protected CallFamily callFamily() {
        return CallFamily.CORE_GL20;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the program object via {@link GL20#glDeleteProgram(int)}.</p>
     */
    @Override
    protected void freeGlResources() {
        GL20.glDeleteProgram(programId);
    }

    // ── Uniform operations ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Queries the uniform location via
     * {@link GL20#glGetUniformLocation(int, CharSequence)}.</p>
     *
     * @param name the uniform variable name
     * @return the uniform location, or -1 if not found
     * @throws IllegalArgumentException if {@code name} is null
     */
    @Override
    public int getUniformLocation(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Uniform name must not be null");
        }
        return GL20.glGetUniformLocation(programId, name);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the integer uniform via {@link GL20#glUniform1i(int, int)}.</p>
     *
     * @param location the uniform location
     * @param value    the integer value
     */
    @Override
    public void setUniform1i(int location, int value) {
        GL20.glUniform1i(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the float uniform via {@link GL20#glUniform1f(int, float)}.</p>
     *
     * @param location the uniform location
     * @param value    the float value
     */
    @Override
    public void setUniform1f(int location, float value) {
        GL20.glUniform1f(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 2-component float vector uniform via
     * {@link GL20#glUniform2f(int, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     */
    @Override
    public void setUniform2f(int location, float x, float y) {
        GL20.glUniform2f(location, x, y);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 3-component float vector uniform via
     * {@link GL20#glUniform3f(int, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     */
    @Override
    public void setUniform3f(int location, float x, float y, float z) {
        GL20.glUniform3f(location, x, y, z);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 4-component float vector uniform via
     * {@link GL20#glUniform4f(int, float, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     * @param w        the fourth component
     */
    @Override
    public void setUniform4f(int location, float x, float y, float z, float w) {
        GL20.glUniform4f(location, x, y, z, w);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uploads the 4x4 matrix via
     * {@link GL20#glUniformMatrix4(int, boolean, FloatBuffer)}.</p>
     *
     * @param location the uniform location
     * @param buffer   a 16-element FloatBuffer in column-major order
     */
    @Override
    public void setUniformMatrix4f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        GL20.glUniformMatrix4(location, false, buffer);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Binds a texture unit to a sampler uniform via
     * {@link GL20#glUniform1i(int, int)}.  The {@code textureUnit} parameter
     * is the zero-based unit index (0 = {@code GL_TEXTURE0}).</p>
     *
     * @param location    the sampler uniform location
     * @param textureUnit the zero-based texture unit index
     */
    @Override
    public void setSampler(int location, int textureUnit) {
        GL20.glUniform1i(location, textureUnit);
    }

    @Override
    public void setUniformFloatBuffer(int location, FloatBuffer buffer) {
        if (location < 0) return;
        GL20.glUniform1(location, buffer);
    }

    @Override
    public void setUniformIntBuffer(int location, IntBuffer buffer) {
        if (location < 0) return;
        GL20.glUniform1(location, buffer);
    }

    @Override
    public void setUniformMatrix3f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        GL20.glUniformMatrix3(location, false, buffer);
    }
}
