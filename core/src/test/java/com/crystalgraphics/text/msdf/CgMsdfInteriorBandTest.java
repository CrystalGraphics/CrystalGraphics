package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>The median DOES misreconstruct the glyph's interior — and it is not what an inward stroke
 * sees.</b> Two findings, and keeping them apart is the point of the class.
 *
 * <p>{@link CgMtsdfAlphaChannelTest} compared the median against MTSDF's true distance and found them
 * agreeing to 0.0005, but it sampled {@code median} in [0.2, 0.5] — the band OUTSIDE the contour,
 * which is the right band for {@code stroke-align: outset} and the wrong one for the two that reach
 * inward. Measured inside instead, the median deviates by up to <b>0.18</b> at a junction, against
 * <b>exactly 0.00000</b> on an {@code o}, which has no junction and is the control. So the classic
 * MSDF corner artifact is real, is interior, and had never been measured here.</p>
 *
 * <p><b>And it changes no pixel at a stroke this wide.</b> Reading the true distance instead of the
 * median moves the band by one texel on a {@code g} and none at all on an {@code h} or {@code n} —
 * so MTSDF's fourth channel is not a fix for anything visible, and the closed joint on a {@code g}'s
 * link or an {@code h}'s shoulder is the exact geometric answer rather than a reconstruction fault:
 * an inward stroke of half-width {@code w} leaves fill only where the ink is thicker than {@code 2w},
 * and a joint is THINNED BY THE TYPE DESIGNER so the merge does not read as a blot. The thinnest part
 * of the letterform is where an inward band closes first.</p>
 *
 * <p>The deviation is worth pinning anyway: it bounds how wide an inward effect can get before
 * reconstruction starts deciding pixels, which is the question a glow or an inner shadow will ask.</p>
 */
public class CgMsdfInteriorBandTest {

    /** Inward reach of the stroke under test, in atlas texels — about a 2px stroke at 72px. */
    private static final double BAND_TEXELS = 2.2;

    @Test
    public void theMedianDipsInsideJunctionsWhereTheTrueDistanceDoesNot() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "interior-probe", CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            assertTrue("meaningless unless the default config generates MTSDF", config.mtsdf());
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            double worstOverall = 0;
            double controlDeviation = 0;
            for (char c : new char[]{'h', 'g', 'n', 'o'}) {
                int glyphId = font.getGlyphIndex(c);
                if (glyphId <= 0) continue;
                double deviation = probe(font, fontKey, msdfFont, config, glyphId, c);
                if (c == 'o') controlDeviation = deviation;
                else worstOverall = Math.max(worstOverall, deviation);
            }

            assertTrue("the median and the true distance are expected to DISAGREE inside a junction "
                            + "by far more than the 0.0005 they agree to outside the contour. If this "
                            + "is now clean, the generator's corner handling improved.",
                    worstOverall > 0.02);
            assertEquals("an 'o' has no junction, so it is the control that says the deviation "
                            + "belongs to junctions and not to the field everywhere",
                    0.0, controlDeviation, 1.0e-6);

            // The finding that decides whether anything needs building: at this width the deviation
            // does not move the band, so reading MTSDF's .a instead of the median buys nothing.
            assertTrue("reading the true distance instead of the median should change almost no "
                            + "texel in the stroke band (" + bandDisagreement + " changed). If this "
                            + "grows, an inward effect got wide enough for reconstruction to decide "
                            + "pixels and the shader should sample .a for the inward reach.",
                    bandDisagreement <= 2);
        } finally {
            font.dispose();
        }
    }

    /** Texels whose stroke-band membership differs between the median and the true distance. */
    private static int bandDisagreement;

    /** @return the worst |median - alpha| found strictly inside this glyph. */
    private static double probe(CgFont font, CgFontKey fontKey, FreeTypeMSDFIntegration.Font msdfFont,
                                CgMsdfAtlasConfig config, int glyphId, char c) {
        CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
        CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(fontKey, config);
        CgGlyphGenerationResult result =
                CgMsdfGenerator.prepareGlyph(key, fontKey, msdfFont, atlasKey, config);
        assertNotNull("generation produced no result for '" + c + "'", result);
        float[] data = result.getMsdfData();
        assertNotNull("generation produced no pixels for '" + c + "'", data);

        int w = result.getWidth();
        int h = result.getHeight();
        double pxRange = result.getPxRange();
        // The band an inward stroke reads, in field value: 0.5 is the contour, and one texel of
        // distance is 1/pxRange of value.
        double bandTop = 0.5 + BAND_TEXELS / pxRange;

        double worst = 0;
        int worstX = -1;
        int worstY = -1;
        int medianOnly = 0;
        int alphaOnly = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = 4 * (y * w + x);
                double median = median3(data[i], data[i + 1], data[i + 2]);
                double alpha = data[i + 3];

                // Strictly inside by the true distance, so the comparison is over the region an
                // inward stroke can reach and not over the antialiased edge itself.
                if (alpha > 0.5) {
                    double diff = Math.abs(median - alpha);
                    if (diff > worst) {
                        worst = diff;
                        worstX = x;
                        worstY = y;
                    }
                }
                boolean inBandMedian = median >= 0.5 && median <= bandTop;
                boolean inBandAlpha = alpha >= 0.5 && alpha <= bandTop;
                if (inBandMedian && !inBandAlpha) medianOnly++;
                if (inBandAlpha && !inBandMedian) alphaOnly++;
            }
        }
        System.out.printf("[interior] '%c' %dx%d pxRange=%.1f band<=%.3f  worstInsideDiff=%.5f at (%d,%d)"
                        + "  strokeTexels median-only=%d alpha-only=%d%n",
                c, w, h, pxRange, bandTop, worst, worstX, worstY, medianOnly, alphaOnly);
        printMap(data, w, h, bandTop, c);
        bandDisagreement = Math.max(bandDisagreement, medianOnly + alphaOnly);
        return worst;
    }

    /**
     * The glyph drawn twice over: what an inward stroke would paint reading the median, and what it
     * would paint reading the true distance. A number cannot say whether the difference is a junction
     * or the whole contour, and that is the only thing worth knowing.
     */
    private static void printMap(float[] data, int w, int h, double bandTop, char c) {
        int step = Math.max(1, w / 60);
        System.out.printf("[interior] '%c'  M = median says stroke, A = true distance says stroke, "
                + "# = both, . = fill, space = outside%n", c);
        for (int y = 0; y < h; y += step) {
            StringBuilder line = new StringBuilder("[interior]   ");
            for (int x = 0; x < w; x += step) {
                int i = 4 * (y * w + x);
                double median = median3(data[i], data[i + 1], data[i + 2]);
                double alpha = data[i + 3];
                boolean bm = median >= 0.5 && median <= bandTop;
                boolean ba = alpha >= 0.5 && alpha <= bandTop;
                line.append(bm && ba ? '#' : bm ? 'M' : ba ? 'A' : alpha > 0.5 ? '.' : ' ');
            }
            System.out.println(line);
        }
    }

    private static double median3(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgMsdfInteriorBandTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
