package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.text.atlas.packing.CgPackingStrategy;
import com.crystalgraphics.trace.CgFrameRecord;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceSnapshot;
import com.crystalgraphics.util.trace.CgChannels;
import org.junit.Test;

import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Measures atlas page eviction — the one atlas path that can stall mid-session.
 *
 * <p>Everything else measured about the atlas happens at startup: pages grow, glyphs upload, and it
 * settles. Eviction is different. It fires only once an atlas is full, which means it fires during
 * normal play, long after anyone is watching for a hitch — and no benchmark reached it, because no
 * scene renders enough distinct glyphs to fill a 32-page atlas.
 *
 * <p>Forcing it with a small page budget rather than a huge glyph set is deliberate: the cost of an
 * eviction depends on how many glyphs a page holds and what dropping them invalidates, not on how
 * long it took to get there. A tiny budget reaches the same state in a fraction of the time.
 *
 * <p>The scope alone understates the real cost. Freeing a page is cheap; the expensive part is that
 * dropping its glyphs bumps {@code evictionGeneration}, which invalidates every
 * {@code CgGlyphPlacementCache} entry built against the old generation — those re-resolve on their
 * next draw, and can pull glyphs straight back into the atlas. {@code glyphsDropped} sizes that
 * downstream wave, so it is reported alongside the timing.
 *
 * <p>Runs in test mode ({@code skipGlUpload}), so this measures the CPU side — packing, index
 * maintenance, page bookkeeping — and not the GL texture work an eviction also triggers in-game.
 */
public class CgGlyphAtlasEvictionCostTest {

    /** Small enough to fill quickly, large enough to hold a realistic number of glyphs per page. */
    private static final int PAGE_SIZE = 256;

    /** Tight budget so eviction begins early and repeats many times within one run. */
    private static final int MAX_PAGES = 4;

    private static final int GLYPH_W = 24;
    private static final int GLYPH_H = 24;

    /** Enough allocations to evict many times over, so the average is not one unlucky sample. */
    private static final int GLYPHS = 4000;

    @Test
    public void reportsEvictionCost() {
        // The whole run is frame 0, closed by the second frameBegin so its counters are written.
        CgTrace.resetForTesting();
        CgTrace.enable(CgChannels.TEXT.name());
        try {
            CgTrace.frameBegin();
            runEvictionMeasurement();
            CgTrace.frameBegin();
            report();
        } finally {
            CgTrace.resetForTesting();
        }
    }

    private double totalMs;

    private void runEvictionMeasurement() {

        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(
                PAGE_SIZE, PAGE_SIZE, CgGlyphAtlas.Type.BITMAP,
                CgGlyphAtlas.MAX_RECTS_FACTORY, MAX_PAGES);

        CgFontKey fontKey = new CgFontKey("evict-test", CgFontStyle.REGULAR, 16,
                Collections.emptyList());
        byte[] pixels = new byte[GLYPH_W * GLYPH_H];

        long start = System.nanoTime();
        for (int i = 0; i < GLYPHS; i++) {
            CgGlyphKey key = new CgGlyphKey(fontKey, i, false);
            atlas.allocateBitmap(key, pixels, GLYPH_W, GLYPH_H,
                    0f, GLYPH_H, GLYPH_W, GLYPH_H, i);
        }
        totalMs = (System.nanoTime() - start) / 1_000_000.0;
    }

    private void report() {
        CgFrameRecord frame = firstFrame();
        long evictions = 0L;
        long droppedCount = 0L, droppedSum = 0L, droppedMin = Long.MAX_VALUE, droppedMax = Long.MIN_VALUE;
        for (CgTraceSnapshot.CounterView c : CgTrace.countersIn(frame)) {
            if (c.name().equals("atlas.evictPage.count")) evictions += c.value();
            if (c.name().equals("atlas.evictPage.glyphsDropped")) {
                droppedCount++;
                droppedSum += c.value();
                droppedMin = Math.min(droppedMin, c.value());
                droppedMax = Math.max(droppedMax, c.value());
            }
        }
        double evictMs = 0;
        for (CgTraceSnapshot.ZoneView z : CgTrace.zonesIn(frame)) {
            if (z.name().equals("atlas.evictPage")) evictMs += (z.endNanos() - z.startNanos()) / 1_000_000.0;
        }

        System.out.println();
        System.out.printf(Locale.ROOT,
                "=== atlas eviction: %d glyphs into a %d-page %dx%d atlas ===%n",
                GLYPHS, MAX_PAGES, PAGE_SIZE, PAGE_SIZE);
        System.out.printf(Locale.ROOT, "  allocations total   %8.2f ms%n", totalMs);
        System.out.printf(Locale.ROOT, "  evictions           %8d%n", evictions);
        System.out.printf(Locale.ROOT, "  eviction total      %8.3f ms%n", evictMs);
        if (evictions > 0) {
            System.out.printf(Locale.ROOT, "  per eviction        %8.3f ms%n", evictMs / evictions);
            System.out.printf(Locale.ROOT, "  eviction share      %8.1f%% of allocation time%n",
                    100.0 * evictMs / totalMs);
        }
        if (droppedCount > 0) {
            System.out.printf(Locale.ROOT,
                    "  glyphs dropped      avg %.1f, min %d, max %d  (placement-cache entries invalidated per eviction)%n",
                    (double) droppedSum / droppedCount, droppedMin, droppedMax);
        }
        if (CgTrace.droppedZones() > 0) {
            System.out.printf(Locale.ROOT, "  (%d zones did not fit the frame; eviction total is a floor)%n",
                    CgTrace.droppedZones());
        }

        assertTrue("expected the page budget to force at least one eviction — if this fails the "
                + "atlas grew instead of evicting, and the test is no longer measuring what it claims",
                evictions > 0);
    }

    private static CgFrameRecord firstFrame() {
        for (CgFrameRecord frame : CgTrace.frames()) {
            if (frame.index() == 0L) return frame;
        }
        throw new AssertionError("frame 0 was not recorded");
    }
}
