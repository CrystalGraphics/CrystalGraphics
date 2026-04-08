package io.github.somehussar.crystalgraphics.text.atlas;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;

import io.github.somehussar.crystalgraphics.text.atlas.CgPagedGlyphAtlas;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgPagedGlyphAtlas}.
 *
 * <p>Validates multi-page atlas management: page allocation on overflow,
 * stable glyph placement, cross-page lookup, and page count growth.</p>
 */
public class CgPagedGlyphAtlasTest {

    private static final CgFontKey FONT_KEY = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 48);

    private static CgGlyphKey bitmapKey(int glyphId) {
        return new CgGlyphKey(FONT_KEY, glyphId, false);
    }

    private static CgGlyphKey msdfKey(int glyphId) {
        return new CgGlyphKey(FONT_KEY, glyphId, true);
    }

    private static byte[] dummyBitmap(int w, int h) {
        return new byte[w * h];
    }

    private static float[] dummyMsdf(int w, int h) {
        return new float[w * h * 3];
    }

    @Test
    public void testFirstAllocation_createsOnePage() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

        assertEquals(0, atlas.getPageCount());

        CgGlyphPlacement p = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                2.0f, 14.0f, 16.0f, 16.0f, 1);

        assertNotNull(p);
        assertEquals(1, atlas.getPageCount());
        assertEquals(0, p.getPageIndex());
    }

    @Test
    public void testOverflow_createsNewPage() {
        // Use a tiny 32x32 page so it fills quickly
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        // Fill the first page: 32x32 can hold one 32x32 rect
        CgGlyphPlacement p1 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(32, 32), 32, 32,
                0, 32, 32, 32, 1);
        assertNotNull(p1);
        assertEquals(1, atlas.getPageCount());
        assertEquals(0, p1.getPageIndex());

        // This should overflow to page 1
        CgGlyphPlacement p2 = atlas.allocateBitmap(
                bitmapKey(2), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 2);
        assertNotNull(p2);
        assertEquals(2, atlas.getPageCount());
        assertEquals(1, p2.getPageIndex());
    }

    @Test
    public void testStablePlacement_afterPageGrowth() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        CgGlyphPlacement p1 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(32, 32), 32, 32,
                0, 32, 32, 32, 1);

        // Force page growth
        atlas.allocateBitmap(
                bitmapKey(2), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 2);

        // p1's placement should be unchanged (stable)
        CgGlyphPlacement p1Again = atlas.get(bitmapKey(1), 3);
        assertNotNull(p1Again);
        assertEquals(p1.getPageIndex(), p1Again.getPageIndex());
        assertEquals(p1.getU0(), p1Again.getU0(), 0.0001f);
        assertEquals(p1.getV0(), p1Again.getV0(), 0.0001f);
    }

    @Test
    public void testDuplicateKey_returnsCached() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

        CgGlyphPlacement p1 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                2, 14, 16, 16, 1);
        CgGlyphPlacement p2 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                2, 14, 16, 16, 2);

        // Same object returned for same key
        assertSame(p1, p2);
        assertEquals(1, atlas.getTotalSlotCount());
    }

    @Test
    public void testMsdfAllocation_withPxRange() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);

        CgGlyphPlacement p = atlas.allocateMsdf(
                msdfKey(1), dummyMsdf(32, 32), 32, 32,
                -5.0f, 28.0f,
                -5.0f, 28.0f - 32.0f, -5.0f + 32.0f, 28.0f,
                20.0f, 25.0f, 4.0f, 1);

        assertNotNull(p);
        assertEquals(4.0f, p.getPxRange(), 0.001f);
        assertTrue(p.isMsdf());
    }

    @Test
    public void testMultiplePages_distinctTextureIds() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        // Fill pages
        for (int i = 0; i < 5; i++) {
            atlas.allocateBitmap(
                    bitmapKey(i), dummyBitmap(32, 32), 32, 32,
                    0, 32, 32, 32, i);
        }

        assertEquals(5, atlas.getPageCount());
        // In test mode texture IDs are 0, but pages should have distinct indices
        for (int i = 0; i < atlas.getPageCount(); i++) {
            assertEquals(i, atlas.getPages().get(i).getPageIndex());
        }
    }

    @Test
    public void testDelete_clearsPages() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 1);

        assertFalse(atlas.isDeleted());
        atlas.delete();
        assertTrue(atlas.isDeleted());
        assertEquals(0, atlas.getPageCount());
    }

    @Test(expected = IllegalStateException.class)
    public void testAllocateAfterDelete_throws() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        atlas.delete();
        atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 1);
    }

    @Test
    public void testNoEviction_pagingInstead() {
        // Verify that paged atlas never evicts — it always creates new pages
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        // Allocate 10 glyphs that each fill an entire page
        for (int i = 0; i < 10; i++) {
            CgGlyphPlacement p = atlas.allocateBitmap(
                    bitmapKey(i), dummyBitmap(32, 32), 32, 32,
                    0, 32, 32, 32, i);
            assertNotNull("Glyph " + i + " should be allocated (new page)", p);
        }

        assertEquals(10, atlas.getPageCount());
        assertEquals(10, atlas.getTotalSlotCount());

        // All glyphs should still be findable
        for (int i = 0; i < 10; i++) {
            CgGlyphPlacement found = atlas.get(bitmapKey(i), 100);
            assertNotNull("Glyph " + i + " should still be present (no eviction)", found);
        }
    }

    @Test
    public void testPlacement_planeBounds() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

        CgGlyphPlacement p = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(20, 30), 20, 30,
                3.0f, 28.0f, 18.0f, 26.0f, 1);

        assertNotNull(p);
        // For bitmap: plane bounds use metrics width/height
        assertEquals(3.0f, p.getPlaneLeft(), 0.001f);
        assertEquals(28.0f, p.getPlaneTop(), 0.001f);
        assertEquals(3.0f + 18.0f, p.getPlaneRight(), 0.001f); // bearingX + metricsWidth
        assertEquals(28.0f - 26.0f, p.getPlaneBottom(), 0.001f); // bearingY - metricsHeight
    }

    @Test
    public void testPlacement_msdfPlaneBounds_usesFullBoxSize() {
        CgPagedGlyphAtlas atlas = CgPagedGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);

        CgGlyphPlacement p = atlas.allocateMsdf(
                msdfKey(1), dummyMsdf(36, 42), 36, 42,
                -5.0f, 35.0f,
                -5.0f, 35.0f - 42.0f, -5.0f + 36.0f, 35.0f,
                28.0f, 32.0f, 4.0f, 1);

        assertNotNull(p);
        // For MSDF: plane bounds use full box size (includes SDF range border)
        assertEquals(-5.0f, p.getPlaneLeft(), 0.001f);
        assertEquals(35.0f, p.getPlaneTop(), 0.001f);
        assertEquals(-5.0f + 36.0f, p.getPlaneRight(), 0.001f); // bearingX + boxWidth
        assertEquals(35.0f - 42.0f, p.getPlaneBottom(), 0.001f); // bearingY - boxHeight
    }
}
