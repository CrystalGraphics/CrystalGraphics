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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>How wide is the antialiasing ramp at each of a stroked glyph's two edges?</b>
 *
 * <p>A stroked glyph has two boundaries and {@code text.shader} derives them from different
 * thresholds: the outer one from {@code strokeDist + outward}, the inner one from
 * {@code strokeDist - inward}. Both should resolve over one pixel. This walks a scanline across a
 * straight stem, bilinear-sampling the real atlas the way the GPU does and running the shader's own
 * arithmetic, and reports the 10%-to-90% width of each transition.</p>
 *
 * <p>An ideal one-pixel clamp ramp measures 0.8px between those points. Materially more than that at
 * one edge and not the other says the field's gradient has gone soft where that threshold sits —
 * which is what happens as a threshold approaches the stored range and the field flattens out.</p>
 */
public class CgStrokeEdgeRampTest {

    /** Where the gallery draws: font-size 72 against an 80px atlas. */
    private static final double DISPLAY_PX_PER_EM = 72.0;

    @Test
    public void bothEdgesResolveOverAboutOnePixel() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "ramp-probe", CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            int glyphId = font.getGlyphIndex('h');
            assertTrue("the test font must have an 'h'", glyphId > 0);
            CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
            CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(fontKey, config);
            CgGlyphGenerationResult result =
                    CgMsdfGenerator.prepareGlyph(key, fontKey, msdfFont, atlasKey, config);
            assertNotNull(result);
            float[] data = result.getMsdfData();
            int w = result.getWidth();
            int h = result.getHeight();
            double pxRange = result.getPxRange();
            double atlasScale = config.atlasScalePx();

            // One display pixel is this many atlas texels, which is what fwidth measures on the GPU.
            double texelsPerPixel = atlasScale / DISPLAY_PX_PER_EM;
            double screenPxRange = pxRange / texelsPerPixel;   // pxRange is the result's, not the config's
            double fieldReach = Math.max(screenPxRange * 0.5 - 1.0, 0.0);
            System.out.printf("[ramp] atlasScale=%.0f display=%.0f pxRange=%.1f -> screenPxRange=%.3f "
                            + "fieldReach=%.3fpx%n",
                    atlasScale, DISPLAY_PX_PER_EM, pxRange, screenPxRange, fieldReach);

            int scanY = stemScanline(data, w, h);
            System.out.printf("[ramp] scanning the stem at atlas row %d%n", scanY);

            // What the shader ASSUMES is that one texel of stored value spans 1/pxRange, so that
            // screenPxDist advances one per display pixel and  is a one-pixel ramp. Measured
            // here instead, because a ramp coming out narrower than 0.8px can only mean the stored
            // field is steeper than its nominal range says.
            double slope = edgeSlopePerTexel(data, w, scanY);
            System.out.printf("[ramp] field slope = %.5f value/texel  (nominal 1/pxRange = %.5f)"
                            + "  -> effective pxRange %.2f%n",
                    slope, 1.0 / pxRange, 1.0 / slope);

            double widest = 0;
            for (double strokePx : new double[]{1.0, 2.0, 2.7, 4.0, 8.0}) {
                widest = Math.max(widest, report(data, w, scanY, pxRange, texelsPerPixel,
                        screenPxRange, fieldReach, strokePx));
            }

