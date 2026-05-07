package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

/**
 * Singleton owner of the frame-level GPU resources shared by all materials:
 * the per-frame UBO ({@code CgFrameBlock}) and the per-object SSBO/TBO.
 *
 * <p>Follows the Unity/Godot pattern: the render pipeline owns frame-global
 * constant buffers and binds them once before any draw calls. Materials own
 * only their own properties.</p>
 *
 * <p><strong>TODO</strong>: if a greater pipeline orchestrator is ever introduced
 * (e.g. a render graph or multi-pass manager), this class should be absorbed by
 * it rather than growing independently.</p>
 *
 * <h3>Lifecycle</h3>
 * <pre>{@code
 * // On GL context creation:
 * CgMaterialPipeline.init();
 *
 * // Per-frame:
 * CgMaterialPipeline pipeline = CgMaterialPipeline.getInstance();
 * pipeline.beginFrame(frameUniforms);  // frameUniforms is a reusable CgFrameUniforms instance
 *
 * CgShaderBuffer buf = pipeline.objectBuffer();
 * CgBufferWriter w = buf.beginWrite(N);
 * for (MyObject obj : objects) {
 *     w.beginRecord()
 *      .mat4("modelMatrix", obj.getModel())
 *      .mat4("normalMatrix", obj.getNormal());
 *     // custom0-3 auto-zeroed
 *     buf.endRecord();
 * }
 * buf.endWrite();
 *
 * material.bind();
 * mesh.drawInstanced(N);
 * material.unbind();
 *
 * // On GL context destroy:
 * CgMaterialPipeline.destroy();
 * }</pre>
 */
public final class CgMaterialPipeline {

    /**
     * Per-frame UBO format (std140). Stride = 38 floats = 152 bytes.
     *
     * <pre>
     *   mat4  cg_ViewMatrix   — floats  0–15 (column-major)
     *   mat4  cg_ProjMatrix   — floats 16–31 (column-major)
     *   vec4  cg_Time         — floats 32–35: t/20, t, t×2, t×3 (seconds)
     *   vec2  cg_Resolution   — floats 36–37: viewport width, height (pixels)
     * </pre>
     */
    public static final CgBufferFormat FRAME_BLOCK_FORMAT = CgBufferFormat
            .builder("CgFrameBlock", CgBufferFormat.MemoryLayout.STD140)
            .mat4("cg_ViewMatrix")
            .mat4("cg_ProjMatrix")
            .vec4("cg_Time")
            .vec2("cg_Resolution")
            .build();

    /**
     * Per-object SSBO/TBO format (std430). Stride = 48 floats = 192 bytes.
     *
     * <pre>
     *   mat4  modelMatrix   — floats  0–15 (column-major)
     *   mat4  normalMatrix  — floats 16–31 (full mat4; shader reads upper-left 3×3 as mat3)
     *   vec4  custom0       — floats 32–35
     *   vec4  custom1       — floats 36–39
     *   vec4  custom2       — floats 40–43
     *   vec4  custom3       — floats 44–47
     * </pre>
     *
     * <p>Use named writes to fill only the fields you need;
     * unwritten fields are auto-zeroed per record.</p>
     */
    public static final CgBufferFormat OBJECT_FORMAT = CgBufferFormat
            .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
            .mat4("modelMatrix")
            .mat4("normalMatrix")
            .vec4("custom0")
            .vec4("custom1")
            .vec4("custom2")
            .vec4("custom3")
            .build();
    

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile CgMaterialPipeline INSTANCE;

