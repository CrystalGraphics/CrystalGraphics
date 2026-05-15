package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.shader.CgActiveUniform;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.state.CallFamily;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFragmentShader;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.crystalgraphics.gl.shader.CgShaderFactory.JOML_BUFFER;

/**
 * Shader program implementation using ARB shader object extension entry points.
 *
 * <p>This backend uses {@link ARBShaderObjects} methods
 * ({@code glCreateShaderObjectARB}, {@code glUseProgramObjectARB},
 * {@code glUniform*ARB}, etc.) for all shader operations.  It is used on
 * hardware that exposes shader support through the {@code GL_ARB_shader_objects}
 * extension rather than Core OpenGL 2.0.</p>
 *
 * <h3>ARB-Specific Method Names</h3>
 * <p>The ARB shader extension uses a distinctly different naming convention
 * from Core GL20.  Key differences:</p>
 * <ul>
 *   <li>Shaders and programs are both "objects" — created with
 *       {@code glCreateShaderObjectARB} / {@code glCreateProgramObjectARB}</li>
 *   <li>Attachment uses {@code glAttachObjectARB} instead of
 *       {@code glAttachShader}</li>
 *   <li>Status queries use {@code glGetObjectParameteriARB} with
 *       {@code GL_OBJECT_COMPILE_STATUS_ARB} / {@code GL_OBJECT_LINK_STATUS_ARB}</li>
 *   <li>All uniform methods carry the {@code ARB} suffix</li>
 *   <li>Shader type constants come from {@link ARBVertexShader} and
 *       {@link ARBFragmentShader}</li>
 * </ul>
 *
 * <h3>Compilation and Linking</h3>
 * <p>New programs are created via the static {@link #compile(String, String, CgVertexFormat)}
 * factory method, which compiles vertex and fragment shader sources, links
 * them into a program, and deletes the individual shader objects (they are
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
 * @see CgCoreShaderProgram
 */
public class CgArbShaderProgram extends CgAbstractShaderProgram {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /**
     * Constructs an ARB shader program wrapper.
     *
     * @param programId the OpenGL program object ID
     * @param owned     {@code true} if CrystalGraphics owns this program
     */
    CgArbShaderProgram(int programId, boolean owned) {
        super(programId, owned);
    }

