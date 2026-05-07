package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import org.joml.Matrix4f;

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
 * pipeline.beginFrame(view, proj, timeSecs, viewportW, viewportH);
 *
 * CgBufferWriter w = pipeline.getObjectBuffer().beginWrite(N);
 * // ... write N object records ...
 * pipeline.getObjectBuffer().endWrite();
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

    /** std140 float count for CgFrameBlock: 2×mat4 + vec4 + vec2 = 38 floats = 152 bytes. */
    public static final int FLOATS_PER_FRAME_BLOCK = 38;

    /** Default initial object record capacity for the owned object buffer. Auto-grows as needed. */
    private static final int DEFAULT_OBJECT_CAPACITY = 64;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static volatile CgMaterialPipeline INSTANCE;

    /**
     * Creates and installs the singleton pipeline. Must be called once on the GL thread
     * after context creation, before any {@link #getInstance()} call.
     **/
    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new CgMaterialPipeline();
    }

    /**
     * Returns the active pipeline singleton.
     **/
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
        CgMaterialPipeline inst = INSTANCE;
        INSTANCE = null;
        if (inst != null) inst.delete();
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final CgUniformBuffer frameUbo;
    private final CgShaderBuffer objectBuffer;
    private boolean deleted;

    private CgMaterialPipeline() {
        this.frameUbo = new CgUniformBuffer(CgUniformBuffer.BLOCK_NAME, CgBindingPoints.FRAME_DATA);
        this.objectBuffer = CgShaderBuffer.create(DEFAULT_OBJECT_CAPACITY);
    }

    /**
     * Returns the per-frame UBO ({@code CgFrameBlock}) that backs all material programs.
     *
     * <h3>Buffer ABI (std140, binding point {@link io.github.somehussar.crystalgraphics.api.CgBindingPoints#FRAME_DATA})</h3>
     * <p>{@link #beginFrame} writes this layout each frame:</p>
     * <pre>
     *   mat4  cg_ViewMatrix   — floats  0–15, column-major
     *   mat4  cg_ProjMatrix   — floats 16–31, column-major
     *   vec4  cg_Time         — floats 32–35: t/20, t, t×2, t×3 (seconds)
     *   vec2  cg_Resolution   — floats 36–37: viewport width, height (pixels)
     * </pre>
     *
     * <p>{@link #beginFrame} uploads and binds the UBO automatically every frame.
     * Callers rarely need direct access.</p>
     *
     * <p>The primary use case for direct access is wiring the block to an additional
     * custom shader program that was not created through the material pipeline:
     * <pre>{@code
     * frameBuffer().bindBlock(CgUniformBuffer.BLOCK_NAME, myCustomShader.getProgram().getId());
     * }</pre>
     * </p>
     *
     * @return the engine-owned per-frame UBO; never {@code null}
     * @throws IllegalStateException if the pipeline has been destroyed
     */
    public CgUniformBuffer frameBuffer() {
        checkNotDeleted();
        return frameUbo;
    }

    /**
     * Writes frame data into the UBO and binds it to the GL binding point.
     * Must be called once per frame before any {@link CgMaterial#bind()} calls.
     * The UBO binding persists until this pipeline is destroyed or overridden.
     *
     * @param view      view matrix
     * @param proj      projection matrix
     * @param timeSecs  elapsed time in seconds
     * @param viewportW viewport width in pixels
     * @param viewportH viewport height in pixels
     */
    public void beginFrame(Matrix4f view, Matrix4f proj,
                           float timeSecs, int viewportW, int viewportH) {
        checkNotDeleted();
        frameUbo.writer().reset();
        frameUbo.writer()
                .mat4(view)
                .mat4(proj)
                .vec4(timeSecs / 20f, timeSecs, timeSecs * 2f, timeSecs * 3f)
                .vec2(viewportW, viewportH);
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
     * for (int i = 0; i < N; i++) {
     *     w.beginRecord();
     *     w.mat4(modelMatrix).mat4(normalMatrix).vec4(custom0)...;
     *     w.endRecord();
     *     buf.advanceRecord();
     * }
     * buf.endWrite();
     * // then: material.bind() / mesh.draw / material.unbind()
     * }</pre>
     *
     * <p>The buffer auto-grows when {@code N} exceeds current capacity.
     * It may be written to multiple times per frame (e.g. different object batches),
     * but each {@code beginWrite/endWrite} cycle overwrites the previous content.</p>
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
