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
 * <b>Synthetic bold costs a stroke nothing, which is not what it looks like.</b>
 *
 * <p>Bold is a bias added to every distance channel rather than a geometry edit — inflating control
 * points self-intersects on tight curves, which {@code MSDFShapeSynthesis} records. It plainly moves
 * the contour outward, so the reading that the outward headroom shrinks by the shift is the obvious
 * one, and it is wrong: a constant added to a linear field moves the contour AND both saturation ends
 * together. Measured on a real bold field, the outward reach is 5.50 texels with the bias and 5.50
 * without.</p>
 *
 * <p>So {@code text.shader} bounds both directions with one number and needs to know nothing about
 * weight. This exists to stop that being "fixed": a shift was plumbed through the quad's spare custom
 * slot on the strength of the obvious reading, and only measuring took it out again.</p>
 *
 * <p>The trap in measuring it is that msdfgen's float output is NOT clamped to [0,1] — a texel well
 * outside the shape carries a genuinely negative distance — while the bias clamps as it writes. Read
 * raw, the two fields look 6.6 texels apart; read as the 8-bit atlas stores them, they agree.</p>
 */
public class CgSyntheticBoldReachTest {

    /** Skia's synthetic-bold strength is an em over 24, half of it per edge. */
    private static final double EXPECTED_SHIFT_EM = 1.0 / 48.0;

    @Test
    public void boldSpendsOutwardReachAndNotInward() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "bold-reach-probe", CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            int glyphId = font.getGlyphIndex('H');
            assertTrue("the test font must have an H", glyphId > 0);

            float[] plain = generate(fontKey, msdfFont, glyphId, config, false);
            float[] bold = generate(fontKey, msdfFont, glyphId, config, true);

            // The field's own ends, in stored units. 0.5 is the contour; the distance either end sits
            // from it, times the stored range, is the reach in texels.
            float plainLow = min(plain), plainHigh = max(plain);
            float boldLow = min(bold), boldHigh = max(bold);

            float storedRange = config.pxRange() - 1f;   // the layout reserves a texel; @see CgMsdfGenerator
            double expectedShiftTexels = EXPECTED_SHIFT_EM * config.atlasScalePx();

            double plainOutward = (0.5f - plainLow) * storedRange;
            double boldOutward = (0.5f - boldLow) * storedRange;
            double plainInward = (plainHigh - 0.5f) * storedRange;
            double boldInward = (boldHigh - 0.5f) * storedRange;

            System.out.printf("[bold-reach] stored range %.1f texels, expected shift %.2f texels%n",
                    storedRange, expectedShiftTexels);
            System.out.printf("[bold-reach] outward reach: plain %.2f, bold %.2f (lost %.2f)%n",
                    plainOutward, boldOutward, plainOutward - boldOutward);
            System.out.printf("[bold-reach] inward  reach: plain %.2f, bold %.2f (lost %.2f)%n",
                    plainInward, boldInward, plainInward - boldInward);

            // The numbers CgTextRenderer#syntheticBoldShiftTexels and text.shader are built on. A
            // quarter-texel tolerance: the ends are sampled from a discrete grid, so the extreme texel
            // is wherever the glyph happens to put one, not exactly on the boundary.
            System.out.printf("[bold-reach] (the shift itself, if it cost anything: %.2f texels)%n",
                    expectedShiftTexels);

            // THE FACT THE SHADER RESTS ON. Not "close enough": the two are the same number, because
            // the field's ends translate with its contour. A quarter texel of slack for the grid.
            assertEquals("synthetic bold must not cost a stroke its outward reach -- if this moves, "
                            + "text.shader's single fieldReach is wrong for bold text",
                    plainOutward, boldOutward, 0.25);

            // And the inward end is the letterform's, not the range's: an H's stem is thinner than the
            // field is deep, so neither field ever reaches its inward saturation at all.
            assertTrue("the inward reach should be bounded by the stem, well inside the range",
                    plainInward < storedRange * 0.5 - 1.0 && boldInward < storedRange * 0.5);
        } finally {
            font.dispose();
        }
    }

    /**
     * <b>The size below which a distance field cannot antialias, which is msdfgen's rule and not a
     * taste.</b> A stroke reaches for the field tier down to here and no further.
     */
    @Test
    public void theAntialiasFloorIsTheOneMsdfgenStates() {
        CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
        int floor = config.minAntialiasablePx();

        // screenPxRange at that size, computed as text.shader computes it.
        double atFloor = (config.pxRange() - 1.0) * floor / config.atlasScalePx();
        double belowFloor = (config.pxRange() - 1.0) * (floor - 1) / config.atlasScalePx();

        System.out.printf("[aa-floor] %dpx: screenPxRange %.2f  |  %dpx: %.2f%n",
                floor, atFloor, floor - 1, belowFloor);

        assertTrue("at the floor the field must still clear msdfgen's 2, or its own shader's "
                + "antialiasing is documented to fail", atFloor >= 2.0);
        assertTrue("the floor must be the SMALLEST such size, or a stroke is refused at sizes that "
                + "would have drawn correctly", belowFloor < 2.0);
        assertTrue("a floor above the bitmap/field threshold would mean the stroke promotion can "
                + "never fire", floor < 33);
    }

    private static float min(float[] rgba) {
        float m = Float.MAX_VALUE;
        for (int i = 0; i < rgba.length; i += 4) m = Math.min(m, median(rgba, i));
        return m;
    }

    private static float max(float[] rgba) {
        float m = -Float.MAX_VALUE;
        for (int i = 0; i < rgba.length; i += 4) m = Math.max(m, median(rgba, i));
        return m;
    }

    /**
     * The median AS THE ATLAS STORES IT. msdfgen's float output runs past [0,1] -- a texel far outside
     * the shape carries a genuinely negative distance -- and the upload clamps it. Measuring the raw
     * floats answers a question no fragment ever asks.
     */
    private static float median(float[] rgba, int i) {
        float r = clamp01(rgba[i]), g = clamp01(rgba[i + 1]), b = clamp01(rgba[i + 2]);
        return Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float[] generate(CgFontKey fontKey, FreeTypeMSDFIntegration.Font msdfFont,
                                    int glyphId, CgMsdfAtlasConfig config, boolean syntheticBold) {
        CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0, syntheticBold, false);
        CgGlyphGenerationResult result = CgMsdfGenerator.prepareGlyph(
                key, fontKey, msdfFont, new CgMsdfAtlasKey(fontKey, config), config);
        assertNotNull("generation produced no result", result);
        float[] data = result.getMsdfData();
        assertNotNull("generation produced no pixels", data);
        return data;
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgSyntheticBoldReachTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
