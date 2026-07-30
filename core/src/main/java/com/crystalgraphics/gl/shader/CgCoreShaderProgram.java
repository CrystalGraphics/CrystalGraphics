package com.crystalgraphics.gl.shader;


import com.crystalgraphics.api.shader.CgActiveUniform;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import static com.crystalgraphics.gl.shader.CgShaderFactory.JOML_BUFFER;

/**
 * Shader program implementation using Core OpenGL 2.0 entry points.
 *
 * <p>This backend uses {@link CgGL} methods ({@code glCreateShader},
 * {@code glUseProgram}, {@code glUniform*}, etc.) for all shader operations.
 * It is the preferred shader backend on hardware that supports OpenGL 2.0
 * or higher.</p>
 *
 * <h3>Compilation and Linking</h3>
 * <p>New programs are created via the static {@link #compile(String, String, CgVertexFormat)}
 * factory method, which compiles vertex and fragment shader sources, links
 * them into a program, and detaches the individual shader objects (they are
 * no longer needed after linking).</p>
 *
 * <h3>Ownership</h3>
 * <p>Programs created via {@link #compile(String, String, CgVertexFormat)} are always owned.
 * The package-private constructor is available for wrapping externally-created
 * programs.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are <strong>not</strong> thread-safe.  All methods must be
 * called on the thread that owns the OpenGL context.</p>
 *
 * @see CgAbstractShaderProgram
 * @see CgArbShaderProgram
 */
public class CgCoreShaderProgram extends CgAbstractShaderProgram {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * Constructs a Core GL20 shader program wrapper.
     *
     * @param programId the OpenGL program object ID
     * @param owned     {@code true} if CrystalGraphics owns this program
     */
    CgCoreShaderProgram(int programId, boolean owned) {
        super(programId, owned);
    }

    /**
     * Compiles vertex and fragment shaders, links them into a program from the given vertex format.
     *
     * <p>The compilation/link pipeline is:</p>
     * <ol>
     *   <li>Create and compile the vertex shader</li>
     *   <li>Create and compile the fragment shader</li>
     *   <li>Create a program, attach both shaders, bind attribute names 
     *       from format to sequential indices then link</li>
     *   <li>Delete the individual shader objects (no longer needed)</li>
     * </ol>
     *
     * <p>If any step fails, an {@link IllegalStateException} is thrown with
     * the GL info log included in the message.</p>
     *
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @param format attribute format of the VAO that feeds this shader
     * @return a new owned {@code CgCoreShaderProgram}
     * @throws IllegalStateException if compilation or linking fails, with
     *         the info log in the message
     */
    public static CgCoreShaderProgram compile(String vertexSource, String fragmentSource, CgVertexFormat format) {
        int progId = CgGL.glCreateProgram();
        CgCoreShaderProgram prog = new CgCoreShaderProgram(progId, true);
        try {
            prog.relink(vertexSource, fragmentSource, format);
        } catch (IllegalStateException e) {
            prog.delete();
            throw e;
        }
        return prog;
    }

