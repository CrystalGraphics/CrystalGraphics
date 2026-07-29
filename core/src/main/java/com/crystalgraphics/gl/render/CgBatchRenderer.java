package com.crystalgraphics.gl.render;


import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.gl.vertex.CgVertexArrayBinding;
import com.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.profiling.CgProfiler;
import java.nio.ByteBuffer;

/**
 * CPU→GPU pump over the shared per-format binding from {@link CgVertexArrayRegistry}.
 *
 * <p>Owns CPU staging ({@link CgStagingBuffer}) and the format-aware
 * {@link CgVertexWriter}. Does <strong>not</strong> own VBO, VAO, or IBO —
 * those come from the shared registry binding and {@link CgQuadIndexBuffer}.</p>
 *
 * <p>{@link #flush()} performs only: VBO upload, VAO pointer rebinding, IBO
 * bind, and {@code glDrawElements}. It must never bind shaders, textures,
 * blend, depth, cull, or framebuffer state.</p>
 *
 * <h3>Upload-once / draw-many lifecycle (V3.1 draw-list support)</h3>
 * <p>In addition to the immediate {@link #flush()} path, this renderer supports
 * an explicit upload-once / draw-many lifecycle for UI draw-list replay:</p>
 * <ol>
 *   <li>{@link #begin()} — resets staging and opens recording</li>
 *   <li>Record vertices via {@link #vertex()} (same as before)</li>
 *   <li>{@link #uploadPendingVertices()} — uploads staging once, locks recording</li>
 *   <li>{@link #drawUploadedRange(int, int)} — replays vertex spans (repeatable)</li>
 *   <li>{@link #finishUploadedDraws()} — releases replay state</li>
 *   <li>{@link #end()} — closes batch, resets for next frame</li>
 * </ol>
 * <p>After {@link #uploadPendingVertices()}, no more vertex recording or staging
 * growth is allowed. Attempts to record after upload throw {@link IllegalStateException}.</p>
 */
public final class CgBatchRenderer extends CgAbstractRenderer {

    private final CgVertexArrayBinding binding;
    private final CgStagingBuffer staging;
    private final CgVertexWriter writer;

    // ── Upload-once / draw-many state (V3.1) ────────────────────────────

    /** True after {@link #uploadPendingVertices()} and before {@link #finishUploadedDraws()}. */
    private boolean uploadedForReplay;
    /** Number of floats in the current uploaded batch (valid during replay). */
    private int uploadedFloatCount;
    /** Byte offset returned by stream-buffer commit (valid during replay). */
    private int uploadedDataOffset;
    /** Number of vertices uploaded (valid during replay). */
    private int uploadedVertexCount;

    public static CgBatchRenderer create(CgVertexFormat format, int initialMaxQuads) {
        CgStagingBuffer staging = new CgStagingBuffer(format.getFloatsPerVertex(), initialMaxQuads);
        CgVertexArrayBinding binding = CgVertexArrayRegistry.get().getOrCreate(format);
        return new CgBatchRenderer(staging, binding);
    }

    private CgBatchRenderer(CgStagingBuffer staging, CgVertexArrayBinding binding) {
        this.staging = staging;
        this.binding = binding;
        this.writer = new CgVertexWriter(staging, binding.getFormat());
    }

    @Override
    protected void onBegin() {
        uploadedForReplay = false;
        staging.reset();
        staging.ensureRoomForNextVertex();
    }

    @Override
    public void flush() {
        if (!begun || staging.isEmpty()) return;
        if (uploadedForReplay) throw new IllegalStateException("Cannot flush() during replay — use drawUploadedRange()");

        if ((staging.vertexCount() & 3) != 0) {
            throw new IllegalStateException("CgBatchRenderer.flush() requires complete quads; staged vertex count="
                    + staging.vertexCount());
        }

        // Instrumented per stage: this method was the single largest unmeasured block in the text
        // draw path -- roughly a third of draw time belonged to no scope at all, because quadLoop
        // covers submitting quads and nothing covered getting them to the GPU.
        try (CgProfiler.Scope ignored = CgProfiler.scope("batch.flush")) {
            int quadCount = staging.quadCount();
            int floatCount = staging.rawCursor();
            int byteCount = floatCount * Float.BYTES;

            CgProfiler.count("batch.flush.count");
            CgProfiler.sample("batch.flush.quads", quadCount);
            CgProfiler.sample("batch.flush.bytes", byteCount);

            ByteBuffer mapped;
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.map")) {
                mapped = binding.getStreamBuffer().map(byteCount);
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.copyToMapped")) {
                mapped.asFloatBuffer().put(staging.rawData(), 0, floatCount);
            }
            int dataOffset;
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.commit")) {
                dataOffset = binding.getStreamBuffer().commit(byteCount);
            }

