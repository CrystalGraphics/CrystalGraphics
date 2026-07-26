package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgShapedRun;
import org.junit.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Direct tests for {@link CgTextLayoutEngine}'s bake step ({@code bakeGlyphs},
 * {@code computeJustifiable}, {@code collectLineBreakBoundaries}) — package-private so
 * these can be exercised with hand-built {@link CgShapedRun} fixtures, avoiding the need
 * for native HarfBuzz/FreeType (the same isolation approach {@code CgLineBreaker}'s own
 * tests use).
 */
public class CgTextLayoutEngineBakeTest {

    private static final CgFontKey TEST_FONT_KEY =
            new CgFontKey("test-font.ttf", CgFontStyle.REGULAR, 12);

    /** ascender=11, descender=3, lineGap=1, lineHeight=15, xHeight=7, capHeight=10 */
    private static final CgFontMetrics TEST_METRICS =
            new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);

    private static final float CHAR_ADVANCE = 10.0f;

    // ---------------------------------------------------------------
    //  bakeGlyphs
    // ---------------------------------------------------------------

    @Test
    public void testBakeGlyphs_penPositionsAndLineStructure() {
        // Line 0: two runs, "Hi" (2 glyphs) + " there" (6 glyphs) = 8 glyphs
        // Line 1: one run, "World" (5 glyphs)
        CgShapedRun runA = makeAsciiRun("Hi", 0, 2);
        CgShapedRun runB = makeAsciiRun(" there", 2, 8);
        CgShapedRun runC = makeAsciiRun("World", 0, 5);

        List<CgShapedRun> line0 = new ArrayList<>();
        line0.add(runA);
        line0.add(runB);
        List<CgShapedRun> line1 = new ArrayList<>();
        line1.add(runC);

        List<List<CgShapedRun>> lines = new ArrayList<>();
        lines.add(line0);
        lines.add(line1);

        List<boolean[]> justifiableByLine = new ArrayList<>();
        justifiableByLine.add(new boolean[8]);
        justifiableByLine.add(new boolean[5]);

        CgBakedGlyphs baked = CgTextLayoutEngine.bakeGlyphs(lines, justifiableByLine, TEST_METRICS, 0f);

        assertEquals(13, baked.glyphCount());
        assertArrayEquals(new int[]{0, 8, 13}, baked.lineStart());
        assertArrayEquals(new float[]{15.0f, 15.0f}, baked.lineHeight(), 0.001f);

        // Line 0 baseline = ascender (11) + 0*lineHeight; pen X accumulates by CHAR_ADVANCE per glyph
        for (int i = 0; i < 8; i++) {
            assertEquals("glyph " + i + " penX", i * CHAR_ADVANCE, baked.penX()[i], 0.001f);
            assertEquals("glyph " + i + " penY", 11.0f, baked.penY()[i], 0.001f);
            assertEquals("glyph " + i + " offsetX", 0.0f, baked.offsetX()[i], 0.001f);
            assertSame(TEST_FONT_KEY, baked.fontKeys()[i]);
        }

        // Line 1 baseline = ascender (11) + 1*lineHeight (15) = 26; pen X resets per line
        for (int i = 8; i < 13; i++) {
            int glyphInLine = i - 8;
            assertEquals("glyph " + i + " penX", glyphInLine * CHAR_ADVANCE, baked.penX()[i], 0.001f);
            assertEquals("glyph " + i + " penY", 26.0f, baked.penY()[i], 0.001f);
        }
    }

    @Test
    public void testBakeGlyphs_emptyLayout() {
        CgBakedGlyphs baked = CgTextLayoutEngine.bakeGlyphs(
                new ArrayList<>(), new ArrayList<>(), TEST_METRICS, 0f);

        assertEquals(0, baked.glyphCount());
        assertArrayEquals(new int[]{0}, baked.lineStart());
    }

    // ---------------------------------------------------------------
    //  computeJustifiable / collectLineBreakBoundaries
    // ---------------------------------------------------------------

    @Test
    public void testComputeJustifiable_flagsWordBoundaries_exceptLastOnLine() {
        // "aa bb cc" — UAX#14 line-break opportunities fall after each space
        // (before "bb" and before "cc"); the last one on the line is excluded.
        String text = "aa bb cc";
        CgShapedRun run = makeAsciiRun(text, 0, text.length());
        List<CgShapedRun> line = new ArrayList<>();
        line.add(run);

        BitSet boundaries = CgTextLayoutEngine.collectLineBreakBoundaries(text);
        boolean[] justifiable = CgTextLayoutEngine.computeJustifiable(text, boundaries, line);

        assertEquals(text.length(), justifiable.length);
        boolean[] expected = new boolean[text.length()];
        expected[3] = true; // 'b' of "bb", right after the first space boundary
        // index 6 ('c' of "cc") would also be flagged, but it's the last on the line — excluded
        assertArrayEquals(expected, justifiable);
    }

    @Test
    public void testComputeJustifiable_singleWord_noBoundaries() {
        String text = "hello";
        CgShapedRun run = makeAsciiRun(text, 0, text.length());
        List<CgShapedRun> line = new ArrayList<>();
        line.add(run);

        BitSet boundaries = CgTextLayoutEngine.collectLineBreakBoundaries(text);
        boolean[] justifiable = CgTextLayoutEngine.computeJustifiable(text, boundaries, line);

        for (boolean b : justifiable) {
            assertFalse("Single unbroken word should have no justification points", b);
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    /**
     * One glyph per char, ASCII only (so HarfBuzz-style UTF-8 byte cluster id == char index),
     * fixed {@link #CHAR_ADVANCE} per glyph, zero offsets.
     */
    private static CgShapedRun makeAsciiRun(String segment, int sourceStart, int sourceEnd) {
        int n = segment.length();
        int[] glyphIds = new int[n];
        int[] clusterIds = new int[n];
        float[] advancesX = new float[n];
        float[] offsetsX = new float[n];
        float[] offsetsY = new float[n];
        float total = 0;
        for (int i = 0; i < n; i++) {
            glyphIds[i] = segment.charAt(i);
            clusterIds[i] = i;
            advancesX[i] = CHAR_ADVANCE;
            total += CHAR_ADVANCE;
        }
        return new CgShapedRun().fontKey(TEST_FONT_KEY).resolvedFont(null).rtl(false)
                .glyphIds(glyphIds).clusterIds(clusterIds).advancesX(advancesX).offsetsX(offsetsX).offsetsY(offsetsY)
                .totalAdvance(total).sourceStart(sourceStart).sourceEnd(sourceEnd);
    }
}
