package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
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
     */
public final class CgUniformBuffer extends CgShaderBuffer {

    /**
     * Default GLSL uniform block name used by {@code cg_env.glsl}.
     * Pass to {@link #bindBlock(int)} after program link.
     */
    public static final String BLOCK_NAME = "CgFrameBlock";

    /** GLSL block name this UBO is wired to. Set once at construction. */
    private final String blockName;

    /**
     * Engine-internal constructor. Creates a format-aware UBO targeting any binding slot,
     * including engine-reserved slots 0–9. User code must use {@link #create} instead.
     *
     * @param format          typed format descriptor (mandatory)
     * @param blockName       the GLSL uniform block name this UBO is wired to
     * @param bindingLocation the GL binding slot to use
     */
    public CgUniformBuffer(CgBufferFormat format, String blockName, int bindingLocation) {
        super(format, GL31.GL_UNIFORM_BUFFER, bindingLocation);
        this.blockName = blockName;
    }

    /**
     * Creates a user-defined format-aware UBO. Enforces that {@code bindingLocation} is at
     * least {@link CgBindingPoints#USER_START} (10).
     *
     * @param format          typed format descriptor (mandatory)
     * @param blockName       the GLSL uniform block name
     * @param bindingLocation binding slot; must be {@code >= CgBindingPoints.USER_START}
     * @return a new {@code CgUniformBuffer}
     * @throws IllegalArgumentException if {@code bindingLocation} is engine-reserved
     */
    public static CgUniformBuffer create(CgBufferFormat format, String blockName, int bindingLocation) {
        CgBindingPoints.validateBindingPoint(bindingLocation);
        return new CgUniformBuffer(format, blockName, bindingLocation);
    }

    /** Returns the GLSL uniform block name this UBO is wired to. */
    public String getBlockName() {
        return blockName;
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
     * Wires the uniform block {@code blockName} in {@code shader} to this UBO's
     * {@link #bindingLocation} via {@code glUniformBlockBinding}. No-op if the block is absent.
     *
     * <p>Call once per program after link.</p>
     *
     * @param shader the Cg shader to wire
     */
    public void bindBlock(CgShader shader) {
        bindBlock(shader.getProgram().getId());
    }

    /**
     * Wires the uniform block {@code blockName} in {@code programId} to this UBO's
     * {@link #bindingLocation} via {@code glUniformBlockBinding}. No-op if the block is absent.
     *
     * <p>Call once per program after link.</p>
     *
     * @param programId GL program object ID
     */
    public void bindBlock(int programId) {
        int idx = GL31.glGetUniformBlockIndex(programId, blockName);
        if (idx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(programId, idx, bindingLocation);
        }
    }

    @Override
    protected void bindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingLocation, getGlBufferId());
    }

    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, bindingLocation, 0);
    }
}
