package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgGlyphAtlas}.
 *
 * <p>Validates multi-page atlas management: page allocation on overflow,
 * stable glyph placement, cross-page lookup, and page count growth.</p>
 */
public class CgGlyphAtlasTest {

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
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

        assertEquals(0, atlas.getPageCount());

        CgGlyphPlacement p = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                2.0f, 14.0f, 16.0f, 16.0f, 1);

        assertNotNull(p);
        assertEquals(1, atlas.getPageCount());
        assertEquals(0, p.atlasPageIndex());
    }

    @Test
    public void testOverflow_createsNewPage() {
        // Use a tiny 32x32 page so it fills quickly
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        // Fill the first page: 32x32 can hold one 32x32 rect
        CgGlyphPlacement p1 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(32, 32), 32, 32,
                0, 32, 32, 32, 1);
        assertNotNull(p1);
        assertEquals(1, atlas.getPageCount());
        assertEquals(0, p1.atlasPageIndex());

        // This should overflow to page 1
        CgGlyphPlacement p2 = atlas.allocateBitmap(
                bitmapKey(2), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 2);
        assertNotNull(p2);
        assertEquals(2, atlas.getPageCount());
        assertEquals(1, p2.atlasPageIndex());
    }

    @Test
    public void testStablePlacement_afterPageGrowth() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

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
        assertEquals(p1.atlasPageIndex(), p1Again.atlasPageIndex());
        assertEquals(p1.u0(), p1Again.u0(), 0.0001f);
        assertEquals(p1.v0(), p1Again.v0(), 0.0001f);
    }

    @Test
    public void testDuplicateKey_returnsCached() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

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
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);

        CgGlyphPlacement p = atlas.allocateMsdf(
                msdfKey(1), dummyMsdf(32, 32), 32, 32,
                -5.0f, 28.0f,
                -5.0f, 28.0f - 32.0f, -5.0f + 32.0f, 28.0f,
                20.0f, 25.0f, 4.0f, 1);

        assertNotNull(p);
        assertEquals(4.0f, p.pxRange(), 0.001f);
        assertTrue(p.isMsdf());
    }

    @Test
    public void testMultiplePages_distinctTextureIds() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

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
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
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
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        atlas.delete();
        atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(16, 16), 16, 16,
                0, 16, 16, 16, 1);
    }

    @Test
    public void testNoEviction_pagingInstead() {
        // Verify that paged atlas never evicts — it always creates new pages
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

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
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);

        CgGlyphPlacement p = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(20, 30), 20, 30,
                3.0f, 28.0f, 18.0f, 26.0f, 1);

        assertNotNull(p);
        // For bitmap: plane bounds use metrics width/height
        assertEquals(3.0f, p.planeLeft(), 0.001f);
        assertEquals(28.0f, p.planeTop(), 0.001f);
        assertEquals(3.0f + 18.0f, p.planeRight(), 0.001f); // bearingX + metricsWidth
        assertEquals(28.0f - 26.0f, p.planeBottom(), 0.001f); // bearingY - metricsHeight
    }

    @Test
    public void testPageBudget_evictsColdestPageOnOverflow() {
        // 32x32 pages hold exactly one 32x32 glyph each; budget of 2 pages.
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(
                32, 32, CgGlyphAtlas.Type.BITMAP, CgGlyphAtlas.MAX_RECTS_FACTORY, 2);

        CgGlyphPlacement p0 = atlas.allocateBitmap(
                bitmapKey(0), dummyBitmap(32, 32), 32, 32, 0, 32, 32, 32, /*frame*/ 1);
        CgGlyphPlacement p1 = atlas.allocateBitmap(
                bitmapKey(1), dummyBitmap(32, 32), 32, 32, 0, 32, 32, 32, /*frame*/ 2);
        assertNotNull(p0);
        assertNotNull(p1);
        assertEquals(2, atlas.getPageCount());

        // Touch page 1 (glyph 1) at a later frame so page 0 (glyph 0) is coldest.
        atlas.get(bitmapKey(1), 5);

        // A third distinct glyph forces a new page — budget is full, so the
        // coldest page (page 0, holding glyph 0) must be evicted first.
        CgGlyphPlacement p2 = atlas.allocateBitmap(
                bitmapKey(2), dummyBitmap(32, 32), 32, 32, 0, 32, 32, 32, /*frame*/ 6);
        assertNotNull(p2);

        // Budget respected — never more than 2 pages alive at once.
        assertEquals(2, atlas.getPageCount());
        // Glyph 0 was on the evicted page — no longer findable.
        assertNull(atlas.get(bitmapKey(0), 7));
        // Glyphs 1 and 2 (on surviving/new pages) remain findable.
        assertNotNull(atlas.get(bitmapKey(1), 7));
        assertNotNull(atlas.get(bitmapKey(2), 7));
    }

    @Test
    public void testPageBudget_unboundedByDefaultForTest() {
        // createForTest's plain overloads must keep the historical unbounded
        // behavior that testNoEviction_pagingInstead relies on.
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);
        assertEquals(CgGlyphAtlas.UNBOUNDED_PAGES, atlas.getMaxPages());
    }

    @Test
    public void testPlacement_msdfPlaneBounds_usesFullBoxSize() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);

        CgGlyphPlacement p = atlas.allocateMsdf(
                msdfKey(1), dummyMsdf(36, 42), 36, 42,
                -5.0f, 35.0f,
                -5.0f, 35.0f - 42.0f, -5.0f + 36.0f, 35.0f,
                28.0f, 32.0f, 4.0f, 1);

        assertNotNull(p);
        // For MSDF: plane bounds use full box size (includes SDF range border)
        assertEquals(-5.0f, p.planeLeft(), 0.001f);
        assertEquals(35.0f, p.planeTop(), 0.001f);
        assertEquals(-5.0f + 36.0f, p.planeRight(), 0.001f); // bearingX + boxWidth
        assertEquals(35.0f - 42.0f, p.planeBottom(), 0.001f); // bearingY - boxHeight
    }
}
