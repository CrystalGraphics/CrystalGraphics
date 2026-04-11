package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import lombok.Getter;

/**
 * Shared vertex input binding for a specific vertex format.
 * Owns the stream buffer and VAO used by all consumers of that format.
 */
public final class CgVertexArrayBinding {

    @Getter
    private final CgVertexFormat format;
    @Getter
    private final CgStreamBuffer streamBuffer;
    @Getter
    private final CgVertexArray vertexArray;
    private int currentDataOffset;

    protected CgVertexArrayBinding(CgVertexFormat format, CgStreamBuffer streamBuffer, CgVertexArray vertexArray) {
        this.format = format;
        this.streamBuffer = streamBuffer;
        this.vertexArray = vertexArray;
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
        vertexArray.delete();
        streamBuffer.delete();
    }
}
