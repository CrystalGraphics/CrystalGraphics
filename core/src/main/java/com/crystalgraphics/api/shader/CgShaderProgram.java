package com.crystalgraphics.api.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.crystalgraphics.gl.shader.CgShaderFactory.JOML_BUFFER;

/**
 * A linked GLSL program: compiled from vertex and fragment source, bound for drawing, given its uniforms. Most
 * callers want a {@link CgShader} or a material, which own one.
 *
 * <pre>{@code
 * CgShaderProgram program = CgShaderProgram.compile(vertexSource, fragmentSource, CgVertexFormat.SPATIAL);
 * program.bind();
 * program.setUniformMatrix4f(program.getUniformLocation("u_mvp"), mvp);
 * ... draw ...
 * program.delete();
 * }</pre>
 *
 * <p>Linking without waiting, for a driver that compiles on threads of its own:</p>
 * <pre>{@code
 * CgShaderProgram program = CgShaderProgram.create();
 * program.submitLink(vertexSource, fragmentSource, format);
 * // later frames:
 * if (program.isLinkDone()) program.finishLink();   // throws as compile would
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, as every GL object.</li>
 *   <li>The uniform setters act on the <em>bound</em> program: bind it first.</li>
 *   <li>A failed compile or link throws {@link IllegalStateException} with the driver's log. A failed
 *       {@link #relink} keeps the program's id and its previous executable.</li>
 *   <li>Finish a {@link #submitLink} before binding: anything that queries the program waits for the driver.</li>
 * </ul>
 */
public final class CgShaderProgram {

    private final int programId;
    private boolean deleted;

    /** The shader objects of a {@link #submitLink} not yet finished; 0 when nothing is pending. */
    private int pendingVert;
    private int pendingFrag;

    private CgShaderProgram(int programId) {
        this.programId = programId;
    }

    /**
     * Compiles a vertex and a fragment shader and links them, binding {@code format}'s attributes to sequential
     * locations.
     *
     * @param format the vertex format of the geometry it draws, or null to bind no attribute locations
     * @throws IllegalStateException if a stage fails to compile or the program to link, with the driver's log
     */
    public static CgShaderProgram compile(String vertexSource, String fragmentSource, CgVertexFormat format) {
        CgShaderProgram program = create();
        try {
            program.relink(vertexSource, fragmentSource, format);
        } catch (IllegalStateException e) {
            program.delete();
            throw e;
        }
        return program;
    }

    /** A program with nothing linked yet, for a {@link #submitLink} to fill. */
    public static CgShaderProgram create() {
        return new CgShaderProgram(CgGL.glCreateProgram());
    }

    // ── Binding and lifetime ───────────────────────────────────────────────

    /** Makes this the program draws use. */
    public void bind() {
        CgGL.glUseProgram(programId);
    }

    /** Binds program 0. */
    public void unbind() {
        CgGL.glUseProgram(0);
    }

    /** The GL program name. */
    public int getId() {
        return programId;
    }