    @Override
    public void relink(String vertexSource, String fragmentSource, CgVertexFormat format) {
        IntBuffer countBuf   = CgBufferUtils.createIntBuffer(1);
        IntBuffer shadersBuf = CgBufferUtils.createIntBuffer(16);
        CgGL.glGetAttachedShaders(programId, countBuf, shadersBuf);
        int attached = countBuf.get(0);
        for (int i = 0; i < attached; i++) {
            int id = shadersBuf.get(i);
            CgGL.glDetachShader(programId, id);
            CgGL.glDeleteShader(id);
        }

        int vertId = CgGL.glCreateShader(CgGL.GL_VERTEX_SHADER);
        CgGL.glShaderSource(vertId, vertexSource);
        CgGL.glCompileShader(vertId);
        if (CgGL.glGetShaderi(vertId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
            String log = CgGL.glGetShaderInfoLog(vertId, 4096);
            CgGL.glDeleteShader(vertId);
            throw new IllegalStateException("Vertex shader compile failed: " + log);
        }

        int fragId = CgGL.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        CgGL.glShaderSource(fragId, fragmentSource);
        CgGL.glCompileShader(fragId);
        if (CgGL.glGetShaderi(fragId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
            String log = CgGL.glGetShaderInfoLog(fragId, 4096);
            CgGL.glDeleteShader(vertId);
            CgGL.glDeleteShader(fragId);
            throw new IllegalStateException("Fragment shader compile failed: " + log);
        }

        CgGL.glAttachShader(programId, vertId);
        CgGL.glAttachShader(programId, fragId);

        if (format != null) {
            for (int i = 0; i < format.getAttributeCount(); i++)
                CgGL.glBindAttribLocation(programId, i, format.getAttribute(i).getName());
        }

        CgGL.glLinkProgram(programId);

        CgGL.glDetachShader(programId, vertId);
        CgGL.glDetachShader(programId, fragId);
        CgGL.glDeleteShader(vertId);
        CgGL.glDeleteShader(fragId);

        if (CgGL.glGetProgrami(programId, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
            throw new IllegalStateException("Shader program link failed: " + CgGL.glGetProgramInfoLog(programId, 4096));
        }
    }

    // ── Abstract hook implementations ──────────────────────────────────


    /**
     * {@inheritDoc}
     *
     * <p>Deletes the program object via {@link CgGL#glDeleteProgram(int)}.</p>
     */
    @Override
    protected void freeGlResources() {
        CgGL.glDeleteProgram(programId);
    }

    // ── Uniform operations ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Queries the uniform location via
     * {@link CgGL#glGetUniformLocation(int, CharSequence)}.</p>
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
        return CgGL.glGetUniformLocation(programId, name);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queries active uniforms via {@link CgGL#glGetActiveUniform} and
     * filters out built-in {@code gl_*} names.</p>
     */
    @Override
    public List<CgActiveUniform> getActiveUniforms() {
        int count = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORMS);
        if (count <= 0) return Collections.emptyList();
        int maxLen = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH);
        if (maxLen <= 0) maxLen = 256;

        List<CgActiveUniform> result = new ArrayList<>(count);
        // LWJGL2 convenience form: glGetActiveUniform(program, index, maxLength, sizeTypeBuf)
        // fills sizeTypeBuf[0]=size, sizeTypeBuf[1]=type and returns the uniform name.
        IntBuffer sizeTypeBuf = CgBufferUtils.createIntBuffer(2);
        for (int i = 0; i < count; i++) {
            sizeTypeBuf.clear();
            String name = CgGL.glGetActiveUniform(programId, i, maxLen, sizeTypeBuf);
            if (name == null || name.startsWith("gl_")) continue;
            int size = sizeTypeBuf.get(0);
            int glType = sizeTypeBuf.get(1);
            int loc = CgGL.glGetUniformLocation(programId, name);
            result.add(new CgActiveUniform(name, glType, size, loc));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the integer uniform via {@link CgGL#glUniform1i(int, int)}.</p>
     *
     * @param location the uniform location
     * @param value    the integer value
     */
    @Override
    public void setUniform1i(int location, int value) {
        CgGL.glUniform1i(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the float uniform via {@link CgGL#glUniform1f(int, float)}.</p>
     *
     * @param location the uniform location
     * @param value    the float value
     */
    @Override
    public void setUniform1f(int location, float value) {
        CgGL.glUniform1f(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 2-component float vector uniform via
     * {@link CgGL#glUniform2f(int, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     */
    @Override
    public void setUniform2f(int location, float x, float y) {
        CgGL.glUniform2f(location, x, y);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 3-component float vector uniform via
     * {@link CgGL#glUniform3f(int, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     */
    @Override
    public void setUniform3f(int location, float x, float y, float z) {
        CgGL.glUniform3f(location, x, y, z);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 4-component float vector uniform via
     * {@link CgGL#glUniform4f(int, float, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     * @param w        the fourth component
     */
    @Override
    public void setUniform4f(int location, float x, float y, float z, float w) {
        CgGL.glUniform4f(location, x, y, z, w);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Binds a texture unit to a sampler uniform via
     * {@link CgGL#glUniform1i(int, int)}.  The {@code textureUnit} parameter
     * is the zero-based unit index (0 = {@code GL_TEXTURE0}).</p>
     *
     * @param location    the sampler uniform location
     * @param textureUnit the zero-based texture unit index
     */
    @Override
    public void setSampler(int location, int textureUnit) {
        CgGL.glUniform1i(location, textureUnit);
    }

    @Override
    public void setUniformFloatBuffer(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    @Override
    public void setUniformIntBuffer(int location, IntBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    @Override
    public void setUniformMatrix3f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix3(location, false, buffer);
    }
    
    @Override
    public void setUniformMatrix3f(int location, Matrix3f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix3(location, false, buf);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uploads the 4x4 matrix via
     * {@link CgGL#glUniformMatrix4(int, boolean, FloatBuffer)}.</p>
     *
     * @param location the uniform location
     * @param buffer   a 16-element FloatBuffer in column-major order
     */
    @Override
    public void setUniformMatrix4f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix4(location, false, buffer);
    }
    
    @Override
    public void setUniformMatrix4f(int location, Matrix4f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix4(location, false, buf);
    }
    
}
