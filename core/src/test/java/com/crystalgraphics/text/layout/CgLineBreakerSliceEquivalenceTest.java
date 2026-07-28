package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgBakedGlyphs;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.AfterClass;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves the line breaker's slice fast path produces <em>identical</em> output to re-shaping.
 *
 * <p>{@code CgLineBreaker.buildFragments} used to call HarfBuzz twice for every line break. When
 * HarfBuzz marks a boundary safe-to-break, the already-shaped glyphs on either side are by
 * definition what a fresh shape would produce, so the run can be sliced instead. That is a large
 * win — breakLines was 43% of a reshape at 1000 wrapped labels — but it is also the kind of change
 * that corrupts text subtly rather than loudly: a mis-rebased cluster id or a dropped offset shows
 * up as glyphs drawn at wrong positions, not as an exception.
 *
 * <p>So rather than assert properties of the sliced output, this wraps identical text <em>both
 * ways</em> via {@link CgLineBreaker#sliceFastPathDisabled} and compares every glyph. If the two
 * paths ever diverge, this fails.
 */
public class CgLineBreakerSliceEquivalenceTest {

    private static final String[] FONT_CANDIDATES = {
            "src/main/resources/assets/crystalgraphics/IBMPlexSans-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf",
    };

    /** Widths chosen to force breaks at many different boundaries, including very tight ones. */
    private static final float[] WRAP_WIDTHS = {40f, 55f, 70f, 90f, 120f, 200f};

    private static final String[] TEXTS = {
            "Item 0042 Lorem ipsu",
            "The quick brown fox jumps over the lazy dog",
            "supercalifragilisticexpialidocious and then some short words",
            "a b c d e f g h i j k l m n o p",
            "Trailing spaces   and  double  spaces here",
            "Hyphen-ated words and slash/separated tokens",
    };

    private static CgFont font;

    private static CgFontFamily family() {
        if (font == null) {
            for (String path : FONT_CANDIDATES) {
                File f = new File(path);
                if (f.isFile()) {
                    font = CgFont.load(f.getPath(), CgFontStyle.REGULAR, 16);
                    break;
                }
            }
        }
        return font == null ? null : CgFontFamily.of(font);
    }

    @AfterClass
    public static void tearDown() {
        CgLineBreaker.sliceFastPathDisabled = false;
        if (font != null) {
            font.dispose();
            font = null;
        }
    }

    @Test
    public void slicedWrapMatchesReshapedWrapExactly() {
        CgFontFamily family = family();
        if (family == null) {
            System.out.println("[slice-equivalence] no test font available, skipping");
            return;
        }

        int comparisons = 0;
        for (String text : TEXTS) {
            for (float width : WRAP_WIDTHS) {
                CgTextLayout sliced = layoutWith(text, family, width, false);
                CgTextLayout reshaped = layoutWith(text, family, width, true);
                assertLayoutsIdentical(text, width, reshaped, sliced);
                comparisons++;
            }
        }
        assertTrue("expected some comparisons", comparisons > 0);
        System.out.println("[slice-equivalence] " + comparisons
                + " text/width combinations identical between slice and reshape paths");
    }

    private static CgTextLayout layoutWith(String text, CgFontFamily family, float width, boolean forceReshape) {
        CgLineBreaker.sliceFastPathDisabled = forceReshape;
        try {
            // A fresh shape each time: the slice path mutates nothing, but sharing a
            // CgShapedParagraph across both runs would let its layout memo return the first
            // result for the second call and make this test vacuous.
            return CgTextLayout.of(text, family).shape().layout(width, 0f);
        } finally {
            CgLineBreaker.sliceFastPathDisabled = false;
        }
    }

    private static void assertLayoutsIdentical(String text, float width,
                                               CgTextLayout expected, CgTextLayout actual) {
        String what = "text=\"" + text + "\" width=" + width;

        assertEquals(what + " line count", expected.lines().size(), actual.lines().size());
        assertEquals(what + " totalWidth", expected.totalWidth(), actual.totalWidth(), 0.001f);
        assertEquals(what + " totalHeight", expected.totalHeight(), actual.totalHeight(), 0.001f);

        for (int lineIndex = 0; lineIndex < expected.lines().size(); lineIndex++) {
            List<CgShapedRun> expectedRuns = expected.lines().get(lineIndex);
            List<CgShapedRun> actualRuns = actual.lines().get(lineIndex);
            assertEquals(what + " line " + lineIndex + " run count",
                    expectedRuns.size(), actualRuns.size());

            for (int runIndex = 0; runIndex < expectedRuns.size(); runIndex++) {
                assertRunsIdentical(what + " line " + lineIndex + " run " + runIndex,
                        expectedRuns.get(runIndex), actualRuns.get(runIndex));
            }
        }

        // Baked glyphs are what actually reaches the renderer, so compare those too rather than
        // trusting that identical runs imply identical output.
        CgBakedGlyphs expectedBaked = expected.baked();
        CgBakedGlyphs actualBaked = actual.baked();
        assertEquals(what + " baked glyph count",
                expectedBaked.glyphCount(), actualBaked.glyphCount());
        for (int i = 0; i < expectedBaked.glyphCount(); i++) {
            assertEquals(what + " baked glyphId[" + i + "]",
                    expectedBaked.glyphIds()[i], actualBaked.glyphIds()[i]);
            assertEquals(what + " baked penX[" + i + "]",
                    expectedBaked.penX()[i], actualBaked.penX()[i], 0.001f);
            assertEquals(what + " baked penY[" + i + "]",
                    expectedBaked.penY()[i], actualBaked.penY()[i], 0.001f);
            assertEquals(what + " baked offsetX[" + i + "]",
                    expectedBaked.offsetX()[i], actualBaked.offsetX()[i], 0.001f);
            assertEquals(what + " baked argbColor[" + i + "]",
                    expectedBaked.argbColor()[i], actualBaked.argbColor()[i]);
        }
    }

    private static void assertRunsIdentical(String what, CgShapedRun expected, CgShapedRun actual) {
        assertEquals(what + " sourceStart", expected.sourceStart(), actual.sourceStart());
        assertEquals(what + " sourceEnd", expected.sourceEnd(), actual.sourceEnd());
        assertEquals(what + " rtl", expected.rtl(), actual.rtl());
        assertEquals(what + " totalAdvance", expected.totalAdvance(), actual.totalAdvance(), 0.001f);
        assertArrayEqualsInt(what + " glyphIds", expected.glyphIds(), actual.glyphIds());
        assertArrayEqualsInt(what + " clusterIds", expected.clusterIds(), actual.clusterIds());
        assertArrayEqualsFloat(what + " advancesX", expected.advancesX(), actual.advancesX());
        assertArrayEqualsFloat(what + " offsetsX", expected.offsetsX(), actual.offsetsX());
        assertArrayEqualsFloat(what + " offsetsY", expected.offsetsY(), actual.offsetsY());
    }

    private static void assertArrayEqualsInt(String what, int[] expected, int[] actual) {
        assertEquals(what + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(what + "[" + i + "]", expected[i], actual[i]);
        }
    }

    private static void assertArrayEqualsFloat(String what, float[] expected, float[] actual) {
        assertEquals(what + " length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(what + "[" + i + "]", expected[i], actual[i], 0.001f);
        }
    }
}
