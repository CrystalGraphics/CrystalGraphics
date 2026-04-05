package io.github.somehussar.crystalgraphics.gl.text;

/**
 * Immutable key for grouping glyph quads into draw batches.
 *
 * <p>The renderer collects glyph placements and must issue separate draw calls
 * whenever the GL state changes between glyphs. This key captures the three
 * dimensions that force a batch break:</p>
 * <ol>
 *   <li><strong>Atlas mode</strong> ({@code msdf}) — bitmap and MSDF glyphs use
 *       different shader programs, so they cannot share a draw call.</li>
 *   <li><strong>Page texture ID</strong> ({@code textureId}) — each atlas page
 *       is a separate GL texture. Glyphs on different pages require a texture
 *       rebind between batches.</li>
 *   <li><strong>Pixel range</strong> ({@code pxRange}) — for MSDF glyphs, the
 *       distance range used during SDF generation is uploaded as the
 *       {@code u_pxRange} shader uniform. If different pages or font-size
 *       buckets use different range values, they need separate batches.</li>
 * </ol>
 *
 * <h3>Ordering</h3>
 * <p>The key implements {@link Comparable} so batches can be sorted by mode
 * (bitmap first, then MSDF) for consistent draw order. Within the same mode,
 * batches are ordered by texture ID to minimize texture rebinds.</p>
 *
 * <h3>Usage</h3>
 * <p>During quad collection, the renderer creates a batch key for each glyph
 * placement. Consecutive glyphs with equal batch keys are merged into the
 * same batch. When the key changes, a new batch begins. After all quads are
 * collected, the renderer iterates batches in order, binding the appropriate
 * shader, texture, and uniforms for each.</p>
 *
 * @see CgDrawBatch
 */
public final class CgDrawBatchKey implements Comparable<CgDrawBatchKey> {

    /** Whether this batch uses MSDF rendering (true) or bitmap (false). */
    private final boolean msdf;

    /** GL texture ID of the atlas page bound for this batch. */
    private final int textureId;

    /**
     * SDF pixel range for MSDF batches. For bitmap batches this is 0.0f.
     * Used to set {@code u_pxRange} uniform per batch.
     */
    private final float pxRange;

    /**
     * Creates a batch key.
     *
     * @param msdf      true for MSDF batches, false for bitmap
     * @param textureId GL texture ID of the atlas page
     * @param pxRange   SDF pixel range (0 for bitmap)
     */
    public CgDrawBatchKey(boolean msdf, int textureId, float pxRange) {
        this.msdf = msdf;
        this.textureId = textureId;
        this.pxRange = pxRange;
    }

    public boolean isMsdf() { return msdf; }
    public int getTextureId() { return textureId; }
    public float getPxRange() { return pxRange; }

    /**
     * Sorts bitmap batches before MSDF, then by texture ID, then by pxRange.
     *
     * <p>This ordering matches the current two-pass draw order (bitmap first,
     * MSDF second) and minimizes texture rebinds within each pass by grouping
     * batches with the same texture consecutively.</p>
     */
    @Override
    public int compareTo(CgDrawBatchKey other) {
        // Bitmap (false=0) before MSDF (true=1)
        int cmp = Boolean.compare(this.msdf, other.msdf);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.textureId, other.textureId);
        if (cmp != 0) return cmp;
        return Float.compare(this.pxRange, other.pxRange);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgDrawBatchKey that = (CgDrawBatchKey) o;
        return msdf == that.msdf &&
                textureId == that.textureId &&
                Float.compare(that.pxRange, pxRange) == 0;
    }

    @Override
    public int hashCode() {
        int result = (msdf ? 1 : 0);
        result = 31 * result + textureId;
        result = 31 * result + Float.floatToIntBits(pxRange);
        return result;
    }

    @Override
    public String toString() {
        return "CgDrawBatchKey{" +
                "msdf=" + msdf +
                ", textureId=" + textureId +
                ", pxRange=" + pxRange +
                '}';
    }
}
