package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstancedVertexArrayBinding;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstancedVertexArrayRegistry;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;

/**
 * State-blind CPU→GPU pump for instanced geometry draws.
 *
 * <p>Owns CPU staging ({@link CgStagingBuffer} for base vertices,
 * {@link CgByteStagingBuffer} for instance data) and the format-aware writers.
 * Does <strong>not</strong> own any GPU resources — VAO and stream buffers are
 * shared via {@link CgInstancedVertexArrayRegistry}.</p>
 *
 * <p>{@link #flush(CgInstancedDrawMode)} performs only: base VBO upload, instance VBO
 * upload, VAO pointer rebinding, IBO bind (for quads), and one instanced draw call.
 * It must never bind shaders, textures, blend, depth, cull, or framebuffer state.</p>
 *
 * <h3>Zero-instance / zero-vertex no-op contract</h3>
 * <p>A flush with zero base vertices or zero instances is a no-op. No GL draw call
 * is issued, and both staging buffers are reset.</p>
 *
 * <p>TODO(v2): add upload-once/draw-many replay for instanced geometry.</p>
 */
public final class CgInstancedBatchRenderer {

    private final CgInstancedVertexArrayBinding binding;
    private final CgStagingBuffer vertexStaging;
    private final CgVertexWriter vertexWriter;
    private final CgStagingBuffer instanceStaging;
    private final CgInstanceWriter instanceWriter;

    private boolean begun;

    /**
     * Creates a renderer for the given base vertex format and instance layout.
     *
     * @param baseFormat        vertex format for base geometry
     * @param instanceLayout    per-instance attribute layout
     * @param initialMaxBaseQuads initial staging capacity in quads
     * @param initialMaxInstances initial instance staging capacity
     */
    public static CgInstancedBatchRenderer create(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout,
                                                  int initialMaxBaseQuads, int initialMaxInstances) {
        CgInstancedVertexArrayBinding binding =
                CgInstancedVertexArrayRegistry.get().getOrCreate(baseFormat, instanceLayout);
        CgStagingBuffer vertexStaging = new CgStagingBuffer(baseFormat.getFloatsPerVertex(), initialMaxBaseQuads);
        CgStagingBuffer instanceStaging = new CgStagingBuffer(instanceLayout.getFloatsPerInstance(), initialMaxInstances);
        return new CgInstancedBatchRenderer(binding, vertexStaging, instanceStaging);
    }

    private CgInstancedBatchRenderer(CgInstancedVertexArrayBinding binding,
                                     CgStagingBuffer vertexStaging,
                                     CgStagingBuffer instanceStaging) {
        this.binding = binding;
        this.vertexStaging = vertexStaging;
        this.vertexWriter = new CgVertexWriter(vertexStaging, binding.getBaseFormat());
        this.instanceStaging = instanceStaging;
        this.instanceWriter = new CgInstanceWriter(instanceStaging, binding.getInstanceLayout());
    }

    /** Resets both staging buffers and opens the recording phase. */
    public void begin() {
        if (begun) throw new IllegalStateException("CgInstancedBatchRenderer already begun");
        begun = true;
        vertexStaging.reset();
        vertexStaging.ensureRoomForNextVertex();
        instanceStaging.reset();
        instanceStaging.ensureRoomForNextVertex();
    }

    /** Returns the base vertex writer for recording base geometry. */
    public CgVertexWriter vertex() {
        if (!begun) throw new IllegalStateException("CgInstancedBatchRenderer not begun");
        vertexWriter.reset();
        return vertexWriter;
    }

    /** Returns the instance writer for recording one instance. */
    public CgInstanceWriter instance() {
        if (!begun) throw new IllegalStateException("CgInstancedBatchRenderer not begun");
        instanceWriter.beginInstance();
        return instanceWriter;
    }