    /**
     * Creates and installs the singleton pipeline. Must be called once on the GL thread
     * after context creation, before any {@link #getInstance()} call.
     */
    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new CgMaterialPipeline();
    }

    /** Returns the active pipeline singleton. */
    public static CgMaterialPipeline getInstance() {
        if (INSTANCE == null) init();
        return INSTANCE;
    }

    /**
     * Destroys the singleton, freeing all owned GPU resources.
     * Must be called before {@code CgGraphicsLifecycle.destroyContext()}.
     * A no-op if never initialized.
     */
    public static void destroy() {
        if (INSTANCE != null) INSTANCE.delete();
        INSTANCE = null;
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final CgUniformBuffer frameUbo;
    private final CgShaderBuffer objectBuffer;
    private final CgFrameUniforms frameUniforms = new CgFrameUniforms();
    private boolean deleted;

    private CgMaterialPipeline() {
        this.frameUbo = new CgUniformBuffer(FRAME_BLOCK_FORMAT, CgUniformBuffer.BLOCK_NAME, CgBindingPoints.FRAME_DATA);
        this.objectBuffer = CgShaderBuffer.createInternal(OBJECT_FORMAT, CgBindingPoints.OBJECT_DATA);
    }

    /**
     * Returns the per-frame UBO ({@code CgFrameBlock}) that backs all material programs.
     *
     * <p>The primary use case for direct access is wiring the block to a custom shader program
     * that was not created through the material pipeline:</p>
     * <pre>{@code
     * frameBuffer().bindBlock(CgUniformBuffer.BLOCK_NAME, myCustomShader.getProgram().getId());
     * }</pre>
     *
     * @return the engine-owned per-frame UBO; never {@code null}
     * @throws IllegalStateException if the pipeline has been destroyed
     */
    public CgUniformBuffer frameBuffer() {
        checkNotDeleted();
        return frameUbo;
    }

    /**
     * Returns the pipeline-owned frame uniforms holder.
     * Update fields each frame before calling {@link #beginFrame()}.
     *
     * @return the mutable frame uniforms; never {@code null}
     * @throws IllegalStateException if the pipeline has been destroyed
     */
    public CgFrameUniforms getFrameUniforms() {
        checkNotDeleted();
        return frameUniforms;
    }

    /**
     * Writes frame data into the UBO and binds it to the GL binding point.
     * Reads from the pipeline-owned {@link #getFrameUniforms()} holder.
     * Must be called once per frame before any {@link CgMaterial#bind()} calls.
     *
     * <p>Adding a new frame uniform: add a field to {@link CgFrameUniforms} and
     * {@link #FRAME_BLOCK_FORMAT}, then add one named-write line here. No callers break.</p>
     */
    public void beginFrame() {
        checkNotDeleted();
        frameUbo.writer()
                .reset()
                .beginRecord()
                .mat4("cg_ViewMatrix", frameUniforms.view())
                .mat4("cg_ProjMatrix", frameUniforms.proj())
                .vec4("cg_Time",
                        frameUniforms.timeSecs() / 20f, frameUniforms.timeSecs(),
                        frameUniforms.timeSecs() * 2f, frameUniforms.timeSecs() * 3f)
                .vec2("cg_Resolution",
                        (float) frameUniforms.viewportW(), (float) frameUniforms.viewportH())
                .endRecord();
        frameUbo.upload();
        frameUbo.bind();
    }

    /**
     * Returns the shared per-object SSBO/TBO that backs every material draw.
     *
     * <p>Write all per-object records into this buffer each frame <em>before</em>
     * calling {@link CgMaterial#bind()}. The typical per-frame pattern is:</p>
     * <pre>{@code
     * CgShaderBuffer buf = pipeline.objectBuffer();
     * CgBufferWriter w = buf.beginWrite(N);
     * for (MyObject obj : objects) {
     *     w.beginRecord()
     *      .mat4("modelMatrix", obj.getModel())
     *      .mat4("normalMatrix", obj.getNormal());
     *     // custom0-3 auto-zeroed
     *     buf.endRecord();
     * }
     * buf.endWrite();
     * // then: material.bind() / mesh.drawInstanced(N) / material.unbind()
     * }</pre>
     *
     * <p>The available named fields are defined in {@link #OBJECT_FORMAT}:
     * {@code modelMatrix}, {@code normalMatrix}, {@code custom0}–{@code custom3}.</p>
     *
     * @return the engine-owned object buffer; never {@code null}
     */
    public CgShaderBuffer objectBuffer() {
        checkNotDeleted();
        return objectBuffer;
    }

    private void delete() {
        if (!deleted) {
            deleted = true;
            frameUbo.unbind();
            frameUbo.delete();
            objectBuffer.delete();
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialPipeline has been deleted");
    }
}
