package io.github.somehussar.crystalgraphics.api.font;

import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;

/**
 * Immutable placement record describing a glyph's location within a paged atlas.
 *
 * <p>This is the renderer-facing contract for glyph placement in the multi-page
 * atlas system, replacing the single-page assumptions of {@link CgAtlasRegion}.
 * It separates <strong>plane bounds</strong> (geometry-space quad extents) from
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
 * <p>Each placement carries a {@code pageIndex} and {@code pageTextureId} so the
 * renderer can group glyphs by atlas page for draw batching. The texture ID is
 * the GL texture name of the specific page this glyph resides on.</p>
 *
 * <h3>MSDF Configuration</h3>
 * <p>When the atlas mode is MSDF, the {@code pxRange} field carries the pixel
 * range used during SDF generation for this page/bucket. This allows the renderer
 * to set {@code u_pxRange} per batch when different pages or font sizes use
 * different range values, rather than treating it as a global constant.</p>
 *
 * <h3>Backward Compatibility</h3>
 * <p>{@link CgAtlasRegion} remains as the atlas-internal allocation record.
 * {@code CgGlyphPlacement} is the canonical renderer-facing contract. A static
 * factory {@link #fromAtlasRegion} bridges the two during the transition period
 * where single-page atlases still produce {@code CgAtlasRegion}.</p>
 *
 * @see CgAtlasRegion
 * @see CgGlyphKey
 */
public final class CgGlyphPlacement {

    // ── Glyph identity ─────────────────────────────────────────────────

    /** The glyph key this placement was allocated for. */
    private final CgGlyphKey key;

    // ── Page identity ──────────────────────────────────────────────────

    /** Zero-based page index within the paged atlas. */
    private final int pageIndex;

    /**
     * GL texture ID of the atlas page this glyph resides on.
     *
     * <p>This is resolved at placement time so the renderer does not need
     * to look up page handles during draw. A value of 0 indicates a
     * test-mode placement with no backing GL texture.</p>
     */
    private final int pageTextureId;

    private final CgGlyphAtlas.Type atlasType;

    // ── Plane bounds (physical raster space) ───────────────────────────

    /**
     * Left edge of the glyph quad in physical raster units, measured from
     * the pen origin. For bitmap glyphs this equals bearingX. For MSDF
     * glyphs this includes the SDF range padding to the left of the
     * visible glyph edge.
     */
    private final float planeLeft;

    /**
     * Bottom edge of the glyph quad in physical raster units, measured
     * from the baseline. Positive values extend below the baseline.
     * For MSDF glyphs this includes the SDF range padding below the
     * visible glyph edge.
     */
    private final float planeBottom;

    /**
     * Right edge of the glyph quad in physical raster units.
     * {@code planeRight - planeLeft} gives the full quad width including
     * any SDF padding.
     */
    private final float planeRight;

    /**
     * Top edge of the glyph quad in physical raster units, measured from
     * the baseline. Positive values extend above the baseline (the common
     * case for most glyphs). For MSDF glyphs this includes SDF range
     * padding above the visible glyph edge.
     */
    private final float planeTop;

    // ── Atlas bounds (page-local pixel coordinates) ────────────────────

    /** Left edge of the glyph region in the atlas page (pixels). */
    private final int atlasLeft;

    /** Bottom edge of the glyph region in the atlas page (pixels). */
    private final int atlasBottom;

    /** Right edge of the glyph region in the atlas page (pixels). */
    private final int atlasRight;

    /** Top edge of the glyph region in the atlas page (pixels). */
    private final int atlasTop;

    // ── Normalized UVs ─────────────────────────────────────────────────

    /** Normalized U coordinate of the left edge [0, 1]. */
    private final float u0;

    /** Normalized V coordinate of the top edge [0, 1]. */
    private final float v0;

    /** Normalized U coordinate of the right edge [0, 1]. */
    private final float u1;

    /** Normalized V coordinate of the bottom edge [0, 1]. */
    private final float v1;

    // ── MSDF page configuration ────────────────────────────────────────

    /**
     * SDF pixel range for this page/bucket, used as {@code u_pxRange} in
     * the MSDF fragment shader.
     *
     * <p>For bitmap placements this is 0.0f (unused). For MSDF placements
     * this carries the range value that was used during SDF generation so
     * the renderer can set the correct uniform per batch.</p>
     */
    private final float pxRange;

    // ── Constructor ────────────────────────────────────────────────────

