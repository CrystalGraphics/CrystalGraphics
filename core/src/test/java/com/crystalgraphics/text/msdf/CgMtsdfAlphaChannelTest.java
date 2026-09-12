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
 * <b>Does MTSDF's fourth channel actually carry a true distance field?</b>
 *
 * <p>{@code text.shader}'s header has described it as "a true SDF intended for WIDE effects
 * (outlines, glows, soft shadows)" since the material was written, and in the same breath recorded
 * that "nothing has tested it because nothing samples it yet". The first thing to sample it — a text
 * stroke reading it for round joins — drew nothing at all, which is what a constant channel produces:
 * a constant distance makes the stroke's two coverage thresholds equal and their difference zero.</p>
 *
 * <p>So this asks the narrow question the header asked for, before anything is built on the answer.
 * Glow and soft shadow rest on the same channel, so a dead one is worth knowing about now rather than
 * three features later.</p>
 */
public class CgMtsdfAlphaChannelTest {

    @Test
    public void alphaCarriesAVaryingDistanceField() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "mtsdf-probe", CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            assertTrue("this test is meaningless unless the default config generates MTSDF",
                    config.mtsdf());

            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            // 'g' on purpose: its link to the bowl is one of the junctions where the median
            // reconstruction fails and the true distance is supposed to hold up.
            int glyphId = font.getGlyphIndex('g');
            assertTrue("the test font must have a 'g'", glyphId > 0);

            CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
            CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(fontKey, config);
            CgGlyphGenerationResult result =
                    CgMsdfGenerator.prepareGlyph(key, fontKey, msdfFont, atlasKey, config);

            assertNotNull("generation produced no result", result);
            float[] data = result.getMsdfData();
            assertNotNull("generation produced no pixels", data);
            assertEquals("MTSDF must be four channels", 0, data.length % 4);

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            int crossings = 0;
            for (int i = 3; i < data.length; i += 4) {
                float a = data[i];
                min = Math.min(min, a);
                max = Math.max(max, a);
                // A real signed field straddles the 0.5 contour: some texels inside, some outside.
                if (a > 0.5f) crossings++;
            }
            int texels = data.length / 4;

            System.out.printf("[mtsdf-alpha] texels=%d min=%.4f max=%.4f inside=%d (%.1f%%)%n",
                    texels, min, max, crossings, 100.0 * crossings / texels);

            assertTrue("alpha is constant at " + min + " — the channel carries no field, so every "
                            + "effect reading it (stroke round joins, glow, soft shadow) resolves to "
                            + "nothing. @see text.shader's header",
                    max - min > 0.05f);
            assertTrue("alpha never crosses the 0.5 contour, so it is not a signed distance",
                    crossings > 0 && crossings < texels);

            // HOW FAR APART THE TWO CHANNELS RUN, per texel, over the band a 2px stroke reads. Small,
            // and that is all this can say: a mean over the band cannot see a corner, because a corner
            // is a handful of texels out of hundreds. The question it was once read as answering --
            // whether a stroke off alpha would LOOK different -- is answered by comparing the
            // reconstructed bands instead, where they differ at corners and nowhere else.
            // @see CgStrokeFieldRangeTest#medianAndTrueDistanceDisagreeAtCorners
            double sum = 0;
            double worst = 0;
            int band = 0;
            for (int i = 0; i < data.length; i += 4) {
                float r = data[i], g = data[i + 1], bl = data[i + 2];
                float median = Math.max(Math.min(r, g), Math.min(Math.max(r, g), bl));
                float alpha = data[i + 3];
                if (median < 0.2f || median > 0.5f) continue;   // just outside the edge
                double diff = Math.abs(median - alpha);
                sum += diff;
                worst = Math.max(worst, diff);
                band++;
            }
            System.out.printf("[mtsdf-alpha] outside-band texels=%d meanAbsDiff=%.5f worst=%.5f%n",
                    band, band == 0 ? 0 : sum / band, worst);
        } finally {
            font.dispose();
        }
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgMtsdfAlphaChannelTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
