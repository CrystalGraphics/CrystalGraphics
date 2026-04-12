package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArray;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayBinding;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry;

import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

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
 */
public final class CgBatchRenderer {

    private final CgVertexArrayBinding binding;
    private final CgStagingBuffer staging;
    private final CgVertexWriter writer;

    private boolean begun;

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

    public void begin() {
        if (begun) throw new IllegalStateException("CgBatchRenderer already begun");
        begun = true;
        staging.reset();
        staging.ensureRoomForNextVertex();
    }

    public void flush() {
        if (!begun || staging.isEmpty()) return;

        if ((staging.vertexCount() & 3) != 0) {
            throw new IllegalStateException("CgBatchRenderer.flush() requires complete quads; staged vertex count="
                    + staging.vertexCount());
        }

        int quadCount = staging.quadCount();
        int floatCount = staging.rawCursor();
        int byteCount = floatCount * Float.BYTES;

        ByteBuffer mapped = binding.getStreamBuffer().map(byteCount);
        mapped.asFloatBuffer().put(staging.rawData(), 0, floatCount);
        int dataOffset = binding.getStreamBuffer().commit(byteCount);

        // VAO must be bound BEFORE rebindPointers — glVertexAttribPointer
        // writes into the currently bound VAO state.
        binding.getVertexArray().bind();
        binding.rebindPointersIfNeeded(dataOffset);
        CgQuadIndexBuffer.get().bindAndEnsureCapacity(quadCount);

        GL11.glDrawElements(GL11.GL_TRIANGLES, quadCount * 6, GL11.GL_UNSIGNED_SHORT, 0L);

        staging.reset();
        staging.ensureRoomForNextVertex();
    }

    public void end() {
        flush();
        begun = false;
        CgVertexArray.bind(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public CgVertexWriter vertex() {
        if (!begun) throw new IllegalStateException("CgBatchRenderer not begun");
        writer.reset();
        return writer;
    }

    public boolean isDirty() {
        return begun && !staging.isEmpty();
    }

    public CgStagingBuffer staging() {
        return staging;
    }

    /** No-op: CPU staging only. Shared GPU resources are owned by the registry. */
    public void delete() {
    }
}
