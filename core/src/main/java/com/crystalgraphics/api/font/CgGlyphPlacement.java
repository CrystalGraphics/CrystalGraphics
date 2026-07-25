package com.crystalgraphics.api.font;

import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;

/**
 * Immutable placement record describing a glyph's location within a paged atlas.
 *
 * <p>This is the renderer-facing contract for glyph placement in the multi-page
 * atlas system. It separates <strong>plane bounds</strong> (geometry-space quad extents) from
 * <strong>atlas bounds</strong> (texture-space sample coordinates) and carries
 * per-page identity and MSDF configuration needed for correct draw batching.</p>
 *
 * <h3>Plane Bounds</h3>
 * <p>The plane bounds ({@code planeLeft}, {@code planeBottom}, {@code planeRight},
 * {@code planeTop}) define the glyph quad in the same coordinate space as pen
 * positions and layout advances. For bitmap glyphs these correspond to the
 * traditional bearing + metrics extents. For MSDF glyphs they include the SDF
 * range border — the renderer must draw the full plane bounds so that the
 * distance field extends beyond the visible glyph edge.</p>
 *
 * <p>Plane bounds are expressed in <strong>physical raster space</strong> and must
 * be normalized to logical space at the renderer boundary using the same
 * {@code baseTargetPx / effectiveTargetPx} scale factor as before.</p>
 *
 * <h3>Atlas Bounds and UVs</h3>
 * <p>The atlas bounds ({@code atlasLeft}, {@code atlasBottom}, {@code atlasRight},
 * {@code atlasTop}) are pixel coordinates within the atlas page. Normalized UVs
 * ({@code u0}, {@code v0}, {@code u1}, {@code v1}) are derived from these and
 * the page dimensions. The renderer uses UVs for texture sampling only.</p>
 *
 * <h3>Page Identity</h3>
 * <p>Each placement carries a {@code atlasPageIndex} and {@code atlasTextureId} so the
 * renderer can group glyphs by atlas page for draw batching. The texture ID is
 * the GL texture name of the specific page this glyph resides on.</p>
 *
 * <h3>MSDF Configuration</h3>
 * <p>When the atlas mode is MSDF, the {@code pxRange} field carries the pixel
 * range used during SDF generation for this page/bucket. This allows the renderer
 * to set {@code u_pxRange} per batch when different pages or font sizes use
 * different range values, rather than treating it as a global constant.</p>
 *
 * @param key            The glyph key this placement was allocated for.
 * @param atlasTextureId GL texture ID of the {@link CgTexture2DArray} atlas this glyph resides on.
 *
 *                       <p>This is resolved at placement time so the renderer does not need
 *                       to look up page handles during draw. A value of 0 indicates a
 *                       test-mode placement with no backing GL texture.</p>
 * @param atlasPageIndex      Zero-based page index within the paged atlas.
 * @param planeLeft      Left edge of the glyph quad in physical raster units, measured from
 *                       the pen origin. For bitmap glyphs this equals bearingX. For MSDF
 *                       glyphs this includes the SDF range padding to the left of the
 *                       visible glyph edge.
 * @param planeBottom    Bottom edge of the glyph quad in physical raster units, measured
 *                       from the baseline. Positive values extend below the baseline.
 *                       For MSDF glyphs this includes the SDF range padding below the
 *                       visible glyph edge.
 * @param planeRight     Right edge of the glyph quad in physical raster units.
 *                       {@code planeRight - planeLeft} gives the full quad width including
 *                       any SDF padding.
 * @param planeTop       Top edge of the glyph quad in physical raster units, measured from
 *                       the baseline. Positive values extend above the baseline (the common
 *                       case for most glyphs). For MSDF glyphs this includes SDF range
 *                       padding above the visible glyph edge.
 * @param atlasLeft      Left edge of the glyph region in the atlas page (pixels).
 * @param atlasBottom    Bottom edge of the glyph region in the atlas page (pixels).
 * @param atlasRight     Right edge of the glyph region in the atlas page (pixels).
 * @param atlasTop       Top edge of the glyph region in the atlas page (pixels).
 * @param u0             Normalized U coordinate of the left edge [0, 1].
 * @param v0             Normalized V coordinate of the top edge [0, 1].
 * @param u1             Normalized U coordinate of the right edge [0, 1].
 * @param v1             Normalized V coordinate of the bottom edge [0, 1].
 * @param pxRange        SDF pixel range for this page/bucket, used as {@code u_pxRange} in
 *                       the MSDF fragment shader.
 *
 *                       <p>For bitmap placements this is 0.0f (unused). For MSDF placements
 *                       this carries the range value that was used during SDF generation so
 *                       the renderer can set the correct uniform per batch.</p>
 * @see CgGlyphKey
 */