    /**
     * Compiles vertex and fragment shaders, links them into a program using
     * the ARB shader objects extension.
     *
     * <p>The compilation/link pipeline is:</p>
     * <ol>
     *   <li>Create and compile the vertex shader via
     *       {@code glCreateShaderObjectARB(GL_VERTEX_SHADER_ARB)}</li>
     *   <li>Create and compile the fragment shader via
     *       {@code glCreateShaderObjectARB(GL_FRAGMENT_SHADER_ARB)}</li>
     *   <li>Create a program via {@code glCreateProgramObjectARB()},
     *       attach both shaders, bind attribute names from format
     *       to sequential indices then link</li>
     *   <li>Delete the individual shader objects (no longer needed)</li>
     * </ol>
     *
     * <p>If any step fails, an {@link IllegalStateException} is thrown with
     * the ARB info log included in the message.</p>
     *
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @param format attribute format of the VAO that feeds this shader
     * @return a new owned {@code CgArbShaderProgram}
     * @throws IllegalStateException if compilation or linking fails, with
     *         the info log in the message
     */
    public static CgArbShaderProgram compile(String vertexSource, String fragmentSource, CgVertexFormat format) {
        int progId = ARBShaderObjects.glCreateProgramObjectARB();
        CgArbShaderProgram prog = new CgArbShaderProgram(progId, true);
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
        // glGetAttachedObjectsARB fills countBuf[0] with actual count
        IntBuffer countBuf   = BufferUtils.createIntBuffer(1);
        IntBuffer shadersBuf = BufferUtils.createIntBuffer(16);
        ARBShaderObjects.glGetAttachedObjectsARB(programId, countBuf, shadersBuf);
        int attached = countBuf.get(0);
        for (int i = 0; i < attached; i++) {
            int id = shadersBuf.get(i);
            ARBShaderObjects.glDetachObjectARB(programId, id);
            ARBShaderObjects.glDeleteObjectARB(id);
        }

        int vertId = ARBShaderObjects.glCreateShaderObjectARB(ARBVertexShader.GL_VERTEX_SHADER_ARB);
        ARBShaderObjects.glShaderSourceARB(vertId, vertexSource);
        ARBShaderObjects.glCompileShaderARB(vertId);
        if (ARBShaderObjects.glGetObjectParameteriARB(vertId, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) != GL11.GL_TRUE) {
            String log = ARBShaderObjects.glGetInfoLogARB(vertId, 4096);
            ARBShaderObjects.glDeleteObjectARB(vertId);
            throw new IllegalStateException("Vertex shader compile failed: " + log);
        }

        int fragId = ARBShaderObjects.glCreateShaderObjectARB(ARBFragmentShader.GL_FRAGMENT_SHADER_ARB);
        ARBShaderObjects.glShaderSourceARB(fragId, fragmentSource);
        ARBShaderObjects.glCompileShaderARB(fragId);
        if (ARBShaderObjects.glGetObjectParameteriARB(fragId, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) != GL11.GL_TRUE) {
            String log = ARBShaderObjects.glGetInfoLogARB(fragId, 4096);
            ARBShaderObjects.glDeleteObjectARB(vertId);
            ARBShaderObjects.glDeleteObjectARB(fragId);
            throw new IllegalStateException("Fragment shader compile failed: " + log);
        }

        ARBShaderObjects.glAttachObjectARB(programId, vertId);
        ARBShaderObjects.glAttachObjectARB(programId, fragId);

        if (format != null) {
            for (int i = 0; i < format.getAttributeCount(); i++)
                ARBVertexShader.glBindAttribLocationARB(programId, i, format.getAttribute(i).getName());
        }

        ARBShaderObjects.glLinkProgramARB(programId);

        ARBShaderObjects.glDetachObjectARB(programId, vertId);
        ARBShaderObjects.glDetachObjectARB(programId, fragId);
        ARBShaderObjects.glDeleteObjectARB(vertId);
        ARBShaderObjects.glDeleteObjectARB(fragId);

        if (ARBShaderObjects.glGetObjectParameteriARB(programId, ARBShaderObjects.GL_OBJECT_LINK_STATUS_ARB) != GL11.GL_TRUE) {
            throw new IllegalStateException("Shader program link failed: " + ARBShaderObjects.glGetInfoLogARB(programId, 4096));
        }
    }

