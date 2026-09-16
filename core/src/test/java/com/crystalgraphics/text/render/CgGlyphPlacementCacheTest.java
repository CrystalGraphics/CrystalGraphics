package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.Before;
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

    /** The refresh budget is static render-thread state; a test that charges it must not leak into the next. */
    @Before
    public void resetCache() {
        CgGlyphPlacementCache.clearForTest();
    }

    private static final CgFontKey FONT_KEY = new CgFontKey("test.ttf", CgFontStyle.REGULAR, 16);
    private static final CgFontMetrics METRICS = new CgFontMetrics(10, 2, 1, 13, 6, 8);

    @Test
    public void testKey_sameFieldsIncludingRgba_areEqual() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key a = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF, 0);
        CgGlyphPlacementCache.Key b = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF, 0);

        // The pose's sub-pixel phase is part of identity now: a translated element reaches the
        // same layout at the same x/y, and sharing its placements is what kept a fractional
        // transform from ever moving the glyphs.
        CgGlyphPlacementCache.Key shifted =
                CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF, 7);
        assertNotEquals(a, shifted);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testKey_differentRgba_areNotEqual() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key white = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFFFFFF, 0);
        CgGlyphPlacementCache.Key red = CgGlyphPlacementCache.key(layout, 1f, 2f, false, FONT_KEY, 0xFFFF0000, 0);

        assertNotEquals("Two draws of the same layout/position with different default "
                + "colors must not share a cache key", white, red);
    }

    @Test
    public void testCacheHit_differentDefaultColorAtSamePosition_isATrueMiss() {
        CgTextLayout layout = new CgTextLayout(List.of(), 0, 0, METRICS);

        CgGlyphPlacementCache.Key whiteKey = CgGlyphPlacementCache.key(layout, 5f, 5f, false, FONT_KEY, 0xFFFFFFFF, 0);
        CgGlyphPlacementCache.Entry whiteEntry = new CgGlyphPlacementCache.Entry(
                false, 16, 1L, 0L, 0L, 1,
                new float[]{0f}, new float[]{0f}, new int[]{0xFFFFFFFF},
                new CgGlyphPlacement[]{null});
        CgGlyphPlacementCache.put(whiteKey, whiteEntry);

        assertNotNull("Same key should hit", CgGlyphPlacementCache.get(whiteKey, 16, 1L, 0L, 0L));

        CgGlyphPlacementCache.Key redKey = CgGlyphPlacementCache.key(layout, 5f, 5f, false, FONT_KEY, 0xFFFF0000, 0);
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

    /** An entry of a real size, for the rules that depend on how much a refresh would cost. */
    private static CgGlyphPlacementCache.Entry sized(boolean distanceField, int glyphs, long builtFrame) {
        return new CgGlyphPlacementCache.Entry(distanceField, 64, 10L, 0L, builtFrame, glyphs,
                new float[glyphs], new float[glyphs], new int[glyphs], new CgGlyphPlacement[glyphs]);
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
    public void testMatches_bitmapEntry_refreshesShareAFrameBudget() {
        // Without a limit the async drain bumps the content generation on nearly every frame during
        // warmup, and every unconverged entry re-resolving every frame measured at 5-41 fps for
        // ~11 s. The limit is a per-frame TIME budget for small entries now, not a flat 120 frames.
        long frame = 1L;
        CgGlyphPlacementCache.chargeRefresh(frame, 3_000_000L);
        CgGlyphPlacementCache.Entry e = entry(false, 48, 100L, 0L);

        assertTrue("with this frame's refresh budget spent, a generation change must NOT force a "
                        + "re-resolve",
                e.matches(48, 101L, 0L, frame));
        assertFalse("and on the next frame the budget is back, so a small entry refreshes",
                e.matches(48, 101L, 0L, frame + 1));
    }

    /**
     * A font switch resolves a label while most glyphs are still on the bitmap fallback; their fields
     * land two frames later. Served back for the full frame limit, that was a second of text with no
     * outline on a page doing nothing else.
     */
    @Test
    public void testMatches_smallBitmapEntry_refreshesTheFrameAfterItsFieldsLand() {
        assertFalse("ten glyphs whose fields have landed must refresh, not wait out the frame limit",
                sized(false, 10, 100L).matches(64, 11L, 0L, 102L));
    }

    @Test
    public void testMatches_largeBitmapEntry_keepsTheFrameLimitItsCostWasMeasuredAgainst() {
        CgGlyphPlacementCache.Entry document = sized(false, 1757, 100L);

        assertTrue("a refresh this size is ~100 ms, which a 2 ms budget must not admit",
                document.matches(64, 11L, 0L, 102L));
        assertFalse("but the frame limit still guarantees it converges",
                document.matches(64, 11L, 0L, 220L));
    }

    @Test
    public void testMatches_bitmapEntry_stillRequiresExactEffectiveTargetPx() {
        CgGlyphPlacementCache.Entry e = entry(false, 48, 100L, 0L);

        assertTrue(e.matches(48, 100L, 0L, 0L));
        assertFalse("bitmap placements are rasterized at a specific effective size",
                e.matches(49, 100L, 0L, PAST_RATE_LIMIT));
    }
}