            // VAO must be bound BEFORE rebindPointers — glVertexAttribPointer
            // writes into the currently bound VAO state.
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.bindVao")) {
                binding.getVertexArray().bind();
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.rebindPointers")) {
                binding.rebindPointersIfNeeded(dataOffset);
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.indexBuffer")) {
                CgQuadIndexBuffer.get().bindAndEnsureCapacity(quadCount);
            }

            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.drawElements")) {
                CgGL.glDrawElements(CgGL.GL_TRIANGLES, quadCount * 6, CgGL.GL_UNSIGNED_SHORT, 0L);
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.afterSubmit")) {
                binding.getStreamBuffer().afterSubmit();
            }

            try (CgProfiler.Scope ignored2 = CgProfiler.scope("batch.stagingReset")) {
                staging.reset();
                staging.ensureRoomForNextVertex();
            }
        }
    }

    @Override
    protected void onEnd() {
        if (uploadedForReplay) {
            finishUploadedDraws();
        } else {
            flush();
        }
        CgVertexArray.bind(0);
        CgGL.glBindBuffer(CgGL.GL_ARRAY_BUFFER, 0);
    }

    @Override
    protected boolean hasPendingWork() {
        return !staging.isEmpty();
    }

    // ── Upload-once / draw-many lifecycle (V3.1) ────────────────────────

    /**
     * Uploads the current staging buffer contents to the GPU once, then locks
     * the recording phase. After this call, no more vertex recording is allowed
     * until after {@link #finishUploadedDraws()} and a new {@link #begin()}.
     *
     * <p>Call {@link #drawUploadedRange(int, int)} to replay vertex spans from
     * the uploaded data.</p>
     *
     * @throws IllegalStateException if not begun, already uploaded, or staging is empty
     */
    public void uploadPendingVertices() {
        if (!begun) throw new IllegalStateException("CgBatchRenderer not begun");
        if (uploadedForReplay) throw new IllegalStateException("Already uploaded — call finishUploadedDraws() first");
        if (staging.isEmpty()) throw new IllegalStateException("No vertices staged for upload");

        if ((staging.vertexCount() & 3) != 0) {
            throw new IllegalStateException("uploadPendingVertices() requires complete quads; staged vertex count="
                    + staging.vertexCount());
        }

        uploadedVertexCount = staging.vertexCount();
        uploadedFloatCount = staging.rawCursor();
        int byteCount = uploadedFloatCount * Float.BYTES;

        ByteBuffer mapped = binding.getStreamBuffer().map(byteCount);
        mapped.asFloatBuffer().put(staging.rawData(), 0, uploadedFloatCount);
        uploadedDataOffset = binding.getStreamBuffer().commit(byteCount);

        // VAO must be bound BEFORE rebindPointers — glVertexAttribPointer
        // writes into the currently bound VAO state.
        binding.getVertexArray().bind();
        binding.rebindPointersIfNeeded(uploadedDataOffset);

        int totalQuads = uploadedVertexCount / 4;
        CgQuadIndexBuffer.get().bindAndEnsureCapacity(totalQuads);

        uploadedForReplay = true;
    }

    /**
     * Draws a range of vertices from the previously uploaded staging data.
     *
     * <p>This is the replay-side draw call for the draw-list executor.
     * The vertex range must be quad-aligned (vtxStart and vtxCount divisible by 4).
     * The VAO, VBO pointers, and IBO are already bound from
     * {@link #uploadPendingVertices()}.</p>
     *
     * @param vtxStart first vertex index in the uploaded data
     * @param vtxCount number of vertices to draw (must be a multiple of 4)
     * @throws IllegalStateException if not in replay mode
     * @throws IllegalArgumentException if range is invalid or not quad-aligned
     */
    public void drawUploadedRange(int vtxStart, int vtxCount) {
        if (!uploadedForReplay) throw new IllegalStateException("Call uploadPendingVertices() before drawUploadedRange()");
        if (vtxCount <= 0) return;
        if (vtxStart < 0 || vtxStart + vtxCount > uploadedVertexCount) {
            throw new IllegalArgumentException("Vertex range [" + vtxStart + ", " + (vtxStart + vtxCount)
                    + ") exceeds uploaded count " + uploadedVertexCount);
        }
        if ((vtxStart & 3) != 0 || (vtxCount & 3) != 0) {
            throw new IllegalArgumentException("drawUploadedRange requires quad-aligned ranges: vtxStart="
                    + vtxStart + " vtxCount=" + vtxCount);
        }

        int quadCount = vtxCount / 4;
        // Element offset: each quad uses 6 indices (GL_UNSIGNED_SHORT = 2 bytes each)
        // The index buffer pattern is [0,1,2, 2,3,0, 4,5,6, 6,7,4, ...] based on
        // the starting quad, so we need to offset by (vtxStart / 4) * 6 indices.
        int indexOffset = (vtxStart / 4) * 6;
        long byteOffset = indexOffset * 2L; // GL_UNSIGNED_SHORT = 2 bytes per index

        CgGL.glDrawElements(CgGL.GL_TRIANGLES, quadCount * 6, CgGL.GL_UNSIGNED_SHORT, byteOffset);
    }

    /**
     * Releases replay state after all {@link #drawUploadedRange(int, int)} calls
     * are complete. Resets staging for the next recording cycle.
     *
     * <p>Does not unbind VAO/VBO — that is done by {@link #end()}.</p>
     *
     * @throws IllegalStateException if not in replay mode
     */
    public void finishUploadedDraws() {
        if (!uploadedForReplay) throw new IllegalStateException("Not in replay mode");
        binding.getStreamBuffer().afterSubmit();
        uploadedForReplay = false;
        uploadedFloatCount = 0;
        uploadedDataOffset = 0;
        uploadedVertexCount = 0;
        staging.reset();
        staging.ensureRoomForNextVertex();
    }

    public CgVertexWriter vertex() {
        if (!begun) throw new IllegalStateException("CgBatchRenderer not begun");
        if (uploadedForReplay) throw new IllegalStateException("Recording is locked after uploadPendingVertices()");
        writer.reset();
        return writer;
    }

    public boolean isUploadedForReplay() {
        return uploadedForReplay;
    }

    public CgStagingBuffer staging() {
        return staging;
    }

    /** No-op: CPU staging only. Shared GPU resources are owned by the registry. */
    public void delete() {
    }
}
