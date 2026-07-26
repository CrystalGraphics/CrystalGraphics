package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgGlyphPlacementCache.Key}/{@link CgGlyphPlacementCache.Entry} —
 * specifically the phase 11 fix adding {@code rgba} to the key so two draws of the same
 * cached layout at the same position with two different default colors don't incorrectly
 * share a cache entry (and so its resolved colors).
 */
public class CgGlyphPlacementCacheTest {

    private static final CgFontKey FONT_KEY = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 16);
    private static final CgFontMetrics METRICS = new CgFontMetrics(10, 2, 1, 13, 6, 8);

    @Test
    public void testKey_sameFieldsIncludingRgba_areEqual() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key a = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF);
        CgGlyphPlacementCache.Key b = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testKey_differentRgba_areNotEqual() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key white = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF);
        CgGlyphPlacementCache.Key red = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFF0000);

        assertNotEquals("Two draws of the same layout/position with different default "
                + "colors must not share a cache key", white, red);
    }

    @Test
    public void testCacheHit_differentDefaultColorAtSamePosition_isATrueMiss() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key whiteKey = CgGlyphPlacementCache.key(layout, 5f, 5f, false, FONT_KEY, 0xFFFFFFFF);
        CgGlyphPlacementCache.Entry whiteEntry = new CgGlyphPlacementCache.Entry(
                false, 16, 1L, 1,
                new float[]{0f}, new float[]{0f}, new int[]{0xFFFFFFFF},
                new CgGlyphPlacement[]{null});
        CgGlyphPlacementCache.put(whiteKey, whiteEntry);

        assertNotNull("Same key should hit", CgGlyphPlacementCache.get(whiteKey, 16, 1L));

        CgGlyphPlacementCache.Key redKey = CgGlyphPlacementCache.key(layout, 5f, 5f, false, FONT_KEY, 0xFFFF0000);
        assertNull("A different default color at the same layout/position must be a cache "
                        + "miss, not incorrectly reuse the white entry's resolved colors",
                CgGlyphPlacementCache.get(redKey, 16, 1L));
    }

    @Test
    public void testEntry_storesResolvedPerGlyphColor() {
        int[] colors = {0xFFFF0000, 0xFFFFFFFF};
        CgGlyphPlacementCache.Entry entry = new CgGlyphPlacementCache.Entry(
                false, 16, 1L, 2,
                new float[]{0f, 10f}, new float[]{0f, 0f}, colors,
                new CgGlyphPlacement[]{null, null});

        assertArrayEquals(colors, entry.argbColor());
    }
}
