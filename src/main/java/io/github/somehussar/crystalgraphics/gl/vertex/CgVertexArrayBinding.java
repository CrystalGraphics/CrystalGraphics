package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.Getter;

/**
 * Shared vertex input binding for a specific vertex format.
 * Owns the stream buffer and VAO used by all consumers of that format.
 *
 * <p>The {@link #generation} counter is incremented by {@link #delete()} so that
 * derived {@link CgInstancedVertexArrayBinding} instances can detect when their
 * parent binding has been destroyed and rebuilt.</p>
 */
public final class CgVertexArrayBinding {

    @Getter
    private final CgVertexFormat format;
    @Getter
    private final CgStreamBuffer streamBuffer;
    @Getter
    private final CgVertexArray vertexArray;
    private int currentDataOffset;

    /**
     * Incremented on {@link #delete()} so derived instanced bindings can detect
     * parent invalidation and avoid drawing stale GPU resources.
     */
    private int generation = 0;

    protected CgVertexArrayBinding(CgVertexFormat format, CgStreamBuffer streamBuffer, CgVertexArray vertexArray) {
        this.format = format;
        this.streamBuffer = streamBuffer;
        this.vertexArray = vertexArray;
    }

    /**
     * Returns a monotonically increasing generation counter.
     * Instanced bindings snapshot this value at creation time and call
     * {@link CgInstancedVertexArrayBinding#validateParentGeneration()} before each draw
     * to detect parent binding invalidation.
     */
    public int getGeneration() {
        return generation;
    }

    /**
     * After each stream buffer commit, the data may land at a new offset within the VBO.
     * VAO attribute pointers encode the offset, so we must re-issue glVertexAttribPointer
     * whenever the offset changes. Skipped when unchanged (common in orphan/subdata paths).
     */
    public void rebindPointersIfNeeded(int dataOffset) {
        if (dataOffset == currentDataOffset) {
            return;
        }
        streamBuffer.bind();
        vertexArray.reconfigureWithOffset(format, dataOffset);
        currentDataOffset = dataOffset;
    }

    public void delete() {
        generation++;
        vertexArray.delete();
        streamBuffer.delete();
    }
}