    /**
     * Full constructor. Prefer the static factories for common construction
     * patterns.
     */
    public CgGlyphPlacement(CgGlyphKey key,
                            int pageIndex,
                            int pageTextureId,
                            CgGlyphAtlas.Type atlasType,
                            float planeLeft,
                            float planeBottom,
                            float planeRight,
                            float planeTop,
                            int atlasLeft,
                            int atlasBottom,
                            int atlasRight,
                            int atlasTop,
                            float u0,
                            float v0,
                            float u1,
                            float v1,
                            float pxRange) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (pageIndex < 0) {
            throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
        }
        if (atlasType == null) {
            throw new IllegalArgumentException("atlasType must not be null");
        }
        this.key = key;
        this.pageIndex = pageIndex;
        this.pageTextureId = pageTextureId;
        this.atlasType = atlasType;
        this.planeLeft = planeLeft;
        this.planeBottom = planeBottom;
        this.planeRight = planeRight;
        this.planeTop = planeTop;
        this.atlasLeft = atlasLeft;
        this.atlasBottom = atlasBottom;
        this.atlasRight = atlasRight;
        this.atlasTop = atlasTop;
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        this.pxRange = pxRange;
    }

    // ── Static factories ───────────────────────────────────────────────

    /**
     * Bridges a legacy {@link CgAtlasRegion} into a {@code CgGlyphPlacement}.
     *
     * <p>This factory is used during the transition period where single-page
     * atlases still produce {@code CgAtlasRegion}. It maps the old bearing/
     * metrics model into plane bounds and assumes page index 0.</p>
     *
     * <p>For <strong>bitmap</strong> regions, plane bounds are derived from
     * bearingX/bearingY and metricsWidth/metricsHeight (the logical-space
     * outline extents). For <strong>MSDF</strong> regions, plane bounds use
     * bearingX/bearingY and the full cell width/height (which includes the
     * SDF range border).</p>
     *
     * @param region      the legacy atlas region
     * @param textureId   GL texture ID of the atlas page
     * @param pxRange     SDF pixel range (0 for bitmap)
     * @return a placement record bridging the legacy region
     */
    public static CgGlyphPlacement fromAtlasRegion(CgAtlasRegion region,
                                                     int textureId,
                                                     CgGlyphAtlas.Type atlasType,
                                                     float pxRange) {
        if (region == null) {
            throw new IllegalArgumentException("region must not be null");
        }

        // Plane bounds: the quad extent in physical raster space.
        // For bitmap: use metricsWidth/metricsHeight (the visible glyph outline).
        // For MSDF: use the full cell width/height (includes SDF range border).
        float planeLeft = region.getBearingX();
        float planeTop = region.getBearingY();
        float quadWidth;
        float quadHeight;
        if (atlasType != CgGlyphAtlas.Type.BITMAP) {
            quadWidth = region.getWidth();
            quadHeight = region.getHeight();
        } else {
            quadWidth = region.getMetricsWidth();
            quadHeight = region.getMetricsHeight();
        }
        // planeRight = planeLeft + quadWidth
        // planeBottom = planeTop - quadHeight (bearing is above baseline, quad extends down)
        float planeRight = planeLeft + quadWidth;
        float planeBottom = planeTop - quadHeight;

        return new CgGlyphPlacement(
                region.getKey(),
                0,  // single-page legacy: page index 0
                textureId,
                atlasType,
                planeLeft,
                planeBottom,
                planeRight,
                planeTop,
                region.getAtlasX(),
                region.getAtlasY() + region.getHeight(),  // bottom = top + height in top-left-origin
                region.getAtlasX() + region.getWidth(),
                region.getAtlasY(),
                region.getU0(),
                region.getV0(),
                region.getU1(),
                region.getV1(),
                pxRange
        );
    }

    // ── Accessors ──────────────────────────────────────────────────────

    public CgGlyphKey getKey() { return key; }
    public int getPageIndex() { return pageIndex; }
    public int getPageTextureId() { return pageTextureId; }
    public CgGlyphAtlas.Type getAtlasType() { return atlasType; }
    public float getPlaneLeft() { return planeLeft; }
    public float getPlaneBottom() { return planeBottom; }
    public float getPlaneRight() { return planeRight; }
    public float getPlaneTop() { return planeTop; }
    public int getAtlasLeft() { return atlasLeft; }
    public int getAtlasBottom() { return atlasBottom; }
    public int getAtlasRight() { return atlasRight; }
    public int getAtlasTop() { return atlasTop; }
    public float getU0() { return u0; }
    public float getV0() { return v0; }
    public float getU1() { return u1; }
    public float getV1() { return v1; }
    public float getPxRange() { return pxRange; }

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

    @Override
    public String toString() {
        return "CgGlyphPlacement{" +
                "key=" + key +
                ", page=" + pageIndex +
                ", texId=" + pageTextureId +
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
        return pageIndex == that.pageIndex &&
                pageTextureId == that.pageTextureId &&
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
        result = 31 * result + pageIndex;
        result = 31 * result + pageTextureId;
        result = 31 * result + atlasType.hashCode();
        result = 31 * result + Float.floatToIntBits(planeLeft);
        result = 31 * result + Float.floatToIntBits(planeTop);
        result = 31 * result + Float.floatToIntBits(pxRange);
        return result;
    }
}
