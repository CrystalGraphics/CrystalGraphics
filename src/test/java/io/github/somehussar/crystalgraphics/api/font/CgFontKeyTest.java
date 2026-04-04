package io.github.somehussar.crystalgraphics.api.font;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for font subsystem value types: {@link CgFontKey}, {@link CgGlyphKey},
 * {@link CgFontMetrics}, {@link CgGlyphMetrics}, and {@link CgFontStyle}.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Value equality and hash code contracts for {@code CgFontKey} and {@code CgGlyphKey}</li>
 *   <li>MSDF vs bitmap distinction in {@code CgGlyphKey}</li>
 *   <li>Field storage and retrieval for all value types</li>
 *   <li>Null and edge case handling</li>
 * </ul>
 */
public class CgFontKeyTest {

    // ---------------------------------------------------------------
    //  CgFontKey equality tests
    // ---------------------------------------------------------------

    /**
     * Two CgFontKey instances with the same fields must be equal.
     */
    @Test
    public void testFontKey_value_equality() {
        CgFontKey key1 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        CgFontKey key2 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);

        assertEquals("Same fields should be equal", key1, key2);
        assertEquals("Equal keys must have same hashCode", key1.hashCode(), key2.hashCode());
    }

    /**
     * CgFontKey with different fontPath must not be equal.
     */
    @Test
    public void testFontKey_different_path_not_equal() {
        CgFontKey key1 = new CgFontKey("font-a.ttf", CgFontStyle.REGULAR, 12);
        CgFontKey key2 = new CgFontKey("font-b.ttf", CgFontStyle.REGULAR, 12);

        assertNotEquals("Different paths should not be equal", key1, key2);
    }

    /**
     * CgFontKey with different style must not be equal.
     */
    @Test
    public void testFontKey_different_style_not_equal() {
        CgFontKey key1 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        CgFontKey key2 = new CgFontKey("font.ttf", CgFontStyle.BOLD, 12);

        assertNotEquals("Different styles should not be equal", key1, key2);
    }

    /**
     * CgFontKey with different targetPx must not be equal.
     */
    @Test
    public void testFontKey_different_size_not_equal() {
        CgFontKey key1 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        CgFontKey key2 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 48);

        assertNotEquals("Different sizes should not be equal", key1, key2);
    }

    /**
     * CgFontKey getter methods return stored values.
     */
    @Test
    public void testFontKey_getters() {
        CgFontKey key = new CgFontKey("/fonts/NotoSans.ttf", CgFontStyle.BOLD_ITALIC, 32);

        assertEquals("fontPath", "/fonts/NotoSans.ttf", key.getFontPath());
        assertEquals("style", CgFontStyle.BOLD_ITALIC, key.getStyle());
        assertEquals("targetPx", 32, key.getTargetPx());
    }

    /**
     * CgFontKey toString contains field values.
     */
    @Test
    public void testFontKey_toString_contains_fields() {
        CgFontKey key = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        String str = key.toString();

        assertTrue("toString should contain fontPath", str.contains("font.ttf"));
        assertTrue("toString should contain style", str.contains("REGULAR"));
        assertTrue("toString should contain targetPx", str.contains("12"));
    }

    // ---------------------------------------------------------------
    //  CgGlyphKey equality and MSDF distinction tests
    // ---------------------------------------------------------------

    /**
     * CgGlyphKey with msdf=true must NOT equal same key with msdf=false.
     * This is the key QA scenario from the plan.
     */
    @Test
    public void testGlyphKey_msdf_distinction() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 48);
        CgGlyphKey bitmap = new CgGlyphKey(fontKey, 65, false);
        CgGlyphKey msdf = new CgGlyphKey(fontKey, 65, true);

        assertNotEquals("Bitmap and MSDF variants must not be equal", bitmap, msdf);
    }

    /**
     * Two CgGlyphKey instances with identical fields must be equal.
     */
    @Test
    public void testGlyphKey_value_equality() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        CgGlyphKey key1 = new CgGlyphKey(fontKey, 65, false, 0);
        CgGlyphKey key2 = new CgGlyphKey(fontKey, 65, false, 0);

        assertEquals("Same fields should be equal", key1, key2);
        assertEquals("Equal keys must have same hashCode", key1.hashCode(), key2.hashCode());
    }

    /**
     * CgGlyphKey with different glyphId must not be equal.
     */
    @Test
    public void testGlyphKey_different_glyphId_not_equal() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        CgGlyphKey key1 = new CgGlyphKey(fontKey, 65, false, 0);
        CgGlyphKey key2 = new CgGlyphKey(fontKey, 66, false, 0);

        assertNotEquals("Different glyphIds should not be equal", key1, key2);
    }

    /**
     * CgGlyphKey with different fontKey must not be equal.
     */
    @Test
    public void testGlyphKey_different_fontKey_not_equal() {
        CgFontKey fontKey1 = new CgFontKey("font-a.ttf", CgFontStyle.REGULAR, 12);
        CgFontKey fontKey2 = new CgFontKey("font-b.ttf", CgFontStyle.REGULAR, 12);
        CgGlyphKey key1 = new CgGlyphKey(fontKey1, 65, false, 0);
        CgGlyphKey key2 = new CgGlyphKey(fontKey2, 65, false, 0);

        assertNotEquals("Different fontKeys should not be equal", key1, key2);
    }

    /**
     * CgGlyphKey getter methods return stored values.
     */
    @Test
    public void testGlyphKey_getters() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.ITALIC, 24);
        CgGlyphKey key = new CgGlyphKey(fontKey, 100, true, 3);

        assertSame("fontKey", fontKey, key.getFontKey());
        assertEquals("glyphId", 100, key.getGlyphId());
        assertTrue("msdf", key.isMsdf());
        assertEquals("subPixelBucket should be retained for sizes below threshold",
                3, key.getSubPixelBucket());
    }

    // ---------------------------------------------------------------
    //  Sub-pixel bucket threshold tests (bitmap/MSDF boundary at 32px)
    // ---------------------------------------------------------------

    @Test
    public void testGlyphKey_midSize13px_retainsSubPixelBucket() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 13);
        CgGlyphKey key = new CgGlyphKey(fontKey, 65, false, 2);
        assertEquals("13px should retain sub-pixel bucket", 2, key.getSubPixelBucket());
    }

    @Test
    public void testGlyphKey_midSize24px_retainsSubPixelBucket() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 24);
        CgGlyphKey key = new CgGlyphKey(fontKey, 65, false, 3);
        assertEquals("24px should retain sub-pixel bucket", 3, key.getSubPixelBucket());
    }

    @Test
    public void testGlyphKey_midSize31px_retainsSubPixelBucket() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 31);
        CgGlyphKey key = new CgGlyphKey(fontKey, 65, false, 1);
        assertEquals("31px should retain sub-pixel bucket", 1, key.getSubPixelBucket());
    }

    @Test
    public void testGlyphKey_atThreshold32px_normalizesToZero() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 32);
        CgGlyphKey key = new CgGlyphKey(fontKey, 65, false, 3);
        assertEquals("32px should normalize sub-pixel bucket to 0", 0, key.getSubPixelBucket());
    }

    @Test
    public void testGlyphKey_aboveThreshold48px_normalizesToZero() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 48);
        CgGlyphKey key = new CgGlyphKey(fontKey, 65, false, 2);
        assertEquals("48px should normalize sub-pixel bucket to 0", 0, key.getSubPixelBucket());
    }

    @Test
    public void testGlyphKey_thresholdConstantMatchesMsdfBoundary() {
        assertEquals("SUB_PIXEL_BUCKET_MAX_PX should match MSDF handoff boundary",
                32, CgGlyphKey.SUB_PIXEL_BUCKET_MAX_PX);
    }

    @Test
    public void testGlyphKey_largeFont_subPixelBucket_normalizedToZero() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 32);
        CgGlyphKey bucket0 = new CgGlyphKey(fontKey, 65, false, 0);
        CgGlyphKey bucket3 = new CgGlyphKey(fontKey, 65, false, 3);

        assertEquals("Large fonts should normalize sub-pixel buckets to zero", bucket0, bucket3);
        assertEquals(0, bucket0.getSubPixelBucket());
        assertEquals(0, bucket3.getSubPixelBucket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlyphKey_smallFont_rejectsNegativeSubPixelBucket() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        new CgGlyphKey(fontKey, 65, false, -1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlyphKey_smallFont_rejectsOutOfRangeSubPixelBucket() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        new CgGlyphKey(fontKey, 65, false, 4);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlyphKey_rejectsNegativeGlyphId() {
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        new CgGlyphKey(fontKey, -1, false, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGlyphKey_rejectsNullFontKey() {
        new CgGlyphKey(null, 65, false, 0);
    }

    // ---------------------------------------------------------------
    //  CgFontMetrics tests
    // ---------------------------------------------------------------

    /**
     * CgFontMetrics stores and retrieves all metric values.
     */
    @Test
    public void testFontMetrics_getters() {
        CgFontMetrics m = new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);

        assertEquals("ascender", 11.0f, m.getAscender(), 0.001f);
        assertEquals("descender", 3.0f, m.getDescender(), 0.001f);
        assertEquals("lineGap", 1.0f, m.getLineGap(), 0.001f);
        assertEquals("lineHeight", 15.0f, m.getLineHeight(), 0.001f);
        assertEquals("xHeight", 7.0f, m.getXHeight(), 0.001f);
        assertEquals("capHeight", 10.0f, m.getCapHeight(), 0.001f);
    }

    /**
     * Two CgFontMetrics with identical values must be equal.
     */
    @Test
    public void testFontMetrics_value_equality() {
        CgFontMetrics m1 = new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);
        CgFontMetrics m2 = new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);

        assertEquals("Same metrics should be equal", m1, m2);
        assertEquals("Equal metrics must have same hashCode", m1.hashCode(), m2.hashCode());
    }

    /**
     * CgFontMetrics with different values must not be equal.
     */
    @Test
    public void testFontMetrics_different_values_not_equal() {
        CgFontMetrics m1 = new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);
        CgFontMetrics m2 = new CgFontMetrics(12.0f, 3.0f, 1.0f, 16.0f, 7.0f, 10.0f);

        assertNotEquals("Different ascender should not be equal", m1, m2);
    }

    // ---------------------------------------------------------------
    //  CgGlyphMetrics tests
    // ---------------------------------------------------------------

    /**
     * CgGlyphMetrics stores and retrieves all metric values.
     */
    @Test
    public void testGlyphMetrics_getters() {
        CgGlyphMetrics gm = new CgGlyphMetrics(8.0f, 1.0f, 10.0f, 7.0f, 11.0f);

        assertEquals("advanceX", 8.0f, gm.getAdvanceX(), 0.001f);
        assertEquals("bearingX", 1.0f, gm.getBearingX(), 0.001f);
        assertEquals("bearingY", 10.0f, gm.getBearingY(), 0.001f);
        assertEquals("width", 7.0f, gm.getWidth(), 0.001f);
        assertEquals("height", 11.0f, gm.getHeight(), 0.001f);
    }

    /**
     * Two CgGlyphMetrics with identical values must be equal.
     */
    @Test
    public void testGlyphMetrics_value_equality() {
        CgGlyphMetrics gm1 = new CgGlyphMetrics(8.0f, 1.0f, 10.0f, 7.0f, 11.0f);
        CgGlyphMetrics gm2 = new CgGlyphMetrics(8.0f, 1.0f, 10.0f, 7.0f, 11.0f);

        assertEquals("Same glyph metrics should be equal", gm1, gm2);
        assertEquals("Equal glyph metrics must have same hashCode", gm1.hashCode(), gm2.hashCode());
    }

    /**
     * CgGlyphMetrics with different values must not be equal.
     */
    @Test
    public void testGlyphMetrics_different_values_not_equal() {
        CgGlyphMetrics gm1 = new CgGlyphMetrics(8.0f, 1.0f, 10.0f, 7.0f, 11.0f);
        CgGlyphMetrics gm2 = new CgGlyphMetrics(9.0f, 1.0f, 10.0f, 7.0f, 11.0f);

        assertNotEquals("Different advanceX should not be equal", gm1, gm2);
    }

    // ---------------------------------------------------------------
    //  CgFontStyle enum tests
    // ---------------------------------------------------------------

    /**
     * CgFontStyle enum has exactly 4 values.
     */
    @Test
    public void testFontStyle_values() {
        CgFontStyle[] values = CgFontStyle.values();
        assertEquals("Should have exactly 4 styles", 4, values.length);
        assertEquals(CgFontStyle.REGULAR, values[0]);
        assertEquals(CgFontStyle.BOLD, values[1]);
        assertEquals(CgFontStyle.ITALIC, values[2]);
        assertEquals(CgFontStyle.BOLD_ITALIC, values[3]);
    }

    /**
     * CgFontStyle valueOf round-trips correctly.
     */
    @Test
    public void testFontStyle_valueOf() {
        assertEquals(CgFontStyle.REGULAR, CgFontStyle.valueOf("REGULAR"));
        assertEquals(CgFontStyle.BOLD, CgFontStyle.valueOf("BOLD"));
        assertEquals(CgFontStyle.ITALIC, CgFontStyle.valueOf("ITALIC"));
        assertEquals(CgFontStyle.BOLD_ITALIC, CgFontStyle.valueOf("BOLD_ITALIC"));
    }

    // ---------------------------------------------------------------
    //  Map key usage tests (HashMap contract)
    // ---------------------------------------------------------------

    /**
     * CgFontKey works correctly as a HashMap key.
     */
    @Test
    public void testFontKey_as_map_key() {
        java.util.Map<CgFontKey, String> map = new java.util.HashMap<CgFontKey, String>();
        CgFontKey key1 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        map.put(key1, "value1");

        CgFontKey key2 = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);
        assertEquals("Lookup by equal key should find value", "value1", map.get(key2));
    }

    /**
     * CgGlyphKey works correctly as a HashMap key.
     */
    @Test
    public void testGlyphKey_as_map_key() {
        java.util.Map<CgGlyphKey, String> map = new java.util.HashMap<CgGlyphKey, String>();
        CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 12);

        CgGlyphKey key1 = new CgGlyphKey(fontKey, 65, false);
        map.put(key1, "glyph-A-bitmap");

        CgGlyphKey key2 = new CgGlyphKey(fontKey, 65, false);
        assertEquals("Lookup by equal key should find value", "glyph-A-bitmap", map.get(key2));

        CgGlyphKey msdfKey = new CgGlyphKey(fontKey, 65, true);
        assertNull("MSDF key should not find bitmap entry", map.get(msdfKey));
    }
}
