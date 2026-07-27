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
                false, 16, 1L, 0L, 0L, 1,
                new float[]{0f}, new float[]{0f}, new int[]{0xFFFFFFFF},
                new CgGlyphPlacement[]{null});
        CgGlyphPlacementCache.put(whiteKey, whiteEntry);

        assertNotNull("Same key should hit", CgGlyphPlacementCache.get(whiteKey, 16, 1L, 0L, 0L));

        CgGlyphPlacementCache.Key redKey = CgGlyphPlacementCache.key(layout, 5f, 5f, false, FONT_KEY, 0xFFFF0000);
        assertNull("A different default color at the same layout/position must be a cache "
                        + "miss, not incorrectly reuse the white entry's resolved colors",
                CgGlyphPlacementCache.get(redKey, 16, 1L, 0L, 0L));
    }

    @Test
    public void testEntry_storesResolvedPerGlyphColor() {
        int[] colors = {0xFFFF0000, 0xFFFFFFFF};
        CgGlyphPlacementCache.Entry entry = new CgGlyphPlacementCache.Entry(
                false, 16, 1L, 0L, 0L, 2,
                new float[]{0f, 10f}, new float[]{0f, 0f}, colors,
                new CgGlyphPlacement[]{null, null});

        assertArrayEquals(colors, entry.argbColor());
    }

    // ── Revision-based staleness (replaces the old REFRESH_FRAMES timer) ──

    /** Built at frame 0, so a {@code frame} arg of 0 is "same frame" and a large one is
     * "long past the unconverged rate limit". */
    private static CgGlyphPlacementCache.Entry entry(boolean distanceField, int effectiveTargetPx,
                                                      long contentGen, long evictionGen) {
        return new CgGlyphPlacementCache.Entry(distanceField, effectiveTargetPx, contentGen, evictionGen,
                0L, 0,
                new float[0], new float[0], new int[0], new CgGlyphPlacement[0]);
    }

    /** Comfortably beyond {@code MIN_REFRESH_FRAMES_WHILE_UNCONVERGED} for an entry built at frame 0. */
    private static final long PAST_RATE_LIMIT = 100_000L;

    @Test
    public void testMatches_distanceFieldEntry_survivesNewAtlasContentForever() {
        // The whole point of the REFRESH_FRAMES removal: a converged (fully distance-field)
        // layout must never need re-resolving just because time passed or unrelated glyphs
        // landed -- that was a ~34ms full re-resolve hitch every 300 frames, forever.
        CgGlyphPlacementCache.Entry e = entry(true, 48, 100L, 0L);

        assertTrue("new atlas content must not invalidate a fully distance-field entry",
                e.matches(48, 999_999L, 0L, PAST_RATE_LIMIT));
        assertTrue("effectiveTargetPx is irrelevant to a distance-field entry",
                e.matches(9999, 999_999L, 0L, PAST_RATE_LIMIT));
    }

    @Test
    public void testMatches_distanceFieldEntry_invalidatedByEviction() {
        CgGlyphPlacementCache.Entry e = entry(true, 48, 100L, 7L);

        assertTrue(e.matches(48, 100L, 7L, 0L));
        assertFalse("an eviction reuses the freed layer index, so even a distance-field "
                + "entry's placements may now point at another page's glyphs",
                e.matches(48, 100L, 8L, 0L));
    }

    @Test
    public void testMatches_bitmapEntry_invalidatedByNewAtlasContent_onceRateLimitElapsed() {
        // A bitmap-fallback entry is exactly the case new content matters for: the MSDF
        // upgrade it was waiting on may have just landed.
        CgGlyphPlacementCache.Entry e = entry(false, 48, 100L, 0L);

        assertTrue(e.matches(48, 100L, 0L, PAST_RATE_LIMIT));
        assertFalse("new atlas content may mean a bitmap-fallback glyph can now upgrade",
                e.matches(48, 101L, 0L, PAST_RATE_LIMIT));
    }

    @Test
    public void testMatches_bitmapEntry_newContentIsRateLimited_notActedOnEveryFrame() {
        // Without this rate limit the async drain bumps the content generation on nearly every
        // frame during warmup, so every frame became a full re-resolve -- measured at 5-41 fps
        // for ~11s. See MIN_REFRESH_FRAMES_WHILE_UNCONVERGED.
        CgGlyphPlacementCache.Entry e = entry(false, 48, 100L, 0L);

        assertTrue("a generation change immediately after the entry was built must NOT force "
                        + "an instant re-resolve",
                e.matches(48, 101L, 0L, 1L));
    }

    @Test
    public void testMatches_bitmapEntry_stillRequiresExactEffectiveTargetPx() {
        CgGlyphPlacementCache.Entry e = entry(false, 48, 100L, 0L);

        assertTrue(e.matches(48, 100L, 0L, 0L));
        assertFalse("bitmap placements are rasterized at a specific effective size",
                e.matches(49, 100L, 0L, PAST_RATE_LIMIT));
    }
}
