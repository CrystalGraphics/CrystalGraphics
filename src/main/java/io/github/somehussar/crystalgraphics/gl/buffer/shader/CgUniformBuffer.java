package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * UBO-backed {@link CgShaderBuffer} for per-frame uniform data.
 *
 * <p>Operates in <em>flat mode</em> — there is no fixed per-record stride. The caller
 * writes uniform fields in the order they are declared in the GLSL block, then calls
 * {@link #upload()} to push the staged data to the GPU. The parent's flat-mode
 * constructor allocates a {@code CgStagingBuffer} with no stride concept, and
 * {@link CgBufferWriter#putFloat} auto-grows the backing array on overflow.</p>
 *
 * <h3>Usage per frame</h3>
 * <pre>{@code
 * CgBufferWriter w = frameUbo.writer();
 * w.reset();
 * w.mat4(viewMatrix).mat4(projMatrix);
 * frameUbo.upload();
 * frameUbo.bind();
 * // draw calls ...
 * frameUbo.unbind();
 * }</pre>
 *
 * <h3>Program setup (once after link)</h3>
 * <pre>{@code
 * frameUbo.bindProgramBlock(CgUniformBuffer.BLOCK_NAME, programId);
 * }</pre>
 *
 * <h3>GLSL block</h3>
 * <p>Fields must be declared in the same order as the writes. {@link CgBufferWriter} emits
 * tightly-packed column-major floats, so the GLSL block must match exactly:</p>
 * <pre>{@code
 * layout(std140, binding = 1) uniform CgFrameBlock {
 *     mat4 cg_ViewMatrix;
 *     mat4 cg_ProjMatrix;
 * };
 * }</pre>
 *
 * <h3>std140 padding</h3>
 * <p>For {@code mat3}: use {@link CgBufferWriter#mat3Padded} (48 bytes, vec4-aligned columns)
 * or {@link CgBufferWriter#mat3} (36 bytes tight) depending on the GLSL declaration.
 * Always verify the write sequence matches the GLSL layout exactly.</p>
 */
public final class CgUniformBuffer extends CgShaderBuffer {

    /**
     * Default GLSL uniform block name used by {@code cg_env.glsl}.
     * Pass to {@link #bindBlock(String, int)} after program link.
     */
    public static final String BLOCK_NAME = "CgFrameBlock";

    /**
     * UBO binding point reserved for per-frame data (binding = 1).
     * Matches the {@code layout(binding = 1)} declaration in {@code cg_env.glsl}.
     * Must not conflict with {@link CgShaderBuffer#BINDING_POINT} (binding = 0).
     */
    public static final int BINDING_POINT = 1;

    /**
     * Initial staging and GPU buffer capacity in floats (256 floats = 1 KB).
     * Sufficient for 4 {@code mat4} uniforms. The underlying stream buffer auto-grows
     * if more data is written, so this is only the starting allocation.
     */
    private static final int INITIAL_STAGING_FLOATS = 256;

    /**
     * Creates a UBO using the flat-mode parent constructor.
     * Stream buffer target is {@code GL_UNIFORM_BUFFER}; initial capacity is
     * {@link #INITIAL_STAGING_FLOATS} floats.
     */
    public CgUniformBuffer() {
        super(GL31.GL_UNIFORM_BUFFER, INITIAL_STAGING_FLOATS);
    }

    /**
     * Uploads all data written to {@link #writer()} since the last {@link CgBufferWriter#reset()}
     * to the GPU. A no-op if the writer cursor is 0.
     * The underlying stream buffer auto-grows if the payload exceeds the current GPU allocation.
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
     * Queries the uniform block index for {@code blockName} in the given program and wires it
     * to {@link #BINDING_POINT} via {@code glUniformBlockBinding}. No-op if the block is absent.
     * Call once per program after link.
     *
     * @param blockName the GLSL uniform block name (e.g. {@link #BLOCK_NAME})
     * @param program the CgShader object of the shader program
     */
    public void bindBlock(String blockName, CgShader program) {
        bindBlock(blockName, program.getProgram().getId());
    }

    /**
     * Queries the uniform block index for {@code blockName} in the given program and wires it
     * to {@link #BINDING_POINT} via {@code glUniformBlockBinding}. No-op if the block is absent.
     * Call once per program after link.
     *
     * @param blockName the GLSL uniform block name (e.g. {@link #BLOCK_NAME})
     * @param programId GL program object ID
     */
    public void bindBlock(String blockName, int programId) {
        int idx = GL31.glGetUniformBlockIndex(programId, blockName);
        if (idx != GL31.GL_INVALID_INDEX) {
            GL31.glUniformBlockBinding(programId, idx, BINDING_POINT);
        }
    }

    @Override
    protected void bindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_POINT, getGlBufferId());
    }

    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_POINT, 0);
    }
}


