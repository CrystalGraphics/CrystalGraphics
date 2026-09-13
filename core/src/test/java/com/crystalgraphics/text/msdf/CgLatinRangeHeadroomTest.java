package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * <b>How much wider a range Latin could carry than the shared atlas is allowed.</b>
 *
 * <p>The stroke ceiling is the stored range, and the range is capped at 12 by what eight bits can
 * hold: one level is {@code storedRange / 255} of distance, and past 12 that is coarse enough to
 * merge strokes a dense kanji keeps apart — {@link CgMsdfFieldStorageTest} fails at 13. The ceiling
 * is therefore a property of the DENSEST script in a shared atlas, not of the technique.</p>
 *
 * <p>This measures the same question on a Latin face, which has no comparable structure to lose: a
 * stem is 0.0875 em against a kanji's stroke gaps of about a fiftieth. If Latin holds at ranges the
 * shared atlas cannot, then a per-font range is worth the banding it costs, and the number this
 * prints is what it would buy. @see {@code plan/crystalgraphics/text-stroke-field-range.md}</p>
 *
 * <p>It asserts only that Latin holds where the shared limit is, and reports the rest: the point is
 * evidence for a decision, not a ceiling pinned before anything is built on it.</p>
 */
public class CgLatinRangeHeadroomTest {

    private static final String LATIN_FONT = "src/test/resources/fonts/IBMPlexSans-Regular.ttf";

    /** The shipping range, then the ones dense CJK refused. */
    private static final float[] RANGES = {12f, 16f, 20f, 24f};

    private static final int SAMPLE_GLYPHS = 24;
    private static final float BAND_LOW = 0.88f;
    private static final float BAND_HIGH = 0.98f;
    private static final int MAX_SCANNED = 3000;

    @Test
    public void latinHoldsAtRangesDenseCjkRefuses() {
        File fontFile = new File(LATIN_FONT);
        assertTrue("Latin font missing at " + fontFile.getAbsolutePath(), fontFile.isFile());

        CgFont font = CgFont.load(fontFile.getPath(), CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            int evalPx = CgMsdfQualityProbe.DEFAULT_EVAL_PX;

            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(
                    font, SAMPLE_GLYPHS, BAND_LOW, BAND_HIGH, MAX_SCANNED);
            assertTrue("selection produced no glyphs", sample.length > 0);

            System.out.println("=== 8-bit storage across ranges: IBM Plex Sans at scale "
                    + base.atlasScalePx() + ", " + sample.length + " glyphs ===");
            System.out.println("pxRange | ceiling em |        HALF_FLOAT        |          UNORM8          | structure");

            boolean shippingRangeHolds = false;
            for (float pxRange : RANGES) {
                CgMsdfAtlasConfig candidate = base.withPxRange(pxRange);
                CgMsdfQualityProbe.FieldQuality half = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.HALF_FLOAT);
                CgMsdfQualityProbe.FieldQuality byte8 = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.UNORM8);

                int defectDelta = byte8.defectiveGlyphs() - half.defectiveGlyphs();
                boolean holds = defectDelta <= 0;
                double ceilingEm = ((pxRange - 1) / 2.0 - 1.0) / base.atlasScalePx();

                System.out.printf(Locale.ROOT,
                        "   %5.0f |   %.4f   | %8.6f  %7d  | %8.6f  %7d  | %s%n",
                        pxRange, ceilingEm,
                        half.worstMismatch(), half.defectiveGlyphs(),
                        byte8.worstMismatch(), byte8.defectiveGlyphs(),
                        holds ? "held" : "LOST " + defectDelta);

                if (pxRange == base.pxRange()) shippingRangeHolds = holds;
            }

            assertTrue("Latin lost structure at the range the shared atlas already ships, which would "
                    + "mean the 8-bit limit is not about dense scripts at all", shippingRangeHolds);
        } finally {
            font.dispose();
        }
    }
}
