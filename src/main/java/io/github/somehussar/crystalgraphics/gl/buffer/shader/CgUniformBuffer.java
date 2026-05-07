package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
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
 * frameUbo.bindBlock(CgUniformBuffer.BLOCK_NAME, programId);
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
     * Matches {@link CgBindingPoints#FRAME_DATA}.
     * Must not conflict with {@link CgShaderBuffer#BINDING_POINT} (binding = 0).
     */
    public static final int BINDING_POINT = CgBindingPoints.FRAME_DATA;

    /**
     * Initial staging and GPU buffer capacity in floats (256 floats = 1 KB).
     * Sufficient for 4 {@code mat4} uniforms. The underlying stream buffer auto-grows
     * if more data is written, so this is only the starting allocation.
     */
    private static final int INITIAL_STAGING_FLOATS = 256;

    /** GLSL block name this UBO is wired to. Set once at construction. */
    private final String blockName;

    /**
     * Engine-internal constructor. Creates a UBO targeting any binding slot, including
     * engine-reserved slots 0–9. User code must use {@link #create} instead, which
     * enforces slot reservation. Callers outside {@code gl/buffer/shader/} that need
     * an engine-owned UBO (e.g. {@code CgMaterialPipeline}) use this directly.
     *
     * @param blockName       the GLSL uniform block name this UBO is wired to
     * @param bindingLocation the GL binding slot to use
     */
    public CgUniformBuffer(String blockName, int bindingLocation) {
        super(GL31.GL_UNIFORM_BUFFER, INITIAL_STAGING_FLOATS);
        this.blockName       = blockName;
        this.bindingLocation = bindingLocation;
    }

    /**
     * Creates a user-defined UBO. Enforces that {@code bindingLocation} is at least
     * {@link CgBindingPoints#USER_START} (10) — slots 0–9 are engine-reserved and
     * collisions produce silent rendering corruption with no GL error.
     *
     * @param blockName       the GLSL uniform block name (must match the {@code uniform} block declaration)
     * @param bindingLocation binding slot; must be &gt;= {@link CgBindingPoints#USER_START}
     * @return a new {@code CgUniformBuffer}
     * @throws IllegalArgumentException if {@code bindingLocation} is in the engine-reserved range
     */
    public static CgUniformBuffer create(String blockName, int bindingLocation) {
        if (bindingLocation < CgBindingPoints.USER_START) {
            throw new IllegalArgumentException(
                "Binding slot " + bindingLocation + " is reserved for the engine (0\u2013"
                + (CgBindingPoints.USER_START - 1) + "). "
                + "Use CgBindingPoints.USER_START (" + CgBindingPoints.USER_START + "+) for custom UBOs. "
                + "Conflicts here produce silent rendering corruption with no GL error.");
        }
        return new CgUniformBuffer(blockName, bindingLocation);
    }

    /** Returns the GLSL uniform block name this UBO is wired to. */
    public String getBlockName() {
        return blockName;
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
     * Wires the uniform block {@code blockName} in {@code program} to this UBO's
     * {@link #bindingLocation} via {@code glUniformBlockBinding}. No-op if the block is absent.
     *
     * <p><strong>Warning:</strong> {@code glUniformBlockBinding} only wires the program-side index
     * to the binding point. The UBO data is not visible to the shader until {@link #bind()} is
     * called at draw time. Call {@link #upload()} before the first {@link #bind()} to ensure GPU
     * data is present.</p>
     *
     * <p>Call once per program after link. If {@link #setBindingLocation(int)} is called later,
     * re-call this method so the program and UBO stay in sync.</p>
     *
     * @param blockName the GLSL uniform block name (e.g. {@link #BLOCK_NAME})
     * @param shader   the Cg shader to wire
     */
    public void bindBlock(String blockName, CgShader shader) {
        bindBlock(blockName, shader.getProgram().getId());
    }

    /**
     * Wires the uniform block {@code blockName} in {@code programId} to this UBO's
     * {@link #bindingLocation} via {@code glUniformBlockBinding}. No-op if the block is absent.
     *
     * <p><strong>Warning:</strong> {@code glUniformBlockBinding} only wires the program-side index
     * to the binding point. The UBO data is not visible to the shader until {@link #bind()} is
     * called at draw time. Call {@link #upload()} before the first {@link #bind()} to ensure GPU
     * data is present.</p>
     *
     * <p>Call once per program after link. If {@link #setBindingLocation(int)} is called later,
     * re-call this method so the program and UBO stay in sync.</p>
     *
     * @param blockName the GLSL uniform block name (e.g. {@link #BLOCK_NAME})
     * @param programId GL program object ID
     */
    public void bindBlock(String blockName, int programId) {
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
