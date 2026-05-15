package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * UBO-backed {@link CgShaderBuffer} for per-frame uniform data.
 *
 * <p>Operates in <em>flat mode</em> — there is no per-record multiplexing. The caller
 * writes uniform fields via named writes, then calls {@link #upload()} to push staged data
 * to the GPU.</p>
 *
 * <h3>Write model (format-aware)</h3>
 * <pre>{@code
 * CgBufferWriter w = frameUbo.writer();
 * w.reset()
 *  .beginRecord()
 *  .mat4("cg_ViewMatrix", view)
 *  .mat4("cg_ProjMatrix", proj)
 *  .vec4("cg_Time", t/20, t, t*2, t*3)
 *  .vec2("cg_Resolution", w, h);
 * frameUbo.endRecord();  // finalize the record (sets lastWrittenCount = 1)
 * frameUbo.upload();     // upload staged data to GPU
 * frameUbo.bind();
 * }</pre>
 *
 * <h3>GLSL block</h3>
 * <p>Fields must be declared in the same order as writes. Example:</p>
 * <pre>{@code
 * layout(std140, binding = 1) uniform CgFrameBlock {
 *     mat4 cg_ViewMatrix;
 *     mat4 cg_ProjMatrix;
 * };
 * }</pre>
 *
 * <h3>std140 padding</h3>
 * <p>For {@code mat3}: use {@link CgBufferWriter#mat3(String, org.joml.Matrix3f)}
 * (48 bytes, vec4-aligned columns, named write). Always verify the write sequence
 * matches the GLSL layout exactly.</p>
 *
 * <h3>Shader wiring</h3>
 * <p>After {@code shader.bind()}, call {@link #bind(CgShader)} to both bind the UBO and
 * wire the uniform block index via {@code glUniformBlockBinding}. The block name used for
 * the index lookup is {@link #getName()} (inherited from the parent).</p>
 */
public final class CgUniformBuffer extends CgShaderBuffer {

    /**
     * Engine-internal constructor. Creates a format-aware UBO targeting any binding slot,
     * including engine-reserved slots 0–4. User code must use {@link #create} instead.
     *
     * @param name            the GLSL uniform block name this UBO is wired to (also used
     *                        by {@link #wireShader(CgShader)} for block index lookup)
     * @param format          typed format descriptor (mandatory)
     * @param bindingLocation the GL binding slot to use
     */
    public CgUniformBuffer(String name, CgBufferFormat format, int bindingLocation) {
        super(name, format, GL31.GL_UNIFORM_BUFFER, bindingLocation);
    }

    /**
     * Creates a user-defined format-aware UBO.
     *
     * <p>The {@code userIndex} is 0-based. {@link CgBindingPoints#USER_START_UBO} is added
     * internally to derive the actual GL binding point.</p>
     *
     * @param format    typed format descriptor (mandatory)
     * @param name      the GLSL uniform block name
     * @param userIndex 0-based user slot index (0 = first user slot after engine range)
     * @return a new {@code CgUniformBuffer}
     */
    public static CgUniformBuffer create(CgBufferFormat format, String name, int userIndex) {
        int binding = CgBindingPoints.USER_START_UBO + userIndex;
        return new CgUniformBuffer(name, format, binding);
    }

    /**
     * Engine-internal factory. Accepts raw binding points (may be engine-reserved 0–4).
     * No USER_START offset is added.
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param format       typed format descriptor (mandatory)
     * @param name         the GLSL uniform block name
     * @param bindingPoint binding slot (may be engine-reserved)
     * @return a new {@code CgUniformBuffer}
     */
    static CgUniformBuffer createInternal(CgBufferFormat format, String name, int bindingPoint) {
        return new CgUniformBuffer(name, format, bindingPoint);
    }

    /**
     * Uploads all data written to {@link #writer()} since the last {@link CgBufferWriter#reset()}
     * to the GPU. A no-op if the writer cursor is 0.
     *
     * <p>The caller must call {@link #endRecord()} before {@code upload()} to finalize the
     * record.
     *
     * @throws IllegalStateException if this buffer has been deleted
     */
    public void upload() {
        if (isDeleted()) throw new IllegalStateException("CgUniformBuffer has been deleted");
        int floatCount = writer().rawCursor();
        if (floatCount == 0) return;
        uploadData(writer().rawData(), floatCount);
    }

    /**
     * Updates the format descriptor and resets the CPU-side writer staging buffer.
     * Called by {@code CgMaterial.recompile()} when the properties layout changes
     * between hot-reloads (e.g., properties added or removed).
     *
     * @param newFormat the updated buffer format to apply
     */
    public void resetFormat(CgBufferFormat newFormat) {
        super.resetFormat(newFormat);
        writer.resetFormat(newFormat);
    }

    @Override
    protected void bindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingLocation, getGlBufferId());
    }

    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingLocation, 0);
    }

    /**
     * Wires the uniform block {@link #getName()} in {@code shader} to this UBO's
     * {@link #bindingLocation} via {@code glUniformBlockBinding}. No-op if the block is absent.
     *
     * <p>Called by {@link #bind(CgShader)} after {@link #bindInternal()}. Replaces the
     * deleted {@code bindBlock()} methods.</p>
     *
     * @param shader the currently-bound shader program; must not be null
     */
    @Override
    public void wireShader(CgShader shader) {
        int programId = shader.getProgram().getId();
        int idx = GL31.glGetUniformBlockIndex(programId, getName());
        if (idx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(programId, idx, bindingLocation);
        }
    }
}