    /** Releases the GL program, and any shader objects a pending link still holds. A second call does nothing. */
    public void delete() {
        if (deleted) return;
        if (pendingVert != 0) {
            CgGL.glDeleteShader(pendingVert);
            CgGL.glDeleteShader(pendingFrag);
            pendingVert = 0;
            pendingFrag = 0;
        }
        CgGL.glDeleteProgram(programId);
        deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    // ── Linking ────────────────────────────────────────────────────────────

    /**
     * Relinks this program from new sources, keeping its id: the old shader objects are detached and deleted, the
     * new ones compiled, attached, given {@code format}'s attribute locations and linked.
     *
     * @param format the vertex format for {@code glBindAttribLocation}, or null to bind none
     * @throws IllegalStateException if a stage fails to compile or the program to link. GL keeps the previous
     *         executable, so a later successful relink recovers it
     */
    public void relink(String vertexSource, String fragmentSource, CgVertexFormat format) {
        submitLink(vertexSource, fragmentSource, format);
        finishLink();
    }

    /**
     * {@link #relink}, returning before the driver has finished: no status is queried in between, which is what
     * lets the driver compile on its own threads. The shader objects stay attached until {@link #finishLink}, which
     * reads their logs if the link failed.
     */
    public void submitLink(String vertexSource, String fragmentSource, CgVertexFormat format) {
        finishPendingQuietly();
        IntBuffer countBuf = CgBufferUtils.createIntBuffer(1);
        IntBuffer shadersBuf = CgBufferUtils.createIntBuffer(16);
        CgGL.glGetAttachedShaders(programId, countBuf, shadersBuf);
        int attached = countBuf.get(0);
        for (int i = 0; i < attached; i++) {
            int id = shadersBuf.get(i);
            CgGL.glDetachShader(programId, id);
            CgGL.glDeleteShader(id);
        }

        pendingVert = CgGL.glCreateShader(CgGL.GL_VERTEX_SHADER);
        CgGL.glShaderSource(pendingVert, vertexSource);
        CgGL.glCompileShader(pendingVert);
        pendingFrag = CgGL.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        CgGL.glShaderSource(pendingFrag, fragmentSource);
        CgGL.glCompileShader(pendingFrag);

        CgGL.glAttachShader(programId, pendingVert);
        CgGL.glAttachShader(programId, pendingFrag);
        if (format != null) {
            for (int i = 0; i < format.getAttributeCount(); i++)
                CgGL.glBindAttribLocation(programId, i, format.getAttribute(i).getName());
        }
        CgGL.glLinkProgram(programId);
    }

    /** Whether {@link #finishLink} would return without waiting. True wherever the driver cannot say. */
    public boolean isLinkDone() {
        return pendingVert == 0 || !CgCapabilities.detect().isParallelShaderCompile()
                || CgGL.glGetProgrami(programId, CgGL.GL_COMPLETION_STATUS_KHR) == CgGL.GL_TRUE;
    }

    /**
     * Ends a {@link #submitLink}: waits if the driver has not finished, then throws as {@link #relink} would. A no-op
     * when nothing is pending.
     */
    public void finishLink() {
        if (pendingVert == 0) return;
        int vertId = pendingVert, fragId = pendingFrag;
        pendingVert = 0;
        pendingFrag = 0;
        try {
            // The compile statuses first: a failed stage makes the link fail too, and its log names the line.
            if (CgGL.glGetShaderi(vertId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Vertex shader compile failed: " + CgGL.glGetShaderInfoLog(vertId, 4096));
            }
            if (CgGL.glGetShaderi(fragId, CgGL.GL_COMPILE_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Fragment shader compile failed: " + CgGL.glGetShaderInfoLog(fragId, 4096));
            }
            if (CgGL.glGetProgrami(programId, CgGL.GL_LINK_STATUS) != CgGL.GL_TRUE) {
                throw new IllegalStateException("Shader program link failed: " + CgGL.glGetProgramInfoLog(programId, 4096));
            }
        } finally {
            CgGL.glDetachShader(programId, vertId);
            CgGL.glDetachShader(programId, fragId);
            CgGL.glDeleteShader(vertId);
            CgGL.glDeleteShader(fragId);
        }
    }

    /** A submit over one still pending: the earlier one is superseded, and its failure is nobody's to report. */
    private void finishPendingQuietly() {
        try {
            finishLink();
        } catch (IllegalStateException superseded) {
            // Replaced by the submit that called this.
        }
    }

    // ── Uniforms: on the bound program; a location of -1 (absent or optimised out) is skipped ─────────────

    /**
     * The location of a uniform, or -1 when the program has none of that name (or the compiler removed it). Needs
     * a linked program, not a bound one.
     *
     * @throws IllegalArgumentException if {@code name} is null
     */
    public int getUniformLocation(String name) {
        if (name == null) throw new IllegalArgumentException("Uniform name must not be null");
        return CgGL.glGetUniformLocation(programId, name);
    }

    /** Every active uniform after a successful link, {@code gl_*} built-ins excluded. Unmodifiable, never null. */
    public List<CgActiveUniform> getActiveUniforms() {
        int count = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORMS);
        if (count <= 0) return Collections.emptyList();
        int maxLen = CgGL.glGetProgrami(programId, CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH);
        if (maxLen <= 0) maxLen = 256;

        List<CgActiveUniform> result = new ArrayList<>(count);
        // The LWJGL 2 form: fills sizeTypeBuf[0] = size, [1] = type, and returns the name.
        IntBuffer sizeTypeBuf = CgBufferUtils.createIntBuffer(2);
        for (int i = 0; i < count; i++) {
            sizeTypeBuf.clear();
            String name = CgGL.glGetActiveUniform(programId, i, maxLen, sizeTypeBuf);
            if (name == null || name.startsWith("gl_")) continue;
            result.add(new CgActiveUniform(name, sizeTypeBuf.get(1), sizeTypeBuf.get(0), CgGL.glGetUniformLocation(programId, name)));
        }
        return Collections.unmodifiableList(result);
    }

    public void setUniform1i(int location, int value) {
        CgGL.glUniform1i(location, value);
    }

    public void setUniform1f(int location, float value) {
        CgGL.glUniform1f(location, value);
    }

    public void setUniform2f(int location, float x, float y) {
        CgGL.glUniform2f(location, x, y);
    }

    public void setUniform2f(int location, Vector2f vec) {
        setUniform2f(location, vec.x, vec.y);
    }

    public void setUniform3f(int location, float x, float y, float z) {
        CgGL.glUniform3f(location, x, y, z);
    }

    public void setUniform3f(int location, Vector3f vec) {
        setUniform3f(location, vec.x, vec.y, vec.z);
    }

    public void setUniform4f(int location, float x, float y, float z, float w) {
        CgGL.glUniform4f(location, x, y, z, w);
    }

    public void setUniform4f(int location, Vector4f vec) {
        setUniform4f(location, vec.x, vec.y, vec.z, vec.w);
    }

    /** A sampler uniform set to texture unit {@code textureUnit} (0 is {@code GL_TEXTURE0}); bind the texture there yourself. */
    public void setSampler(int location, int textureUnit) {
        CgGL.glUniform1i(location, textureUnit);
    }

    /** A {@code float[]} uniform, one element per buffer element; not a {@code vec} or {@code mat} array. */
    public void setUniformFloatBuffer(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    /** An {@code int[]} uniform, one element per buffer element; not an {@code ivec} array. */
    public void setUniformIntBuffer(int location, IntBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniform1(location, buffer);
    }

    /** A {@code mat3} from 9 column-major floats in a direct buffer. */
    public void setUniformMatrix3f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix3(location, false, buffer);
    }

    public void setUniformMatrix3f(int location, Matrix3f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix3(location, false, buf);
    }

    /** A {@code mat4} from 16 column-major floats in a direct buffer. */
    public void setUniformMatrix4f(int location, FloatBuffer buffer) {
        if (location < 0) return;
        CgGL.glUniformMatrix4(location, false, buffer);
    }

    public void setUniformMatrix4f(int location, Matrix4f matrix) {
        if (location < 0) return;
        FloatBuffer buf = JOML_BUFFER.get();
        buf.clear();
        matrix.get(buf).rewind();
        CgGL.glUniformMatrix4(location, false, buf);
    }
}
