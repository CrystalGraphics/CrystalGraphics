package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers {@link CgGlyphPlacementCache}'s dual entry/byte budget.
 *
 * <p>The property that actually matters is that the running byte total stays consistent with the
 * map's contents across <em>both</em> eviction paths — {@code removeEldestEntry} for the count
 * budget and {@code trimToByteBudget} for the byte budget. An incremental counter that drifts is
 * worse than no counter: it silently converts every {@code put} into a full trim sweep, or stops
 * evicting entirely, and neither shows up as a failure anywhere near the cause.
 */
public class CgGlyphPlacementCacheBudgetTest {

    /** Bytes charged per glyph by {@code Entry.estimatedBytes()}. */
    private static final long BYTES_PER_GLYPH = 16L;
    private static final long ENTRY_OVERHEAD = 96L;

    private static CgGlyphPlacementCache.Entry entry(int glyphCount) {
        return new CgGlyphPlacementCache.Entry(
                true, 16, 0L, 0L, 0L, glyphCount,
                new float[glyphCount], new float[glyphCount], new int[glyphCount],
                new CgGlyphPlacement[glyphCount]);
    }

    /**
     * Distinct keys. {@code layout} compares by identity, so a fresh mock per key is enough —
     * no real layout needed, and building one would drag fonts into a pure-logic test.
     */
    private static final CgFontKey FONT_KEY =
            new CgFontKey("test:font.ttf", com.crystalgraphics.api.font.CgFontStyle.REGULAR, 16);

    private static CgGlyphPlacementCache.Key key(int i) {
        return new CgGlyphPlacementCache.Key(null, i, 0f, true, FONT_KEY, 0xFFFFFFFF);
    }

    @Before
    public void reset() {
        CgGlyphPlacementCache.clearForTest();
    }

    @Test
    public void estimatedBytesScalesWithGlyphCount() {
        assertEquals(ENTRY_OVERHEAD, entry(0).estimatedBytes());
        assertEquals(ENTRY_OVERHEAD + 100L * BYTES_PER_GLYPH, entry(100).estimatedBytes());
        // The whole point of the byte budget: a big entry must not be charged like a small one.
        assertTrue(entry(3363).estimatedBytes() > 100L * entry(2).estimatedBytes());
    }

    @Test
    public void runningTotalMatchesContentsAfterPuts() {
        long expected = 0;
        for (int i = 0; i < 50; i++) {
            CgGlyphPlacementCache.Entry e = entry(i * 10);
            CgGlyphPlacementCache.put(key(i), e);
            expected += e.estimatedBytes();
        }
        assertEquals(50, CgGlyphPlacementCache.size());
        assertEquals(expected, CgGlyphPlacementCache.estimatedBytes());
    }

    @Test
    public void replacingAKeyDoesNotDoubleCount() {
        CgGlyphPlacementCache.Key k = key(1);
        CgGlyphPlacementCache.put(k, entry(500));
        long afterFirst = CgGlyphPlacementCache.estimatedBytes();

        CgGlyphPlacementCache.put(k, entry(500));
        assertEquals("same key, same size -> unchanged total", afterFirst, CgGlyphPlacementCache.estimatedBytes());
        assertEquals(1, CgGlyphPlacementCache.size());

        CgGlyphPlacementCache.put(k, entry(1000));
        assertEquals(ENTRY_OVERHEAD + 1000L * BYTES_PER_GLYPH, CgGlyphPlacementCache.estimatedBytes());
    }

    @Test
    public void countBudgetKeepsTheByteTotalConsistent() {
        // Far past CAPACITY (1024) with small entries, so eviction runs entirely through
        // removeEldestEntry and never through the byte path. The total must still track.
        for (int i = 0; i < 1500; i++) {
            CgGlyphPlacementCache.put(key(i), entry(1));
        }
        int size = CgGlyphPlacementCache.size();
        assertTrue("count budget should have capped the map", size <= 1024);
        assertEquals("byte total must equal size x per-entry cost",
                (long) size * (ENTRY_OVERHEAD + BYTES_PER_GLYPH), CgGlyphPlacementCache.estimatedBytes());
    }

    @Test
    public void byteBudgetEvictsBeforeTheCountBudgetWouldFor()  {
        // Entries large enough that the 8 MB byte budget binds long before 1024 entries do:
        // 100k glyphs = ~1.6 MB each, so ~5 fit.
        for (int i = 0; i < 40; i++) {
            CgGlyphPlacementCache.put(key(i), entry(100_000));
        }
        assertTrue("byte budget must have evicted well before CAPACITY",
                CgGlyphPlacementCache.size() < 40);
        assertTrue("must be under the byte ceiling",
                CgGlyphPlacementCache.estimatedBytes() <= 8L * 1024L * 1024L);
    }

    @Test
    public void byteEvictionRemovesLeastRecentlyUsedFirst() {
        // ~1.53 MB each against an 8 MB ceiling, so exactly five fit and the sixth forces one
        // eviction. Sized deliberately: pushing in far more than fits would evict everything and
        // the test would pass for the wrong reason.
        for (int i = 0; i < 5; i++) {
            CgGlyphPlacementCache.put(key(i), entry(100_000));
        }
        assertEquals("five should fit under the ceiling", 5, CgGlyphPlacementCache.size());

        // Touch the oldest so key(1), not key(0), is now the LRU victim.
        assertNotNull(CgGlyphPlacementCache.get(key(0), 16, 0L, 0L, 0L));

        CgGlyphPlacementCache.put(key(5), entry(100_000));

        assertNotNull("recently accessed entry should survive", CgGlyphPlacementCache.get(key(0), 16, 0L, 0L, 0L));
        assertNull("least-recently-used entry should have been evicted",
                CgGlyphPlacementCache.get(key(1), 16, 0L, 0L, 0L));
    }

    @Test
    public void aSingleOversizedEntryIsStillStored() {
        // Bigger than the whole budget on its own. Evicting it immediately would mean the cache
        // never serves the layout it most needs to, so it must be kept even while over budget.
        CgGlyphPlacementCache.put(key(1), entry(2_000_000));
        assertEquals(1, CgGlyphPlacementCache.size());
    }
}
