package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.Getter;

/**
 * Non-instanced VAO binding for a specific vertex format.
 *
 * <p>Owns the VAO only. The stream buffer (VBO) is <em>borrowed</em> from
 * {@link CgVertexBuffer} / {@link CgVertexBufferRegistry} and must not be deleted here.</p>
 */
public final class CgVertexArrayBinding {

    private final CgVertexBuffer baseStream;

    @Getter
    private final CgVertexArray vertexArray;
    private int currentDataOffset;

    CgVertexArrayBinding(CgVertexBuffer baseStream, CgVertexArray vertexArray) {
        this.baseStream = baseStream;
        this.vertexArray = vertexArray;
    }

    /**
     * Returns the vertex format of the borrowed base stream.
     */
    public CgVertexFormat getFormat() {
        return baseStream.getFormat();
    }

    /**
     * Returns the stream buffer borrowed from {@link CgVertexBuffer}.
     *
     * <p>Kept for backward compatibility. Do NOT call {@code delete()} on the returned buffer —
     * it is owned by {@link CgVertexBufferRegistry}.</p>
     */
    public CgStreamBuffer getStreamBuffer() {
        return baseStream.getStreamBuffer();
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
        baseStream.getStreamBuffer().bind();
        vertexArray.reconfigureWithOffset(baseStream.getFormat(), dataOffset);
        currentDataOffset = dataOffset;
    }

    public void delete() {
        vertexArray.delete();
    }
}
