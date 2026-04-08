package io.github.somehussar.crystalgraphics.text.atlas;

import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgGlyphAtlas} LRU eviction logic and atlas region math.
 *
 * <p>All tests use {@code CgGlyphAtlas.createForTest()} which skips GL calls,
 * allowing headless execution without an OpenGL context.</p>
 */
public class CgGlyphAtlasTest {

    private static final CgFontKey FONT = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 16);

    @After
    public void cleanUp() {
        CgGlyphAtlas.freeAll();
    }

    // ── LRU eviction ───────────────────────────────────────────────────

    /**
     * QA Scenario from plan: 32×32 atlas with 16×16 glyphs (4 slots).
     * Insert 4 glyphs at frame 0. Advance to frame 5, access glyphs 0 and 1.
     * Insert glyph 4 — must evict glyph 2 or 3 (not 0 or 1).
     */
    @Test
    public void testLruEviction_coldestSlotEvicted() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);

        CgGlyphKey[] keys = new CgGlyphKey[5];
        for (int i = 0; i < 5; i++) {
            keys[i] = new CgGlyphKey(FONT, i, false);
        }

        byte[] pixels = new byte[16 * 16];

        // Insert 4 glyphs at frame 0 — fills the 32×32 atlas
        for (int i = 0; i < 4; i++) {
            CgAtlasRegion r = atlas.getOrAllocate(keys[i], pixels, 16, 16, 0, 0, 16, 16, 0);
            assertNotNull("Glyph " + i + " should fit", r);
        }
        assertEquals(4, atlas.getSlotCount());

        // Advance to frame 5 and access glyphs 0 and 1 (updating their LRU)
        atlas.getOrAllocate(keys[0], pixels, 16, 16, 0, 0, 16, 16, 5);
        atlas.getOrAllocate(keys[1], pixels, 16, 16, 0, 0, 16, 16, 5);

        // Insert glyph 4 — must evict the coldest (frame 0 = glyph 2 or 3)
        CgAtlasRegion evictionResult = atlas.getOrAllocate(keys[4], pixels, 16, 16, 0, 0, 16, 16, 6);
        assertNotNull("Glyph 4 should fit after eviction", evictionResult);

        // Glyphs 0 and 1 must still be present (they were touched at frame 5)
        assertTrue("Glyph 0 should survive eviction", atlas.containsKey(keys[0]));
        assertTrue("Glyph 1 should survive eviction", atlas.containsKey(keys[1]));

        // One of glyphs 2 or 3 must have been evicted
        boolean glyph2Evicted = !atlas.containsKey(keys[2]);
        boolean glyph3Evicted = !atlas.containsKey(keys[3]);
        assertTrue("Either glyph 2 or 3 must be evicted", glyph2Evicted || glyph3Evicted);

        // Glyph 4 is now present
        assertTrue("Glyph 4 should be present", atlas.containsKey(keys[4]));
    }

    /**
     * LRU eviction with mixed access patterns: access first 50%, then trigger
     * eviction. Evicted slots must come from the un-accessed 50%.
     */
    @Test
    public void testLruEviction_unaccessed50PercentEvictedFirst() {
        // 64×64 atlas with 16×16 glyphs = 16 slots
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);

        int totalSlots = 16;
        CgGlyphKey[] keys = new CgGlyphKey[totalSlots + 1];
        for (int i = 0; i <= totalSlots; i++) {
            keys[i] = new CgGlyphKey(FONT, i, false);
        }

        byte[] pixels = new byte[16 * 16];

        // Fill all 16 slots at frame 0
        for (int i = 0; i < totalSlots; i++) {
            assertNotNull(atlas.getOrAllocate(keys[i], pixels, 16, 16, 0, 0, 16, 16, 0));
        }

        // Access first 8 glyphs at frame 10
        for (int i = 0; i < 8; i++) {
            atlas.getOrAllocate(keys[i], pixels, 16, 16, 0, 0, 16, 16, 10);
        }

        // Insert glyph 16 — must evict from glyphs 8-15
        CgAtlasRegion result = atlas.getOrAllocate(keys[totalSlots], pixels, 16, 16, 0, 0, 16, 16, 11);
        assertNotNull("Should succeed after eviction", result);

        // All accessed glyphs (0-7) must survive
        for (int i = 0; i < 8; i++) {
            assertTrue("Glyph " + i + " should survive", atlas.containsKey(keys[i]));
        }

        // At least one of glyphs 8-15 must be evicted
        int evictedCount = 0;
        for (int i = 8; i < totalSlots; i++) {
            if (!atlas.containsKey(keys[i])) {
                evictedCount++;
            }
        }
        assertTrue("At least one un-accessed glyph should be evicted", evictedCount >= 1);
    }

    // ── Region correctness ─────────────────────────────────────────────

    @Test
    public void testRegion_uvSanity() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(512, 512, CgGlyphAtlas.Type.BITMAP);
        CgGlyphKey key = new CgGlyphKey(FONT, 65, false);
        byte[] pixels = new byte[16 * 16];

        CgAtlasRegion region = atlas.getOrAllocate(key, pixels, 16, 16, 1.5f, 10.0f, 16, 16, 0);
        assertNotNull(region);

        assertEquals(16, region.getWidth());
        assertEquals(16, region.getHeight());
        assertTrue("atlasX >= 0", region.getAtlasX() >= 0);
        assertTrue("atlasY >= 0", region.getAtlasY() >= 0);
        assertTrue("u0 >= 0", region.getU0() >= 0.0f);
        assertTrue("v0 >= 0", region.getV0() >= 0.0f);
        assertTrue("u1 <= 1", region.getU1() <= 1.0f);
        assertTrue("v1 <= 1", region.getV1() <= 1.0f);
        assertTrue("u1 > u0", region.getU1() > region.getU0());
        assertTrue("v1 > v0", region.getV1() > region.getV0());

        assertEquals(1.5f, region.getBearingX(), 0.001f);
        assertEquals(10.0f, region.getBearingY(), 0.001f);
        assertEquals(key, region.getKey());
    }

    @Test
    public void testRegion_uvValues_correctForPosition() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.BITMAP);
        CgGlyphKey key = new CgGlyphKey(FONT, 1, false);
        byte[] pixels = new byte[32 * 32];

        CgAtlasRegion region = atlas.getOrAllocate(key, pixels, 32, 32, 0, 0, 32, 32, 0);
        assertNotNull(region);

        // First rect should be at (0,0)
        assertEquals(0, region.getAtlasX());
        assertEquals(0, region.getAtlasY());
        assertEquals(0.0f, region.getU0(), 0.0001f);
        assertEquals(0.0f, region.getV0(), 0.0001f);
        assertEquals(32.0f / 256, region.getU1(), 0.0001f);
        assertEquals(32.0f / 256, region.getV1(), 0.0001f);
    }

    // ── Cache hit returns same region ──────────────────────────────────

    @Test
    public void testCacheHit_returnsSameRegion_updatesFrame() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(128, 128, CgGlyphAtlas.Type.BITMAP);
        CgGlyphKey key = new CgGlyphKey(FONT, 42, false);
        byte[] pixels = new byte[8 * 8];

        CgAtlasRegion first = atlas.getOrAllocate(key, pixels, 8, 8, 0, 0, 8, 8, 0);
        CgAtlasRegion second = atlas.getOrAllocate(key, pixels, 8, 8, 0, 0, 8, 8, 5);

        assertSame("Cache hit should return same region object", first, second);
        assertEquals(5, atlas.getLastUsedFrame(key));
    }

    // ── Deleted state ──────────────────────────────────────────────────

    @Test
    public void testDelete_idempotent() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        assertFalse(atlas.isDeleted());
        atlas.delete();
        assertTrue(atlas.isDeleted());
        atlas.delete(); // second call is no-op
        assertTrue(atlas.isDeleted());
    }

    @Test(expected = IllegalStateException.class)
    public void testGetOrAllocate_afterDelete_throws() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        atlas.delete();
        atlas.getOrAllocate(new CgGlyphKey(FONT, 0, false), new byte[1], 1, 1, 0, 0, 1, 1, 0);
    }

    // ── Ownership tracking ─────────────────────────────────────────────

    @Test
    public void testOwnership_trackedInAllOwned() {
        int before = CgGlyphAtlas.ALL_OWNED.size();
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        assertEquals(before + 1, CgGlyphAtlas.ALL_OWNED.size());
        atlas.delete();
        assertEquals(before, CgGlyphAtlas.ALL_OWNED.size());
    }

    @Test
    public void testIsOwned_alwaysTrue() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(64, 64, CgGlyphAtlas.Type.BITMAP);
        assertTrue(atlas.isOwned());
    }

    // ── MSDF path ──────────────────────────────────────────────────────

    @Test
    public void testMsdfAtlas_getOrAllocateMsdf() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);
        CgGlyphKey key = new CgGlyphKey(FONT, 65, true);
        float[] msdfData = new float[32 * 32 * 3];

        CgAtlasRegion region = atlas.getOrAllocateMsdf(key, msdfData, 32, 32, 2.0f, 12.0f, 32, 32, 0);
        assertNotNull(region);
        assertEquals(32, region.getWidth());
        assertEquals(32, region.getHeight());
        assertTrue("u1 > u0", region.getU1() > region.getU0());
        assertTrue("v1 > v0", region.getV1() > region.getV0());
        assertEquals(2.0f, region.getBearingX(), 0.001f);
        assertEquals(12.0f, region.getBearingY(), 0.001f);
    }

    // ── Sub-pixel bucket distinction ───────────────────────────────────

    @Test
    public void testSubPixelBucket_distinctKeysGetSeparateSlots() {
        CgFontKey smallFont = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 12);
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(128, 128, CgGlyphAtlas.Type.BITMAP);

        CgGlyphKey bucket0 = new CgGlyphKey(smallFont, 65, false, 0);
        CgGlyphKey bucket1 = new CgGlyphKey(smallFont, 65, false, 1);

        byte[] pixels = new byte[8 * 8];
        CgAtlasRegion r0 = atlas.getOrAllocate(bucket0, pixels, 8, 8, 0, 0, 8, 8, 0);
        CgAtlasRegion r1 = atlas.getOrAllocate(bucket1, pixels, 8, 8, 0, 0, 8, 8, 0);

        assertNotNull(r0);
        assertNotNull(r1);
        assertNotEquals("Different sub-pixel buckets should occupy different slots",
                r0.getAtlasX() + "," + r0.getAtlasY(),
                r1.getAtlasX() + "," + r1.getAtlasY());
        assertEquals(2, atlas.getSlotCount());
    }

    // ── FreeAll ────────────────────────────────────────────────────────

    @Test
    public void testFreeAll_deletesAllTracked() {
        CgGlyphAtlas a1 = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.BITMAP);
        CgGlyphAtlas a2 = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.MSDF);
        CgGlyphAtlas a3 = CgGlyphAtlas.createForTest(32, 32, CgGlyphAtlas.Type.MTSDF);
        assertFalse(a1.isDeleted());
        assertFalse(a2.isDeleted());
        assertFalse(a3.isDeleted());

        CgGlyphAtlas.freeAll();

        assertTrue(a1.isDeleted());
        assertTrue(a2.isDeleted());
        assertTrue(a3.isDeleted());
        assertEquals(0, CgGlyphAtlas.ALL_OWNED.size());
    }

    // ── MTSDF path ─────────────────────────────────────────────────────

    @Test
    public void testMtsdfAtlas_getOrAllocateMsdf_fourChannelData() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MTSDF);
        assertEquals(CgGlyphAtlas.Type.MTSDF, atlas.getType());

        CgGlyphKey key = new CgGlyphKey(FONT, 65, true);
        float[] mtsdfData = new float[32 * 32 * 4];

        CgAtlasRegion region = atlas.getOrAllocateMsdf(key, mtsdfData, 32, 32, 2.0f, 12.0f, 32, 32, 0);
        assertNotNull(region);
        assertEquals(32, region.getWidth());
        assertEquals(32, region.getHeight());
        assertTrue("u1 > u0", region.getU1() > region.getU0());
        assertTrue("v1 > v0", region.getV1() > region.getV0());
        assertEquals(2.0f, region.getBearingX(), 0.001f);
        assertEquals(12.0f, region.getBearingY(), 0.001f);
    }

    @Test
    public void testMtsdfAtlas_typeReportedCorrectly() {
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(128, 128, CgGlyphAtlas.Type.MTSDF);
        assertEquals(CgGlyphAtlas.Type.MTSDF, atlas.getType());
        assertNotEquals(CgGlyphAtlas.Type.MSDF, atlas.getType());
        assertNotEquals(CgGlyphAtlas.Type.BITMAP, atlas.getType());
    }
}
