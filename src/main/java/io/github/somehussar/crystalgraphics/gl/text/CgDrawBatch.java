package io.github.somehussar.crystalgraphics.gl.text;

/**
 * A contiguous range of glyph quads in the VBO that share the same draw state.
 *
 * <p>Each batch corresponds to one {@code glDrawElements} call. The renderer
 * fills the VBO with quads sorted by {@link CgDrawBatchKey} order (bitmap first,
 * then MSDF, sub-sorted by texture and pxRange). Each batch records its key
 * and the start/count of quads it covers in the VBO.</p>
 *
 * <h3>Index Math</h3>
 * <p>Given a batch with {@code startQuad = S} and {@code quadCount = N}:</p>
 * <ul>
 *   <li>IBO offset (bytes) = {@code S * 6 * 2} (6 indices per quad, 2 bytes per
 *       {@code GL_UNSIGNED_SHORT} index)</li>
 *   <li>Index count = {@code N * 6}</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * <p>Batches are transient — created fresh each frame during quad collection
 * and discarded after drawing. They hold no GL resources.</p>
 *
 * @see CgDrawBatchKey
 */
public final class CgDrawBatch {

    /** Indices per glyph quad (two triangles). */
    private static final int INDICES_PER_QUAD = 6;

    /** Bytes per index ({@code GL_UNSIGNED_SHORT}). */
    private static final int BYTES_PER_INDEX = 2;

    private final CgDrawBatchKey key;

    /**
     * Zero-based index of the first quad in this batch within the VBO.
     * Used to compute the IBO byte offset for {@code glDrawElements}.
     */
    private final int startQuad;

    /** Number of quads in this batch. */
    private final int quadCount;

    /**
     * Creates a draw batch.
     *
     * @param key       the batch key (shader mode, texture, pxRange)
     * @param startQuad zero-based index of the first quad in the VBO
     * @param quadCount number of quads in this batch
     * @throws IllegalArgumentException if key is null, startQuad < 0, or quadCount < 0
     */
    public CgDrawBatch(CgDrawBatchKey key, int startQuad, int quadCount) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (startQuad < 0) {
            throw new IllegalArgumentException("startQuad must be >= 0, got " + startQuad);
        }
        if (quadCount < 0) {
            throw new IllegalArgumentException("quadCount must be >= 0, got " + quadCount);
        }
        this.key = key;
        this.startQuad = startQuad;
        this.quadCount = quadCount;
    }

    public CgDrawBatchKey getKey() { return key; }
    public int getStartQuad() { return startQuad; }
    public int getQuadCount() { return quadCount; }

    /**
     * Returns the byte offset into the IBO for this batch's first index.
     * This is the {@code offset} parameter to {@code glDrawElements}.
     */
    public long getIboByteOffset() {
        return (long) startQuad * INDICES_PER_QUAD * BYTES_PER_INDEX;
    }

    /**
     * Returns the number of indices to draw for this batch.
     * This is the {@code count} parameter to {@code glDrawElements}.
     */
    public int getIndexCount() {
        return quadCount * INDICES_PER_QUAD;
    }

    /**
     * Returns whether this batch has any quads to draw.
     */
    public boolean isEmpty() {
        return quadCount == 0;
    }

    @Override
    public String toString() {
        return "CgDrawBatch{" +
                "key=" + key +
                ", startQuad=" + startQuad +
                ", quadCount=" + quadCount +
                '}';
    }
}
