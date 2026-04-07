package io.github.somehussar.crystalgraphics.api.font;

import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link CgGlyphPlacement} — the renderer-facing placement model
 * for multi-page atlas support.
 */
public class CgGlyphPlacementTest {

    private static final CgFontKey FONT_KEY = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 32);

    // ── Constructor and validation ─────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullKey() {
        new CgGlyphPlacement(null, 0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10,
                0, 0, 10, 10,
                0, 0, 1, 1,
                0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativePageIndex() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        new CgGlyphPlacement(key, -1, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10,
                0, 0, 10, 10,
                0, 0, 1, 1,
                0.0f);
    }

    @Test
    public void constructorStoresAllFields() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, true);
        CgGlyphPlacement p = new CgGlyphPlacement(key,
                2, 42,
                CgGlyphAtlas.Type.MSDF,
                1.5f, -0.5f, 11.5f, 9.5f,
                10, 20, 30, 40,
                0.1f, 0.2f, 0.3f, 0.4f,
                4.0f);

        assertSame(key, p.getKey());
        assertEquals(2, p.getPageIndex());
        assertEquals(42, p.getPageTextureId());
        assertEquals(CgGlyphAtlas.Type.MSDF, p.getAtlasType());
        assertEquals(1.5f, p.getPlaneLeft(), 0.0f);
        assertEquals(-0.5f, p.getPlaneBottom(), 0.0f);
        assertEquals(11.5f, p.getPlaneRight(), 0.0f);
        assertEquals(9.5f, p.getPlaneTop(), 0.0f);
        assertEquals(10, p.getAtlasLeft());
        assertEquals(20, p.getAtlasBottom());
        assertEquals(30, p.getAtlasRight());
        assertEquals(40, p.getAtlasTop());
        assertEquals(0.1f, p.getU0(), 0.0f);
        assertEquals(0.2f, p.getV0(), 0.0f);
        assertEquals(0.3f, p.getU1(), 0.0f);
        assertEquals(0.4f, p.getV1(), 0.0f);
        assertEquals(4.0f, p.getPxRange(), 0.0f);
    }

    // ── Derived geometry queries ───────────────────────────────────────

    @Test
    public void planeWidthAndHeightFromBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement p = new CgGlyphPlacement(key,
                0, 1,
                CgGlyphAtlas.Type.BITMAP,
                2.0f, 5.0f, 12.0f, 15.0f,
                0, 0, 10, 10,
                0, 0, 1, 1,
                0.0f);

        assertEquals(10.0f, p.getPlaneWidth(), 0.0001f);
        assertEquals(10.0f, p.getPlaneHeight(), 0.0001f);
    }

    @Test
    public void hasGeometryTrueForNonZeroBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement p = new CgGlyphPlacement(key,
                0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10,
                0, 0, 10, 10,
                0, 0, 1, 1,
                0.0f);
        assertTrue(p.hasGeometry());
    }

    @Test
    public void hasGeometryFalseForZeroWidthBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement p = new CgGlyphPlacement(key,
                0, 1,
                CgGlyphAtlas.Type.BITMAP,
                5.0f, 0, 5.0f, 10,
                0, 0, 0, 10,
                0, 0, 0, 1,
                0.0f);
        assertFalse(p.hasGeometry());
    }

    @Test
    public void distanceFieldFlagsFollowAtlasType() {
        CgGlyphKey msdfKey = new CgGlyphKey(FONT_KEY, 65, true);
        CgGlyphKey bmpKey = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphKey mtsdfKey = new CgGlyphKey(FONT_KEY, 66, true);

        CgGlyphPlacement msdfP = new CgGlyphPlacement(msdfKey, 0, 1,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 4.0f);
        CgGlyphPlacement bmpP = new CgGlyphPlacement(bmpKey, 0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement mtsdfP = new CgGlyphPlacement(mtsdfKey, 0, 1,
                CgGlyphAtlas.Type.MTSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 4.0f);

        assertTrue(msdfP.isMsdf());
        assertFalse(bmpP.isMsdf());
        assertTrue(msdfP.isDistanceField());
        assertFalse(bmpP.isDistanceField());
        assertTrue(mtsdfP.isMtsdf());
        assertTrue(mtsdfP.isDistanceField());
        assertFalse(mtsdfP.isMsdf());
    }

    // ── fromAtlasRegion bridge ─────────────────────────────────────────

    @Test
    public void fromAtlasRegionBitmapUsesMetricsForPlaneBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgAtlasRegion region = new CgAtlasRegion(
                10, 20,      // atlasX, atlasY
                16, 18,      // width, height
                0.1f, 0.2f, 0.3f, 0.4f,  // UVs
                key,
                3.0f, 14.0f, // bearingX, bearingY
                12.0f, 15.0f // metricsWidth, metricsHeight
        );

        CgGlyphPlacement p = CgGlyphPlacement.fromAtlasRegion(region, 42, CgGlyphAtlas.Type.BITMAP, 0.0f);

        assertEquals(key, p.getKey());
        assertEquals(0, p.getPageIndex());
        assertEquals(42, p.getPageTextureId());
        // For bitmap: planeLeft=bearingX, planeTop=bearingY
        assertEquals(3.0f, p.getPlaneLeft(), 0.0001f);
        assertEquals(14.0f, p.getPlaneTop(), 0.0001f);
        // planeRight = bearingX + metricsWidth
        assertEquals(15.0f, p.getPlaneRight(), 0.0001f);
        // planeBottom = bearingY - metricsHeight
        assertEquals(-1.0f, p.getPlaneBottom(), 0.0001f);
        // Plane width/height should match metricsWidth/Height for bitmap
        assertEquals(12.0f, p.getPlaneWidth(), 0.0001f);
        assertEquals(15.0f, p.getPlaneHeight(), 0.0001f);
        assertEquals(0.0f, p.getPxRange(), 0.0f);
    }

    @Test
    public void fromAtlasRegionMsdfUsesFullCellForPlaneBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, true);
        CgAtlasRegion region = new CgAtlasRegion(
                10, 20,      // atlasX, atlasY
                32, 32,      // width, height (full MSDF cell)
                0.1f, 0.2f, 0.3f, 0.4f,  // UVs
                key,
                -6.0f, 28.0f, // bearingX, bearingY
                20.0f, 24.0f // metricsWidth, metricsHeight
        );

        CgGlyphPlacement p = CgGlyphPlacement.fromAtlasRegion(region, 99, CgGlyphAtlas.Type.MSDF, 4.0f);

        // For MSDF: plane bounds use full cell width/height (includes SDF border)
        assertEquals(-6.0f, p.getPlaneLeft(), 0.0001f);
        assertEquals(28.0f, p.getPlaneTop(), 0.0001f);
        // planeRight = bearingX + width (full cell)
        assertEquals(26.0f, p.getPlaneRight(), 0.0001f);
        // planeBottom = bearingY - height (full cell)
        assertEquals(-4.0f, p.getPlaneBottom(), 0.0001f);
        // Plane width/height = full cell dimensions
        assertEquals(32.0f, p.getPlaneWidth(), 0.0001f);
        assertEquals(32.0f, p.getPlaneHeight(), 0.0001f);
        assertEquals(4.0f, p.getPxRange(), 0.0f);
    }

    @Test
    public void fromAtlasRegionPreservesUVs() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgAtlasRegion region = new CgAtlasRegion(
                0, 0, 10, 10,
                0.25f, 0.5f, 0.75f, 1.0f,
                key,
                0, 0, 10, 10
        );

        CgGlyphPlacement p = CgGlyphPlacement.fromAtlasRegion(region, 1, CgGlyphAtlas.Type.BITMAP, 0.0f);

        assertEquals(0.25f, p.getU0(), 0.0f);
        assertEquals(0.5f, p.getV0(), 0.0f);
        assertEquals(0.75f, p.getU1(), 0.0f);
        assertEquals(1.0f, p.getV1(), 0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromAtlasRegionRejectsNull() {
        CgGlyphPlacement.fromAtlasRegion(null, 1, CgGlyphAtlas.Type.BITMAP, 0.0f);
    }

    // ── Equality and hashCode ──────────────────────────────────────────

    @Test
    public void equalityForIdenticalPlacements() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void inequalityForDifferentPages() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 0, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 1, 2,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        assertNotEquals(a, b);
    }

    @Test
    public void inequalityForDifferentPxRange() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, true);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 0, 1,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 4.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 0, 1,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 8.0f);
        assertNotEquals(a, b);
    }
}