    // ── Abstract hook implementations ──────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@link CallFamily#ARB_SHADER_OBJECTS}
     */
    @Override
    protected CallFamily callFamily() {
        return CallFamily.ARB_SHADER_OBJECTS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the program object via
     * {@link ARBShaderObjects#glDeleteObjectARB(int)}.</p>
     */
    @Override
    protected void freeGlResources() {
        ARBShaderObjects.glDeleteObjectARB(programId);
    }

    // ── Uniform operations ─────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Queries the uniform location via
     * {@link ARBShaderObjects#glGetUniformLocationARB(int, CharSequence)}.</p>
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
        return ARBShaderObjects.glGetUniformLocationARB(programId, name);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Queries active uniforms via {@link ARBShaderObjects#glGetActiveUniformARB} and
     * filters out built-in {@code gl_*} names.</p>
     */
    @Override
    public List<CgActiveUniform> getActiveUniforms() {
        int count = ARBShaderObjects.glGetObjectParameteriARB(programId,
                ARBShaderObjects.GL_OBJECT_ACTIVE_UNIFORMS_ARB);
        if (count <= 0) return Collections.emptyList();
        int maxLen = ARBShaderObjects.glGetObjectParameteriARB(programId,
                ARBShaderObjects.GL_OBJECT_ACTIVE_UNIFORM_MAX_LENGTH_ARB);
        if (maxLen <= 0) maxLen = 256;

        List<CgActiveUniform> result = new ArrayList<>(count);
        // LWJGL2 convenience form: glGetActiveUniformARB(program, index, maxLength, sizeTypeBuf)
        // fills sizeTypeBuf[0]=size, sizeTypeBuf[1]=type and returns the uniform name.
        IntBuffer sizeTypeBuf = BufferUtils.createIntBuffer(2);
        for (int i = 0; i < count; i++) {
            sizeTypeBuf.clear();
            String name = ARBShaderObjects.glGetActiveUniformARB(programId, i, maxLen, sizeTypeBuf);
            if (name == null || name.startsWith("gl_")) continue;
            int size = sizeTypeBuf.get(0);
            int glType = sizeTypeBuf.get(1);
            int loc = ARBShaderObjects.glGetUniformLocationARB(programId, name);
            result.add(new CgActiveUniform(name, glType, size, loc));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the integer uniform via
     * {@link ARBShaderObjects#glUniform1iARB(int, int)}.</p>
     *
     * @param location the uniform location
     * @param value    the integer value
     */
    @Override
    public void setUniform1i(int location, int value) {
        ARBShaderObjects.glUniform1iARB(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the float uniform via
     * {@link ARBShaderObjects#glUniform1fARB(int, float)}.</p>
     *
     * @param location the uniform location
     * @param value    the float value
     */
    @Override
    public void setUniform1f(int location, float value) {
        ARBShaderObjects.glUniform1fARB(location, value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 2-component float vector uniform via
     * {@link ARBShaderObjects#glUniform2fARB(int, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     */
    @Override
    public void setUniform2f(int location, float x, float y) {
        ARBShaderObjects.glUniform2fARB(location, x, y);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 3-component float vector uniform via
     * {@link ARBShaderObjects#glUniform3fARB(int, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     */
    @Override
    public void setUniform3f(int location, float x, float y, float z) {
        ARBShaderObjects.glUniform3fARB(location, x, y, z);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sets the 4-component float vector uniform via
     * {@link ARBShaderObjects#glUniform4fARB(int, float, float, float, float)}.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     * @param w        the fourth component
     */
    @Override
    public void setUniform4f(int location, float x, float y, float z, float w) {
        ARBShaderObjects.glUniform4fARB(location, x, y, z, w);
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>Binds a texture unit to a sampler uniform via
     * {@link ARBShaderObjects#glUniform1iARB(int, int)}.  The
     * {@code textureUnit} parameter is the zero-based unit index
     * (0 = {@code GL_TEXTURE0}).</p>
     *
     * @param location    the sampler uniform location
     * @param textureUnit the zero-based texture unit index
     */
    @Override
    public void setSampler(int location, int textureUnit) {
        ARBShaderObjects.glUniform1iARB(location, textureUnit);
    }

    @Override
    public void setUniformFloatBuffer(int location, FloatBuffer buffer) {
        if (location < 0) return;
        ARBShaderObjects.glUniform1ARB(location, buffer);
    }

    @Override
    public void setUniformIntBuffer(int location, IntBuffer buffer) {
        if (location < 0) return;
        ARBShaderObjects.glUniform1ARB(location, buffer);
    }

    @Override
    public void setUniformMatrix3f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        ARBShaderObjects.glUniformMatrix3ARB(location, false, buffer);
    }
    
    @Override
    public void setUniformMatrix3f(int location, Matrix3f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        matrix.get(buf).rewind();
        ARBShaderObjects.glUniformMatrix3ARB(location, false, buf);
    }

     /**
     * {@inheritDoc}
     *
     * <p>Uploads the 4x4 matrix via
     * {@link ARBShaderObjects#glUniformMatrix4ARB(int, boolean, FloatBuffer)}.</p>
     *
     * @param location the uniform location
     * @param buffer   a 16-element FloatBuffer in column-major order
     */
    @Override
    public void setUniformMatrix4f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        ARBShaderObjects.glUniformMatrix4ARB(location, false, buffer);
    }

    @Override
    public void setUniformMatrix4f(int location, Matrix4f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        matrix.get(buf).rewind();
        ARBShaderObjects.glUniformMatrix4ARB(location, false, buf);
    }
}
