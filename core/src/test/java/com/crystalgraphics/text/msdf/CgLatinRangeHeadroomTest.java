package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.api.font.CgGlyphKey;
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

    /**
     * Candidate bands: {atlasScalePx, pxRange}. The shipping narrow band FIRST, since the area
     * column is relative to it. 80/6 is where this started and is kept as the floor: it is what a
     * 0.019em ceiling costs, which drew an authored 2px outline the same as an 8px one.
     */
    private static final int[][] BANDS = {{80, 12}, {80, 6}, {80, 24}, {64, 24}, {48, 16}, {48, 24}};

    private static final int SAMPLE_GLYPHS = 24;
    private static final float BAND_LOW = 0.88f;
    private static final float BAND_HIGH = 0.98f;
    private static final int MAX_SCANNED = 3000;

    /** Every non-dense face on the test classpath: Latin, and a connected script with thin joins. */
    private static final String[] NON_DENSE_FONTS = {
            "src/test/resources/fonts/IBMPlexSans-Regular.ttf",
            "src/test/resources/fonts/IBMPlexSansArabic-Regular.ttf",
            "src/test/resources/fonts/NotoSansArabic-Regular.ttf",
    };

    /**
     * <b>The band a non-dense face is given costs it no shape, measured on every such face we ship.</b>
     *
     * <p>This is the assertion the whole banding rests on: widening the RANGE must not lose structure
     * the narrow band kept. It holds because the atlas SCALE does not move -- the glyph is rasterised
     * at exactly the resolution it always was, and only the padding around it grows.</p>
     *
     * <p>The rejected alternative is printed beside it to keep the reason on record. Dropping the
     * scale to 48 reaches a similar ceiling for less memory and loses shape doing it: on Noto Sans
     * Arabic it costs defective glyphs at any range, while 80/12 and 80/24 are indistinguishable.
     * Arabic is the stress here rather than Latin -- connected letterforms with thin joins, the
     * nearest thing to a dense script outside CJK.</p>
     */
    @Test
    public void theWideBandCostsNoShapeOnAnyNonDenseFaceWeShip() {
        CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
        CgMsdfAtlasConfig wide = base.withPxRange(CgMsdfAtlasConfig.WIDE_PX_RANGE);
        CgMsdfAtlasConfig rejected = base.withAtlasScalePx(48).withPxRange(16);
        int evalPx = CgMsdfQualityProbe.DEFAULT_EVAL_PX;

        System.out.printf(Locale.ROOT, "=== shipped band %d/%.0f (%.4f em) vs narrow %d/%.0f and the "
                        + "rejected 48/16 ===%n",
                wide.atlasScalePx(), wide.pxRange(), wide.maxStrokeWidthEm(),
                base.atlasScalePx(), base.pxRange());

        for (String path : NON_DENSE_FONTS) {
            File file = new File(path);
            if (!file.isFile()) continue;
            CgFont font = CgFont.load(file.getPath(), CgFontStyle.REGULAR, 80);
            try {
                FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
                int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(
                        font, SAMPLE_GLYPHS, BAND_LOW, BAND_HIGH, MAX_SCANNED);
                if (sample.length == 0) continue;

                int narrowFloat = defects(msdfFont, base, sample, evalPx, false);
                int wideFloat = defects(msdfFont, wide, sample, evalPx, false);
                int wideByte = defects(msdfFont, wide, sample, evalPx, true);
                int scaledFloat = defects(msdfFont, rejected, sample, evalPx, false);

                System.out.printf(Locale.ROOT,
                        "  %-42s %2d glyphs | narrow %2d | wide %2d (8-bit %2d) | scale-48 %2d%n",
                        file.getName(), sample.length, narrowFloat, wideFloat, wideByte, scaledFloat);

                assertTrue(file.getName() + " loses shape on the wide band -- widening the range is "
                                + "supposed to cost nothing, since the raster scale does not move",
                        wideFloat <= narrowFloat);
                assertTrue(file.getName() + " loses structure to 8-bit storage on the wide band",
                        wideByte <= wideFloat);
            } finally {
                font.dispose();
            }
        }
    }

    private static int defects(FreeTypeMSDFIntegration.Font msdfFont, CgMsdfAtlasConfig config,
                               int[] sample, int evalPx, boolean eightBit) {
        return CgMsdfQualityProbe.evaluate(msdfFont, config, sample, evalPx,
                eightBit ? CgMsdfQualityProbe.FieldStorage.UNORM8
                        : CgMsdfQualityProbe.FieldStorage.HALF_FLOAT).defectiveGlyphs();
    }

    /** The cell a glyph would occupy under a candidate band, in texels. */
    private static int cellArea(CgFont font, FreeTypeMSDFIntegration.Font msdfFont,
                                CgMsdfAtlasConfig config, char c) {
        CgGlyphKey key = new CgGlyphKey(font.getKey(), font.getGlyphIndex(c), true, 0, false, false);
        CgGlyphGenerationResult r = CgMsdfGenerator.prepareGlyph(
                key, font.getKey(), msdfFont, new CgMsdfAtlasKey(font.getKey(), config), config);
        return r == null ? 0 : r.getWidth() * r.getHeight();
    }

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

            // WHICH BAND TO GIVE A LATIN FACE. Reach and cell area pull against each other: a lower
            // scale shrinks the glyph's own box but the padding is the range, so it grows back. The
            // numbers decide it rather than the intuition that a smaller raster is cheaper.
            System.out.println();
            System.out.println("band (scale/range) | ceiling em | 'g' cell | area vs shipping | structure");
            int shippingArea = 0;
            for (int[] band : BANDS) {
                CgMsdfAtlasConfig candidate = base.withAtlasScalePx(band[0]).withPxRange(band[1]);
                CgMsdfQualityProbe.FieldQuality half = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.HALF_FLOAT);
                CgMsdfQualityProbe.FieldQuality byte8 = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.UNORM8);
                boolean holds = byte8.defectiveGlyphs() <= half.defectiveGlyphs();

                double ceilingEm = ((band[1] - 1) / 2.0 - 1.0) / band[0];
                int area = cellArea(font, msdfFont, candidate, 'g');
                if (shippingArea == 0) shippingArea = area;

                System.out.printf(Locale.ROOT, "      %3d / %-5d    |   %.4f   |  %6d  |      %.2fx       | %s%n",
                        band[0], band[1], ceilingEm, area, area / (double) shippingArea,
                        holds ? "held" : "LOST");
            }
        } finally {
            font.dispose();
        }
    }
}
