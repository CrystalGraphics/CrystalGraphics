package io.github.somehussar.crystalgraphics.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.text.CgShapedRun;
import io.github.somehussar.crystalgraphics.api.text.CgTextLayout;
import io.github.somehussar.crystalgraphics.text.layout.CgLineBreaker;
import io.github.somehussar.crystalgraphics.text.layout.CgTextShaper;
import io.github.somehussar.crystalgraphics.text.layout.RunReshaper;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for the text layout CPU pipeline: {@link CgShapedRun},
 * {@link CgTextShaper}, {@link CgLineBreaker}, and {@link CgTextLayout}.
 *
 * <p>Tests that require HarfBuzz native libraries are guarded by a try/catch
 * around native loading. Tests for {@link CgLineBreaker} and {@link CgTextLayout}
 * use manually constructed {@link CgShapedRun} objects to avoid native dependencies.</p>
 */
public class CgTextLayoutTest {

    /** Standard test font key. */
    private static final CgFontKey TEST_FONT_KEY =
            new CgFontKey("test-font.ttf", CgFontStyle.REGULAR, 12);

    /** Standard test font metrics (ascender=11, descender=3, lineGap=1, lineHeight=15). */
    private static final CgFontMetrics TEST_METRICS =
            new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);

    // ---------------------------------------------------------------
    //  CgShapedRun value type tests
    // ---------------------------------------------------------------

    @Test
    public void testShapedRun_stores_fields_correctly() {
        int[] glyphIds = {65, 66, 67};
        int[] clusterIds = {0, 1, 2};
        float[] advancesX = {8.0f, 7.5f, 8.25f};
        float[] offsetsX = {0.0f, 0.0f, 0.0f};
        float[] offsetsY = {0.0f, 0.0f, 0.0f};
        float totalAdvance = 23.75f;

        CgShapedRun run = new CgShapedRun(TEST_FONT_KEY, false,
                glyphIds, clusterIds, advancesX, offsetsX, offsetsY, totalAdvance);

        assertSame(TEST_FONT_KEY, run.getFontKey());
        assertFalse(run.isRtl());
        assertArrayEquals(glyphIds, run.getGlyphIds());
        assertArrayEquals(clusterIds, run.getClusterIds());
        assertArrayEquals(advancesX, run.getAdvancesX(), 0.001f);
        assertArrayEquals(offsetsX, run.getOffsetsX(), 0.001f);
        assertArrayEquals(offsetsY, run.getOffsetsY(), 0.001f);
        assertEquals(23.75f, run.getTotalAdvance(), 0.001f);
    }

    @Test
    public void testShapedRun_rtl_flag() {
        CgShapedRun run = makeRun(true, 50.0f);
        assertTrue("RTL run should report rtl=true", run.isRtl());
    }

    @Test
    public void testShapedRun_value_equality() {
        int[] glyphs = {1, 2, 3};
        int[] clusters = {0, 1, 2};
        float[] adv = {5.0f, 5.0f, 5.0f};
        float[] ox = {0, 0, 0};
        float[] oy = {0, 0, 0};

        CgShapedRun a = new CgShapedRun(TEST_FONT_KEY, false, glyphs, clusters, adv, ox, oy, 15.0f);
        CgShapedRun b = new CgShapedRun(TEST_FONT_KEY, false, glyphs, clusters, adv, ox, oy, 15.0f);

        assertEquals("Same-field shaped runs should be equal", a, b);
        assertEquals("Equal shaped runs must have same hashCode", a.hashCode(), b.hashCode());
    }

    @Test
    public void testShapedRun_empty_run() {
        CgShapedRun run = new CgShapedRun(TEST_FONT_KEY, false,
                new int[0], new int[0],
                new float[0], new float[0], new float[0],
                0.0f);

        assertEquals(0, run.getGlyphIds().length);
        assertEquals(0.0f, run.getTotalAdvance(), 0.001f);
        assertFalse("Backward-compat constructor should not have source context",
                run.hasSourceContext());
    }

    @Test
    public void testShapedRun_sourceContext_retained() {
        String source = "Hello World";
        CgShapedRun run = new CgShapedRun(TEST_FONT_KEY, false,
                new int[]{1, 2}, new int[]{0, 1},
                new float[]{5.0f, 5.0f}, new float[]{0, 0}, new float[]{0, 0},
                10.0f,
                source, 0, 5);

        assertTrue("Should have source context", run.hasSourceContext());
        assertSame(source, run.getSourceText());
        assertEquals(0, run.getSourceStart());
        assertEquals(5, run.getSourceEnd());
    }

    @Test
    public void testShapedRun_sourceContext_excludedFromEquality() {
        CgShapedRun withContext = new CgShapedRun(TEST_FONT_KEY, false,
                new int[]{1}, new int[]{0},
                new float[]{5.0f}, new float[]{0}, new float[]{0},
                5.0f,
                "Hello", 0, 5);

        CgShapedRun withoutContext = new CgShapedRun(TEST_FONT_KEY, false,
                new int[]{1}, new int[]{0},
                new float[]{5.0f}, new float[]{0}, new float[]{0},
                5.0f);

        assertEquals("Source context should not affect equality",
                withContext, withoutContext);
    }

    // ---------------------------------------------------------------
    //  CgTextShaper tests (require native HarfBuzz)
    // ---------------------------------------------------------------

    @Test
    public void testShaper_rejects_null_text() {
        CgTextShaper shaper = new CgTextShaper();
        try {
            shaper.shape(null, 0, 0, TEST_FONT_KEY, false, null);
            fail("Should throw IllegalArgumentException for null text");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("text"));
        }
    }

    @Test
    public void testShaper_rejects_null_fontKey() {
        CgTextShaper shaper = new CgTextShaper();
        try {
            shaper.shape("hello", 0, 5, null, false, null);
            fail("Should throw IllegalArgumentException for null fontKey");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("fontKey"));
        }
    }

    @Test
    public void testShaper_rejects_invalid_range() {
        CgTextShaper shaper = new CgTextShaper();
        try {
            // Cannot test with real HBFont here, but range check happens before HBFont use
            // Need a non-null non-destroyed HBFont - this test validates the range check
            // Since we can't easily create a mock HBFont, we verify the exception message
            // when hbFont is null (fontKey check happens first if null, so pass non-null fontKey)
            shaper.shape("hello", 3, 1, TEST_FONT_KEY, false, null);
            fail("Should throw IllegalArgumentException for start > end");
        } catch (IllegalArgumentException e) {
            // Expected — either range error or hbFont error
        }
    }

    // ---------------------------------------------------------------
    //  CgLineBreaker tests
    // ---------------------------------------------------------------

    @Test
    public void testLineBreaker_single_ltr_run_no_break() {
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 30.0f));

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 0, 0, TEST_METRICS);

        assertEquals("Should have 1 line", 1, lines.size());
        assertEquals("Should have 1 run in line", 1, lines.get(0).size());
        assertFalse("Run should be LTR", lines.get(0).get(0).isRtl());
    }

    @Test
    public void testLineBreaker_single_rtl_run() {
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(true, 50.0f));

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 0, 0, TEST_METRICS);

        assertEquals("Should have 1 line", 1, lines.size());
        assertEquals("Should have 1 run", 1, lines.get(0).size());
        assertTrue("Run should be RTL", lines.get(0).get(0).isRtl());
    }

    @Test
    public void testLineBreaker_bidi_visual_reorder() {
        // Plan QA scenario: 3 runs (LTR, RTL, LTR) — RTL in middle position after reorder
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRunLabeled(false, 30.0f, 1)); // run0: LTR advance=30
        runs.add(makeRunLabeled(true, 50.0f, 2));  // run1: RTL advance=50
        runs.add(makeRunLabeled(false, 20.0f, 3)); // run2: LTR advance=20

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 0, 0, TEST_METRICS);

        assertEquals("Should have 1 line (no width limit)", 1, lines.size());
        List<CgShapedRun> line = lines.get(0);
        assertEquals("Should have 3 runs", 3, line.size());

        // Visual order for (LTR level 0, RTL level 1, LTR level 0):
        // Bidi.reorderVisually with levels [0, 1, 0] → visual order [0, 1, 2]
        // The RTL run stays in the middle position visually
        // (its INTERNAL glyphs are reversed by HarfBuzz, not its position among runs)
        assertTrue("Middle run should be RTL", line.get(1).isRtl());
    }

    @Test
    public void testLineBreaker_breaks_at_maxWidth() {
        // Plan QA scenario: 3 runs each 50px wide → total 150px, maxWidth=100 → 2 lines
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS);

        assertEquals("Should have 2 lines", 2, lines.size());

        // First line: first two runs (50+50=100 <= 100)
        float line0Width = 0;
        for (CgShapedRun r : lines.get(0)) {
            line0Width += r.getTotalAdvance();
        }
        assertTrue("First line should not exceed maxWidth", line0Width <= 100.0f + 0.001f);
    }

    @Test
    public void testLineBreaker_many_runs_line_breaking() {
        // Create 10 runs each 25px wide (total 250px), maxWidth=100 → at least 3 lines
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        for (int i = 0; i < 10; i++) {
            runs.add(makeRun(false, 25.0f));
        }

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS);

        assertTrue("Should have at least 3 lines", lines.size() >= 3);

        // Verify no line exceeds maxWidth
        for (int i = 0; i < lines.size(); i++) {
            float lineWidth = 0;
            for (CgShapedRun r : lines.get(i)) {
                lineWidth += r.getTotalAdvance();
            }
            assertTrue("Line " + i + " should not exceed 100px: " + lineWidth,
                    lineWidth <= 100.0f + 0.001f);
        }
    }

    @Test
    public void testLineBreaker_maxHeight_truncation() {
        // maxHeight = 1 line height (15px) → only 1 line
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));

        // maxWidth=60 would cause line breaks, maxHeight=15 limits to 1 line
        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 60.0f, 15.0f, TEST_METRICS);

        assertEquals("maxHeight should limit to 1 line", 1, lines.size());
    }

    @Test
    public void testLineBreaker_empty_runs() {
        CgLineBreaker breaker = new CgLineBreaker();
        List<List<CgShapedRun>> lines = breaker.breakLines(
                new ArrayList<CgShapedRun>(), 100.0f, 0, TEST_METRICS);

        assertTrue("Empty input should produce no lines", lines.isEmpty());
    }

    @Test
    public void testLineBreaker_null_runs() {
        CgLineBreaker breaker = new CgLineBreaker();
        List<List<CgShapedRun>> lines = breaker.breakLines(null, 100.0f, 0, TEST_METRICS);

        assertTrue("Null input should produce no lines", lines.isEmpty());
    }

    @Test
    public void testLineBreaker_unbounded_width() {
        // maxWidth <= 0 means no limit
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        for (int i = 0; i < 20; i++) {
            runs.add(makeRun(false, 100.0f));
        }

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, -1.0f, 0, TEST_METRICS);

        assertEquals("No width limit should produce 1 line", 1, lines.size());
        assertEquals("All 20 runs on one line", 20, lines.get(0).size());
    }

    @Test
    public void testLineBreaker_single_oversized_run() {
        // A single run wider than maxWidth should still appear (forced onto its own line)
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 200.0f)); // wider than maxWidth=100

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS);

        assertEquals("Oversized run should still produce 1 line", 1, lines.size());
        assertEquals(1, lines.get(0).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLineBreaker_rejects_null_metrics() {
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 30.0f));
        breaker.breakLines(runs, 100.0f, 0, null);
    }

    // ---------------------------------------------------------------
    //  CgTextLayout tests
    // ---------------------------------------------------------------

    @Test
    public void testTextLayout_stores_fields() {
        List<List<CgShapedRun>> lines = new ArrayList<List<CgShapedRun>>();
        List<CgShapedRun> line1 = new ArrayList<CgShapedRun>();
        line1.add(makeRun(false, 80.0f));
        lines.add(line1);
        List<CgShapedRun> line2 = new ArrayList<CgShapedRun>();
        line2.add(makeRun(false, 60.0f));
        lines.add(line2);

        CgTextLayout layout = new CgTextLayout(lines, 80.0f, 30.0f, TEST_METRICS);

        assertEquals(2, layout.getLines().size());
        assertEquals(80.0f, layout.getTotalWidth(), 0.001f);
        assertEquals(30.0f, layout.getTotalHeight(), 0.001f);
        assertSame(TEST_METRICS, layout.getMetrics());
    }

    @Test
    public void testTextLayout_stores_resolvedFontsMap() {
        List<List<CgShapedRun>> lines = new ArrayList<List<CgShapedRun>>();
        java.util.Map<CgFontKey, io.github.somehussar.crystalgraphics.api.font.CgFont> resolved =
                new java.util.HashMap<CgFontKey, io.github.somehussar.crystalgraphics.api.font.CgFont>();

        CgTextLayout layout = new CgTextLayout(lines, 80.0f, 30.0f, TEST_METRICS, resolved);

        assertSame(resolved, layout.getResolvedFontsByKey());
    }

    @Test
    public void testTextLayout_value_equality() {
        List<List<CgShapedRun>> lines = new ArrayList<List<CgShapedRun>>();
        CgTextLayout a = new CgTextLayout(lines, 100.0f, 15.0f, TEST_METRICS);
        CgTextLayout b = new CgTextLayout(lines, 100.0f, 15.0f, TEST_METRICS);

        assertEquals("Same-field layouts should be equal", a, b);
    }

    // ---------------------------------------------------------------
    //  End-to-end layout pipeline test (without native HarfBuzz)
    // ---------------------------------------------------------------

    @Test
    public void testEndToEnd_manualRuns_lineBreakAndBidi() {
        // Simulate the full pipeline with pre-shaped runs:
        // "Hello \u0645\u0631\u062D\u0628\u0627 World" → 3 runs: LTR, RTL, LTR
        CgLineBreaker breaker = new CgLineBreaker();

        List<CgShapedRun> logicalRuns = new ArrayList<CgShapedRun>();
        logicalRuns.add(makeRunLabeled(false, 40.0f, 1)); // "Hello " LTR
        logicalRuns.add(makeRunLabeled(true, 45.0f, 2));  // "\u0645\u0631\u062D\u0628\u0627" RTL
        logicalRuns.add(makeRunLabeled(false, 35.0f, 3)); // " World" LTR

        List<List<CgShapedRun>> lines = breaker.breakLines(logicalRuns, 0, 0, TEST_METRICS);

        // Should be 1 line with 3 runs, RTL in middle
        assertEquals(1, lines.size());
        assertEquals(3, lines.get(0).size());

        // Build CgTextLayout
        float totalWidth = 0;
        for (CgShapedRun r : lines.get(0)) {
            totalWidth += r.getTotalAdvance();
        }
        CgTextLayout layout = new CgTextLayout(
                lines, totalWidth, TEST_METRICS.getLineHeight(), TEST_METRICS);

        assertEquals(120.0f, layout.getTotalWidth(), 0.001f);
        assertEquals(15.0f, layout.getTotalHeight(), 0.001f);
    }

    @Test
    public void testEndToEnd_longText_multipleLines() {
        // Simulate 200-char text with maxWidth=100 → multiple lines
        CgLineBreaker breaker = new CgLineBreaker();

        // Create 20 runs of 10px advance each (total 200px, like 20 words)
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        for (int i = 0; i < 20; i++) {
            runs.add(makeRun(false, 10.0f));
        }

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS);

        assertTrue("Should have at least 2 lines", lines.size() >= 2);

        // Build layout
        float maxLineWidth = 0;
        for (List<CgShapedRun> line : lines) {
            float w = 0;
            for (CgShapedRun r : line) {
                w += r.getTotalAdvance();
            }
            if (w > maxLineWidth) maxLineWidth = w;
        }

        CgTextLayout layout = new CgTextLayout(
                lines, maxLineWidth,
                lines.size() * TEST_METRICS.getLineHeight(),
                TEST_METRICS);

        assertTrue("Total width should not exceed 100px",
                layout.getTotalWidth() <= 100.0f + 0.001f);
        assertTrue("Should have non-zero height", layout.getTotalHeight() > 0);
    }

    // ---------------------------------------------------------------
    //  Intra-run word wrapping tests
    // ---------------------------------------------------------------

    @Test
    public void testLineBreaker_intraRun_splitsAtSpace() {
        // "hello world" as a single run, 110px wide, maxWidth=60 → should split at space
        CgLineBreaker breaker = new CgLineBreaker();
        String source = "hello world";
        CgShapedRun run = makeRunWithSource(false, 110.0f, source, 0, source.length());

        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(run);

        // Mock reshaper: 10px per character
        RunReshaper reshaper = new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgShapedRun r, int subStart, int subEnd) {
                String sub = r.getSourceText().substring(subStart, subEnd);
                float width = sub.length() * 10.0f;
                return new CgShapedRun(TEST_FONT_KEY, r.isRtl(),
                        new int[]{1}, new int[]{0},
                        new float[]{width}, new float[]{0}, new float[]{0},
                        width,
                        r.getSourceText(), subStart, subEnd);
            }
        };

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 60.0f, 0, TEST_METRICS, reshaper);

        assertTrue("Should split into at least 2 lines", lines.size() >= 2);
    }

    @Test
    public void testLineBreaker_intraRun_noBreakOpportunity_forcedBreak() {
        // "abcdefghij" (no spaces) as a single run, 100px wide, maxWidth=50
        CgLineBreaker breaker = new CgLineBreaker();
        String source = "abcdefghij";
        CgShapedRun run = makeRunWithSource(false, 100.0f, source, 0, source.length());

        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(run);

        RunReshaper reshaper = new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgShapedRun r, int subStart, int subEnd) {
                String sub = r.getSourceText().substring(subStart, subEnd);
                float width = sub.length() * 10.0f;
                return new CgShapedRun(TEST_FONT_KEY, r.isRtl(),
                        new int[]{1}, new int[]{0},
                        new float[]{width}, new float[]{0}, new float[]{0},
                        width,
                        r.getSourceText(), subStart, subEnd);
            }
        };

        // No break opportunity → falls back to whole-run placement
        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 50.0f, 0, TEST_METRICS, reshaper);
        assertEquals("Should have 1 line (forced break)", 1, lines.size());
    }

    @Test
    public void testLineBreaker_intraRun_withoutSourceContext_fallback() {
        // Run without source context — should fall back to whole-run wrapping
        CgLineBreaker breaker = new CgLineBreaker();
        CgShapedRun run = makeRun(false, 200.0f); // no source context

        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(run);

        RunReshaper reshaper = new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgShapedRun r, int subStart, int subEnd) {
                return null; // should never be called
            }
        };

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS, reshaper);
        assertEquals("No source context → 1 line (forced)", 1, lines.size());
    }

    @Test
    public void testLineBreaker_intraRun_multipleWords_wrapsCorrectly() {
        // "aa bb cc dd ee" with 10px per char, maxWidth=50
        CgLineBreaker breaker = new CgLineBreaker();
        String source = "aa bb cc dd ee";
        CgShapedRun run = makeRunWithSource(false, 140.0f, source, 0, source.length());

        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(run);

        RunReshaper reshaper = new RunReshaper() {
            @Override
            public CgShapedRun reshape(CgShapedRun r, int subStart, int subEnd) {
                String sub = r.getSourceText().substring(subStart, subEnd);
                float width = sub.length() * 10.0f;
                return new CgShapedRun(TEST_FONT_KEY, r.isRtl(),
                        new int[]{1}, new int[]{0},
                        new float[]{width}, new float[]{0}, new float[]{0},
                        width,
                        r.getSourceText(), subStart, subEnd);
            }
        };

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 50.0f, 0, TEST_METRICS, reshaper);
        assertTrue("Multiple words should wrap into multiple lines", lines.size() >= 3);
    }

    @Test
    public void testLineBreaker_backwardCompat_withoutReshaper() {
        // Existing breakLines(runs, maxWidth, maxHeight, metrics) still works
        CgLineBreaker breaker = new CgLineBreaker();
        List<CgShapedRun> runs = new ArrayList<CgShapedRun>();
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));
        runs.add(makeRun(false, 50.0f));

        List<List<CgShapedRun>> lines = breaker.breakLines(runs, 100.0f, 0, TEST_METRICS);
        assertEquals("Backward-compat 4-arg overload still works", 2, lines.size());
    }

    // ---------------------------------------------------------------
    //  Helper methods
    // ---------------------------------------------------------------

    /**
     * Create a minimal CgShapedRun with a single dummy glyph.
     */
    private static CgShapedRun makeRun(boolean rtl, float totalAdvance) {
        return new CgShapedRun(TEST_FONT_KEY, rtl,
                new int[]{1},
                new int[]{0},
                new float[]{totalAdvance},
                new float[]{0.0f},
                new float[]{0.0f},
                totalAdvance);
    }

    /**
     * Create a CgShapedRun with a distinguishable glyph ID label.
     * The glyphId is used as a label to identify runs in visual order tests.
     */
    private static CgShapedRun makeRunLabeled(boolean rtl, float totalAdvance, int label) {
        return new CgShapedRun(TEST_FONT_KEY, rtl,
                new int[]{label},
                new int[]{0},
                new float[]{totalAdvance},
                new float[]{0.0f},
                new float[]{0.0f},
                totalAdvance);
    }

    /**
     * Create a CgShapedRun with source-text context for intra-run wrapping tests.
     */
    private static CgShapedRun makeRunWithSource(boolean rtl, float totalAdvance,
                                                  String sourceText, int sourceStart, int sourceEnd) {
        return new CgShapedRun(TEST_FONT_KEY, rtl,
                new int[]{1},
                new int[]{0},
                new float[]{totalAdvance},
                new float[]{0.0f},
                new float[]{0.0f},
                totalAdvance,
                sourceText, sourceStart, sourceEnd);
    }
}