public record CgGlyphPlacement(CgGlyphKey key, int atlasTextureId, int atlasPageIndex, CgGlyphAtlas.Type atlasType,
                               float planeLeft, float planeBottom, float planeRight, float planeTop, int atlasLeft,
                               int atlasBottom, int atlasRight, int atlasTop, float u0, float v0, float u1, float v1,
                               float pxRange) {
    
    /**
     * Full constructor. Prefer the static factories for common construction
     * patterns.
     */
    public CgGlyphPlacement {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (atlasPageIndex < 0) throw new IllegalArgumentException("atlasPageIndex must be >= 0, got " + atlasPageIndex);
        if (atlasType == null) throw new IllegalArgumentException("atlasType must not be null");
        
    }

    /**
     * Returns whether this is an MSDF placement (delegates to the glyph key).
     */
    public boolean isMsdf() {
        return atlasType == CgGlyphAtlas.Type.MSDF;
    }

    public boolean isMtsdf() {
        return atlasType == CgGlyphAtlas.Type.MTSDF;
    }

    public boolean isDistanceField() {
        return atlasType != CgGlyphAtlas.Type.BITMAP;
    }
    
    // ── Derived geometry queries ───────────────────────────────────────

    /**
     * Returns the full quad width in physical raster units (plane bounds).
     * For MSDF placements this includes SDF range padding.
     */
    public float getPlaneWidth() {
        return planeRight - planeLeft;
    }

    /**
     * Returns the full quad height in physical raster units (plane bounds).
     * For MSDF placements this includes SDF range padding.
     */
    public float getPlaneHeight() {
        return planeTop - planeBottom;
    }

    /**
     * Returns whether this placement has non-zero geometry.
     * A placement with zero width and height represents a space or empty glyph.
     */
    public boolean hasGeometry() {
        return getPlaneWidth() > 0 && getPlaneHeight() > 0;
    }

    @Override
    public String toString() {
        return "CgGlyphPlacement{" +
                "key=" + key +
                ", page=" + atlasPageIndex +
                ", texId=" + atlasTextureId +
                ", atlasType=" + atlasType +
                ", plane=[" + planeLeft + "," + planeBottom + "," + planeRight + "," + planeTop + "]" +
                ", uv=[" + u0 + "," + v0 + "," + u1 + "," + v1 + "]" +
                ", pxRange=" + pxRange +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgGlyphPlacement that = (CgGlyphPlacement) o;
        return atlasPageIndex == that.atlasPageIndex &&
                atlasTextureId == that.atlasTextureId &&
                atlasType == that.atlasType &&
                Float.compare(that.planeLeft, planeLeft) == 0 &&
                Float.compare(that.planeBottom, planeBottom) == 0 &&
                Float.compare(that.planeRight, planeRight) == 0 &&
                Float.compare(that.planeTop, planeTop) == 0 &&
                atlasLeft == that.atlasLeft &&
                atlasBottom == that.atlasBottom &&
                atlasRight == that.atlasRight &&
                atlasTop == that.atlasTop &&
                Float.compare(that.u0, u0) == 0 &&
                Float.compare(that.v0, v0) == 0 &&
                Float.compare(that.u1, u1) == 0 &&
                Float.compare(that.v1, v1) == 0 &&
                Float.compare(that.pxRange, pxRange) == 0 &&
                key.equals(that.key);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + atlasPageIndex;
        result = 31 * result + atlasTextureId;
        result = 31 * result + atlasType.hashCode();
        result = 31 * result + Float.floatToIntBits(planeLeft);
        result = 31 * result + Float.floatToIntBits(planeTop);
        result = 31 * result + Float.floatToIntBits(pxRange);
        return result;
    }
}
