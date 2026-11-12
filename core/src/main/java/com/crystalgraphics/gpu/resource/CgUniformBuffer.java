package com.crystalgraphics.gpu.resource;


import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;

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
 *         .getOrCreateUboInternal(LIGHT_FORMAT, "LightBlock", lightBinding, CgBufferLifetime.FRAME);
 * // before each draw that reads it:
 * light.writer().reset().beginRecord().vec4("color", r, g, b, 1f);
 * light.endRecord();
 * light.upload();     // a compare when nothing moved and this frame already has it
 * light.bind();       // noted for the pass's sets
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
 * }</pre>
 *
 * <h3>GLSL block</h3>
 * <p>Fields must be declared in the same order as writes. Example:</p>
 * <pre>{@code
 * layout(std140, binding = 1) uniform CgViewBlock {
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
 * <h3>Shader binding</h3>
 * <p>The material compiler declares its block, named {@link #getName()}, at this buffer's binding: a device
 * pass's sets read it there.</p>
 */
public final class CgUniformBuffer extends CgShaderBuffer {

    /**
     * Engine-internal constructor. Creates a format-aware UBO targeting any binding slot,
     * including engine-reserved slots 0–4. User code must use {@link #create} instead.
     *
     * @param name            the GLSL uniform block name this UBO is declared as
     * @param format          typed format descriptor (mandatory)
     * @param bindingLocation the GL binding slot to use
     */
    public CgUniformBuffer(String name, CgBufferLayout format, CgBindingModel.Slot slot) {
        super(name, format, CgStreamBuffer.Use.UNIFORM, slot);
    }

    /**
     * Engine-internal: a UBO at a raw binding slot, with the contents' {@link CgBufferLifetime}. User code takes
     * {@link #create(CgBufferLayout, String, int, CgBufferLifetime)}.
     *
     * <pre>{@code
     * CgUniformBuffer props = new CgUniformBuffer("CgMaterialProperties", format, CgBindingModel.MATERIAL_BLOCK, CgBufferLifetime.FRAME);
     * }</pre>
     */
    public CgUniformBuffer(String name, CgBufferLayout format, CgBindingModel.Slot slot, CgBufferLifetime lifetime) {
        super(name, format, CgStreamBuffer.Use.UNIFORM, slot, lifetime);
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
     * For a block whose layout changed between hot reloads (properties added or removed).
     *
     * @param newFormat the updated buffer format to apply
     */
    public void resetFormat(CgBufferLayout newFormat) {
        super.resetFormat(newFormat);
        writer.resetFormat(newFormat);
    }

    /**
     * On the frame ring, by range at the latest upload -- a whole block, since a record is written whole, rounded
     * up to std140's 16 so the range is never shorter than the block's data size. The reservation is 256-aligned,
     * so the rounding stays inside it.
     */
    @Override
    protected boolean describeInternal(CgBindings out, int binding) {
        CgGpuBuffer buffer = dataBuffer.gpuBuffer();
        if (buffer == null) return false;
        int bytes = (dataBuffer.getCommittedBytes() + 15) & ~15;
        if (dataBuffer.offsetMovesPerUpload() && bytes > 0) {
            out.buffer(binding, CgBindingLayout.Type.UNIFORM_BUFFER, buffer, dataBuffer.getWriteOffset(), bytes);
        } else {
            out.buffer(binding, CgBindingLayout.Type.UNIFORM_BUFFER, buffer, 0, buffer.size());
        }
        return true;
    }
}
