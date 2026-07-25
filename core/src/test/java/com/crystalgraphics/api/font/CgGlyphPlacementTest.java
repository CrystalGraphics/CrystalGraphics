package com.crystalgraphics.api.font;

import com.crystalgraphics.text.atlas.CgGlyphAtlas;

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
        new CgGlyphPlacement(null, 1, 0,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10,
                0, 0, 10, 10,
                0, 0, 1, 1,
                0.0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativePageIndex() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        new CgGlyphPlacement(key, 1, -1,
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
                42, 2,
                CgGlyphAtlas.Type.MSDF,
                1.5f, -0.5f, 11.5f, 9.5f,
                10, 20, 30, 40,
                0.1f, 0.2f, 0.3f, 0.4f,
                4.0f);

        assertSame(key, p.key());
        assertEquals(2, p.atlasPageIndex());
        assertEquals(42, p.atlasTextureId());
        assertEquals(CgGlyphAtlas.Type.MSDF, p.atlasType());
        assertEquals(1.5f, p.planeLeft(), 0.0f);
        assertEquals(-0.5f, p.planeBottom(), 0.0f);
        assertEquals(11.5f, p.planeRight(), 0.0f);
        assertEquals(9.5f, p.planeTop(), 0.0f);
        assertEquals(10, p.atlasLeft());
        assertEquals(20, p.atlasBottom());
        assertEquals(30, p.atlasRight());
        assertEquals(40, p.atlasTop());
        assertEquals(0.1f, p.u0(), 0.0f);
        assertEquals(0.2f, p.v0(), 0.0f);
        assertEquals(0.3f, p.u1(), 0.0f);
        assertEquals(0.4f, p.v1(), 0.0f);
        assertEquals(4.0f, p.pxRange(), 0.0f);
    }

    // ── Derived geometry queries ───────────────────────────────────────

    @Test
    public void planeWidthAndHeightFromBounds() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement p = new CgGlyphPlacement(key,
                1, 0,
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
                1, 0,
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
                1, 0,
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

        CgGlyphPlacement msdfP = new CgGlyphPlacement(msdfKey, 1, 0,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 4.0f);
        CgGlyphPlacement bmpP = new CgGlyphPlacement(bmpKey, 1, 0,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement mtsdfP = new CgGlyphPlacement(mtsdfKey, 1, 0,
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

    // ── Equality and hashCode ──────────────────────────────────────────

    @Test
    public void equalityForIdenticalPlacements() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 1, 0,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 1, 0,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void inequalityForDifferentPages() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, false);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 1, 0,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 2, 1,
                CgGlyphAtlas.Type.BITMAP,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 0.0f);
        assertNotEquals(a, b);
    }

    @Test
    public void inequalityForDifferentPxRange() {
        CgGlyphKey key = new CgGlyphKey(FONT_KEY, 65, true);
        CgGlyphPlacement a = new CgGlyphPlacement(key, 1, 0,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 4.0f);
        CgGlyphPlacement b = new CgGlyphPlacement(key, 1, 0,
                CgGlyphAtlas.Type.MSDF,
                0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 1, 1, 8.0f);
        assertNotEquals(a, b);
    }
}