    /**
     * Uploads staging data and issues one instanced draw call.
     *
     * <p>Zero base vertices or zero instances → no-op, staging is reset.</p>
     *
     * @throws IllegalArgumentException if base vertex count is invalid for the given mode
     */
    public void flush(CgInstancedDrawMode mode) {
        if (!begun) return;

        int baseVertexCount = vertexStaging.vertexCount();
        int instanceCount = instanceStaging.vertexCount();

        if (baseVertexCount == 0 || instanceCount == 0) {
            vertexStaging.reset();
            vertexStaging.ensureRoomForNextVertex();
            instanceStaging.reset();
            instanceStaging.ensureRoomForNextVertex();
            return;
        }

        validateDrawShape(mode, baseVertexCount, instanceCount);
        binding.validateParentGeneration();

        // Upload base vertices
        int baseByteCount = vertexStaging.rawCursor() * Float.BYTES;
        ByteBuffer baseMapped = binding.getBaseStreamBuffer().map(baseByteCount);
        baseMapped.asFloatBuffer().put(vertexStaging.rawData(), 0, vertexStaging.rawCursor());
        int baseDataOffset = binding.getBaseStreamBuffer().commit(baseByteCount);

        // Upload instance data
        int instanceFloatCount = instanceStaging.rawCursor();
        int instanceByteCount = instanceFloatCount * Float.BYTES;
        ByteBuffer instanceMapped = binding.getInstanceStreamBuffer().map(instanceByteCount);
        instanceMapped.asFloatBuffer().put(instanceStaging.rawData(), 0, instanceFloatCount);
        int instanceDataOffset = binding.getInstanceStreamBuffer().commit(instanceByteCount);

        // Bind instanced VAO, then rebind pointers if offsets changed
        binding.getVertexArray().bind();
        binding.rebindBasePointersIfNeeded(baseDataOffset);
        binding.rebindInstancePointersIfNeeded(instanceDataOffset);

        if (mode == CgInstancedDrawMode.INDEXED_QUADS) {
            int quadCount = baseVertexCount / 4;
            CgQuadIndexBuffer.get().bindAndEnsureCapacity(quadCount);
            drawElementsInstanced(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_SHORT, 0L, instanceCount);
        } else {
            drawArraysInstanced(GL11.GL_TRIANGLES, 0, baseVertexCount, instanceCount);
        }
        binding.getBaseStreamBuffer().afterSubmit();
        binding.getInstanceStreamBuffer().afterSubmit();

        vertexStaging.reset();
        vertexStaging.ensureRoomForNextVertex();
        instanceStaging.reset();
        instanceStaging.ensureRoomForNextVertex();
    }

    /** Resets lifecycle state (does not draw). */
    public void end() {
        begun = false;
    }

    /** Returns true if there is unsent base vertex data. */
    public boolean isDirty() {
        return begun && !vertexStaging.isEmpty();
    }

    /** No-op: CPU staging only. Shared GPU resources are owned by the registry. */
    public void delete() {
    }

    /**
     * Validates that base vertex count and instance count are compatible with the draw mode.
     *
     * @throws IllegalArgumentException on invalid counts
     */
    static void validateDrawShape(CgInstancedDrawMode mode, int baseVertexCount, int instanceCount) {
        if (baseVertexCount < 0) {
            throw new IllegalArgumentException("baseVertexCount must be >= 0, got " + baseVertexCount);
        }
        if (instanceCount < 0) {
            throw new IllegalArgumentException("instanceCount must be >= 0, got " + instanceCount);
        }
        if (baseVertexCount == 0 || instanceCount == 0) return;

        if (mode == CgInstancedDrawMode.INDEXED_QUADS) {
            if ((baseVertexCount % 4) != 0) {
                throw new IllegalArgumentException(
                        "INDEXED_QUADS requires base vertex count to be a multiple of 4, got " + baseVertexCount);
            }
        } else if (mode == CgInstancedDrawMode.ARRAY_TRIANGLES) {
            if ((baseVertexCount % 3) != 0) {
                throw new IllegalArgumentException(
                        "ARRAY_TRIANGLES requires base vertex count to be a multiple of 3, got " + baseVertexCount);
            }
        }
    }

    private static void drawArraysInstanced(int mode, int first, int count, int instanceCount) {
        if (GLContext.getCapabilities().OpenGL31) {
            GL31.glDrawArraysInstanced(mode, first, count, instanceCount);
        } else {
            ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, instanceCount);
        }
    }

    private static void drawElementsInstanced(int mode, int count, int type, long offset, int instanceCount) {
        if (GLContext.getCapabilities().OpenGL31) {
            GL31.glDrawElementsInstanced(mode, count, type, offset, instanceCount);
        } else {
            ARBDrawInstanced.glDrawElementsInstancedARB(mode, count, type, offset, instanceCount);
        }
    }
}
