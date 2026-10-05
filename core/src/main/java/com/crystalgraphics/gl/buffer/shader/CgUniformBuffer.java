package com.crystalgraphics.gl.buffer.shader;


import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * UBO-backed {@link CgShaderBuffer} for a uniform block.
 *
 * <p>Operates in <em>flat mode</em> — there is no per-record multiplexing. The caller
 * writes uniform fields via named writes, then calls {@link #upload()} to push staged data
 * to the GPU.</p>
 *
 * <p><b>{@link #upload()} before every draw that reads the block, and it does the least it can.</b> An
 * unchanged block is not uploaded again -- except, for a {@link CgBufferLifetime#FRAME} block, on the first
 * upload of a new frame, which copies it into that frame's ring region. So the rule a {@code FRAME} block
 * needs, that every frame reading it uploads it first, costs a compare wherever nothing changed. That is how
 * every block the engine owns lives on the ring: material properties ({@code CgMaterial} uploads at every
 * bind) and the text block. A pass's frame block is the executor's, bound by range from its ring. A {@link CgBufferLifetime#RETAINED} block orphans on upload and binds at 0, so it stays readable
 * for draws that never upload it.</p>
 *
 * <pre>{@code
 * CgUniformBuffer light = CgShaderBufferRegistry.get()
 *         .getOrCreateUbo(LIGHT_FORMAT, "LightBlock", 0, CgBufferLifetime.FRAME);
 * // before each draw that reads it:
 * light.writer().reset().beginRecord().vec4("color", r, g, b, 1f);
 * light.endRecord();
 * light.upload();     // a compare when nothing moved and this frame already has it
 * light.bind();
 * }</pre>
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
        super(name, format, CgGL.GL_UNIFORM_BUFFER, bindingLocation);
    }

    /**
     * Engine-internal: a UBO at a raw binding slot, with the contents' {@link CgBufferLifetime}. User code takes
     * {@link #create(CgBufferFormat, String, int, CgBufferLifetime)}.
     *
     * <pre>{@code
     * CgUniformBuffer props = new CgUniformBuffer("CgMaterialProperties", format, binding, CgBufferLifetime.FRAME);
     * }</pre>
     */
    public CgUniformBuffer(String name, CgBufferFormat format, int bindingLocation, CgBufferLifetime lifetime) {
        super(name, format, CgGL.GL_UNIFORM_BUFFER, bindingLocation, lifetime);
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
        return create(format, name, userIndex, CgBufferLifetime.RETAINED);
    }

    /** As {@link #create(CgBufferFormat, String, int)}, with the contents' {@link CgBufferLifetime}. */
    public static CgUniformBuffer create(CgBufferFormat format, String name, int userIndex, CgBufferLifetime lifetime) {
        return new CgUniformBuffer(name, format, CgBindingPoints.USER_START_UBO + userIndex, lifetime);
    }

    /**
     * Uploads what {@link #writer()} holds, unless the GPU already has it: bytes equal to the last upload are not
     * sent again, except a {@link CgBufferLifetime#FRAME} block's first upload of a frame -- see
     * {@link CgShaderBuffer#uploadData}, which every shader buffer's uploads pass. A no-op if the writer cursor
     * is 0.
     *
     * <p>Call {@link #endRecord()} first to finalize the record.</p>
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

    /**
     * On the frame ring, by range at the latest upload -- a whole block, since a record is written whole, rounded
     * up to std140's 16 so the range is never shorter than the block's data size. The reservation is 256-aligned,
     * so the rounding stays inside it.
     */
    @Override
    protected void bindInternal() {
        int bytes = (dataBuffer.getCommittedBytes() + 15) & ~15;
        if (dataBuffer.offsetMovesPerUpload() && bytes > 0) {
            CgGL.glBindBufferRange(CgGL.GL_UNIFORM_BUFFER, bindingLocation, dataBuffer.getGlBuffer(),
                    dataBuffer.getWriteOffset(), bytes);
        } else {
            CgGL.glBindBufferBase(CgGL.GL_UNIFORM_BUFFER, bindingLocation, dataBuffer.getGlBuffer());
        }
    }

    @Override
    protected void unbindInternal() {
        CgGL.glBindBufferBase(CgGL.GL_UNIFORM_BUFFER, bindingLocation, 0);
    }

    /** Wires {@code shader}'s uniform block {@code name} to {@code binding}, with no buffer object of its own. */
    public static void wireBlock(CgShader shader, String name, int binding) {
        int program = shader.getProgram().getId();
        int index = CgGL.glGetUniformBlockIndex(program, name);
        if (index != CgGL.GL_INVALID_INDEX) CgGL.glUniformBlockBinding(program, index, binding);
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
        int idx = CgGL.glGetUniformBlockIndex(programId, getName());
        if (idx != CgGL.GL_INVALID_INDEX) {
            CgGL.glUniformBlockBinding(programId, idx, bindingLocation);
        }
    }
}