            // A one-pixel clamp ramp measures 0.8px between the 10% and 90% points. Both edges of a
            // stroke are that same clamp on the same field, so neither has any business being far off
            // it -- a materially wider one would mean the field had gone flat where that threshold
            // sits, which is what saturation does and what fieldReach exists to keep clear of.
            assertTrue("a stroke edge resolved over " + widest + "px, well past the 0.8px a "
                            + "one-pixel ramp measures. The field has gone soft where a threshold "
                            + "sits -- check fieldReach against the stored range.",
                    widest < 1.4);
        } finally {
            font.dispose();
        }
    }

    /** @return the wider of this stroke's two edge ramps, in display pixels. */
    private static double report(float[] data, int w, int scanY, double pxRange,
                                  double texelsPerPixel, double screenPxRange, double fieldReach,
                                  double strokePx) {
        // CENTER: half the width each way, which is what the gallery's align rows draw.
        double wantOutward = strokePx * 0.5;
        double outward = Math.min(wantOutward, fieldReach);
        double inward = Math.min(strokePx - wantOutward, fieldReach);

        // Walk in display pixels across the stem's left edge, in the same direction the field grows.
        double startTexel = 2.0;
        double endTexel = w - 2.0;
        int steps = 2000;
        double[] pos = new double[steps];
        double[] strokeWeight = new double[steps];
        double[] fillWeight = new double[steps];
        for (int i = 0; i < steps; i++) {
            double texelX = startTexel + (endTexel - startTexel) * i / (steps - 1.0);
            double median = sampleMedian(data, w, texelX, scanY);
            double screenPxDist = screenPxRange * (median - 0.5);

            double ringOuter = clamp(screenPxDist + outward + 0.5);
            double ringInner = clamp(screenPxDist - inward + 0.5);
            double opacity = clamp(screenPxDist + 0.5);
            double strokeCoverage = Math.max(ringOuter - ringInner, 0.0);
            double union = Math.max(ringOuter, opacity);

            pos[i] = texelX / texelsPerPixel;             // display pixels
            strokeWeight[i] = clamp(strokeCoverage);       // stroke over fill
            fillWeight[i] = clamp(union - strokeCoverage);
        }

        double outerRamp = rampWidth(pos, strokeWeight);
        double innerRamp = rampWidth(pos, fillWeight);
        boolean clamped = outward < wantOutward - 1.0e-9 || inward < strokePx - wantOutward - 1.0e-9;
        System.out.printf("[ramp] stroke=%.1fpx center (out=%.2f in=%.2f%s)  outer 10-90 = %.2fpx   "
                        + "inner 10-90 = %s%n",
                strokePx, outward, inward, clamped ? " CLAMPED" : "", outerRamp,
                Double.isNaN(innerRamp) ? "no fill survives" : String.format("%.2fpx", innerRamp));
        // A width that leaves no fill on this row is a real answer about the letterform, not a
        // failed measurement, so it does not count toward the widest ramp.
        double inner = Double.isNaN(innerRamp) ? 0 : innerRamp;
        return Math.max(outerRamp, inner);
    }

    /** Width in display pixels over which a weight first climbs from 0.1 to 0.9. */
    private static double rampWidth(double[] pos, double[] value) {
        double at10 = Double.NaN;
        for (int i = 1; i < value.length; i++) {
            double prev = value[i - 1];
            double cur = value[i];
            if (Double.isNaN(at10) && prev < 0.1 && cur >= 0.1) at10 = pos[i];
            if (!Double.isNaN(at10) && prev < 0.9 && cur >= 0.9) return Math.abs(pos[i] - at10);
        }
        return Double.NaN;
    }

    /**
     * Steepest rise of the stored value per texel across the edge on this row. For a field whose
     * range really is pxRange texels either side of the contour this is exactly 1/pxRange.
     */
    private static double edgeSlopePerTexel(float[] data, int w, int y) {
        double steepest = 0;
        for (int x = 1; x < w - 1; x++) {
            double before = medianAt(data, w, x - 1, y);
            double after = medianAt(data, w, x + 1, y);
            // Only across the contour itself, where the field is a true distance either side.
            double mid = medianAt(data, w, x, y);
            if (mid > 0.25 && mid < 0.75) steepest = Math.max(steepest, Math.abs(after - before) / 2.0);
        }
        return steepest;
    }

    /** Bilinear along x at a fixed row, which is what the sampler does across a vertical edge. */
    private static double sampleMedian(float[] data, int w, double texelX, int y) {
        double sx = texelX - 0.5;
        int x0 = (int) Math.floor(sx);
        double f = sx - x0;
        double a = medianAt(data, w, x0, y);
        double b = medianAt(data, w, x0 + 1, y);
        return a + (b - a) * f;
    }

    private static double medianAt(float[] data, int w, int x, int y) {
        int i = 4 * (y * w + x);
        return Math.max(Math.min(data[i], data[i + 1]),
                Math.min(Math.max(data[i], data[i + 1]), data[i + 2]));
    }

    /**
     * A row crossing the stem as deep inside the glyph as the cell offers.
     *
     * <p>Chosen by the greatest field value on the row, NOT by the longest ink run: a run ties across
     * every row of a stem, so the longest kept the first one found — the topmost, where the cap edge
     * sits a texel above and every value on the row is small. An inward stroke then covers the whole
     * row and the fill ramp cannot be measured at all.</p>
     */
    private static int stemScanline(float[] data, int w, int h) {
        int best = h / 2;
        double bestDepth = -1;
        for (int y = 2; y < h - 2; y++) {
            int runs = 0;
            boolean in = false;
            double deepest = 0;
            for (int x = 0; x < w; x++) {
                double median = medianAt(data, w, x, y);
                boolean ink = median > 0.5;
                if (ink && !in) runs++;
                in = ink;
                deepest = Math.max(deepest, median);
            }
            // One run only: two means the row crosses the stem and the leg, and the scan would
            // report the gap between them as an edge.
            if (runs == 1 && deepest > bestDepth) {
                bestDepth = deepest;
                best = y;
            }
        }
        return best;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgStrokeEdgeRampTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
