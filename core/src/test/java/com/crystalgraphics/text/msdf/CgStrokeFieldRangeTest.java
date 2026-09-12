package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Why a wide stroke's outer edge comes out scalloped while the fill's edge beside it stays
 * smooth.</b>
 *
 * <p>Both edges are thresholds on one field, so a fault in the shape or in the reconstruction would
 * move both. What separates them is WHERE they threshold: the fill reads 0.5, where the field is
 * exact by construction, and the stroke reads an offset that grows with the authored width. Past
 * {@code (pxRange - 1) / 2} texels the stored field has clipped, and a texel that clipped no longer
 * says how far away it is — so the level set there follows the clipping boundary, which is shaped
 * like the texel grid rather than like the letter.</p>
 *
 * <p>This reconstructs the shader's own stroke band on the CPU from two fields of the SAME glyph at
 * the SAME resolution, differing only in stored range, and reports for each authored width how much
 * of the band's contour sits on clipped texels and how far it has drifted from the wide-range answer.
 * Resolution is held constant so the result cannot be read as "the atlas is too small".</p>
 *
 * <p>PNGs land in {@code build/stroke-field/} — shipping range beside wide range, at the
 * magnification the gallery's canvas reaches.</p>
 */
public class CgStrokeFieldRangeTest {

    /** The gallery's specimen size, and the zoom its canvas was at when this was reported. */
    private static final int FONT_PX = 64;
    /** The zoom the scalloping was reported at. The canvas reaches ~11x, where it is gone. */
    private static final float ZOOM = 4.18f;


    /** Authored stroke widths, screen px at {@link #FONT_PX} — 2.32 is the reported one. */
    private static final float[] WIDTHS_PX = {1f, 2f, 2.32f, 4f, 8f};

    private static final float SHIPPING_PX_RANGE = CgMsdfAtlasConfig.DEFAULT_PX_RANGE;   // 6
    private static final float WIDE_PX_RANGE = 24f;

    @Test
    public void aStrokeWiderThanTheStoredRangeThresholdsClippedTexels() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "stroke-range-probe", CgFontStyle.REGULAR, 80);
        try {
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            int glyphId = font.getGlyphIndex('g');
            assertTrue("the test font must have a 'g'", glyphId > 0);

            Field shipping = generate(font, fontKey, msdfFont, glyphId, SHIPPING_PX_RANGE);
            Field wide = generate(font, fontKey, msdfFont, glyphId, WIDE_PX_RANGE);

            int atlasScale = CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;
            System.out.printf("[stroke-range] atlasScale=%dpx  shipping pxRange=%.0f (reach %.2f texels,"
                            + " %.4f em)  wide pxRange=%.0f (reach %.2f texels, %.4f em)%n",
                    atlasScale, SHIPPING_PX_RANGE, (SHIPPING_PX_RANGE - 1) / 2,
                    (SHIPPING_PX_RANGE - 1) / 2 / atlasScale,
                    WIDE_PX_RANGE, (WIDE_PX_RANGE - 1) / 2, (WIDE_PX_RANGE - 1) / 2 / atlasScale);
            System.out.printf("[stroke-range] field %dx%d texels, drawn at font %dpx x %.2f zoom%n",
                    shipping.w, shipping.h, FONT_PX, ZOOM);

            boolean sawClipping = false;
            for (float widthPx : WIDTHS_PX) {
                float widthEm = widthPx / FONT_PX;
                float offsetTexels = widthEm * atlasScale;

                Band shipped = band(shipping, widthEm, true);        // the texel headroom text.shader keeps
                Band pixelHeadroom = band(shipping, widthEm, false);  // what it kept before, for contrast
                Band b = band(wide, widthEm, false);                 // a range with room to spare

                System.out.printf("[stroke-range] %.2fpx (%.4f em, %.2f texels asked):  shipped reaches "
                                + "%.2f texels (clipped taps %4.1f%%)  |  a screen pixel of headroom would "
                                + "reach %.2f texels (clipped taps %4.1f%%)  |  wide range %.2f texels%n",
                        widthPx, widthEm, offsetTexels,
                        shipped.outwardTexels, 100 * shipped.clippedFractionOnContour,
                        pixelHeadroom.outwardTexels, 100 * pixelHeadroom.clippedFractionOnContour,
                        b.outwardTexels);

                // THE RULE, in the form that fails if the headroom goes back to screen pixels: whatever
                // width is authored, the stroke's outer contour resolves from texels that still carry a
                // distance. A flat field turns eight-bit error into a texel-shaped staircase, which is
                // the scalloping this test was written for.
                assertTrue(String.format("at %.2fpx the stroke reads clipped texels along %.0f%% of its "
                                + "contour -- it is thresholding a saturated field and the edge will "
                                + "scallop", widthPx, 100 * shipped.clippedFractionOnContour),
                        shipped.clippedFractionOnContour < 0.25);

                if (widthPx == 2.32f || widthPx == 8f) {
                    write(shipped, "shipped-" + widthPx + "px");
                    write(pixelHeadroom, "pixel-headroom-" + widthPx + "px");
                    write(b, "wide-range-" + widthPx + "px");
                }
                if (pixelHeadroom.clippedFractionOnContour > 0.5) sawClipping = true;
            }

            // And the counterpart: what the rule above is protecting against is real at this pairing.
            // A screen pixel of headroom does reach clipped texels here, so the rule is load-bearing
            // rather than decorative.
            assertTrue("a screen pixel of headroom no longer reaches clipped texels, so the rule this "
                    + "test pins is no longer what keeps the edge clean", sawClipping);
        } finally {
            font.dispose();
        }
    }

    /**
     * <b>What a wider stored range would buy, and what it would cost.</b>
     *
     * <p>The usable reach is {@code ((pxRange - 1) / 2 - 1) / atlasScalePx} em — the generator keeps a
     * texel back so the field cannot bleed past its cell, and {@code text.shader} keeps another for the
     * bilinear footprint. Everything wider than that draws at the ceiling, which is what a slider with
     * eight pixels of travel and one pixel of effect looks like.</p>
     *
     * <p>The cost is cell AREA, paid by every glyph on the page whether or not anything strokes it —
     * the padding is part of the raster, so a page holds proportionally fewer glyphs. Printed per
     * candidate range so the trade is a number rather than a feeling.</p>
     */
    @Test
    public void whatAWiderRangeWouldBuy() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "range-sweep", CgFontStyle.REGULAR, 80);
        try {
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            int glyphId = font.getGlyphIndex('g');
            int atlasScale = CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;

            int baseArea = 0;
            System.out.println("[range-sweep] pxRange | usable reach     | stroke it allows at text size"
                    + " 32 / 64 / 72 | 'g' cell | area vs today");
            for (float pxRange : new float[]{6f, 8f, 10f, 12f, 16f, 24f}) {
                Field f = generate(font, fontKey, msdfFont, glyphId, pxRange);
                double usableTexels = f.pxRange / 2.0 - 1.0;
                double reachEm = usableTexels / atlasScale;
                int area = f.w * f.h;
                if (baseArea == 0) baseArea = area;
                System.out.printf("[range-sweep]   %4.0f  | %4.1f texels %.4f em | %5.2fpx %5.2fpx %5.2fpx"
                                + "        | %3dx%-3d  | %.2fx%n",
                        pxRange, usableTexels, reachEm,
                        reachEm * 32, reachEm * 64, reachEm * 72,
                        f.w, f.h, (double) area / baseArea);
            }
        } finally {
            font.dispose();
        }
    }

    /**
     * <b>Does an outline read off the median differ from one read off MTSDF's true distance?</b>
     *
     * <p>msdf-atlas-gen's own feature table says soft effects "that use true distance, such as glows,
     * ROUNDED OUTLINES, or simplified shadows" are what the fourth channel is for, and that the sharp
     * MSDF does not support them. Godot regressed font outlines when it moved MTSDF to MSDF
     * (godotengine/godot#109757). {@code text.shader} nonetheless reads the median for its stroke, on
     * the strength of a measurement that compared the two channels TEXEL BY TEXEL over the band
     * (@see CgMtsdfAlphaChannelTest) and found them within 0.077.</p>
     *
     * <p>That comparison cannot see the thing the two channels disagree about. A median-of-3 offset
     * contour MITRES at a corner -- the median is built to keep corners sharp -- where a true distance
     * ROUNDS it, and a corner is a handful of texels out of a glyph's hundreds, so any average over the
     * band drowns it. So this compares the reconstructed BANDS, on glyphs chosen for their corners, and
     * reports the disagreement as a fraction of the band's own area.</p>
     */
    @Test
    public void medianAndTrueDistanceDisagreeAtCorners() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "corner-probe", CgFontStyle.REGULAR, 80);
        try {
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            // Corners, not curves: a diagonal apex, right angles, and a round one as the control.
            for (char c : new char[]{'A', 'E', 'M', 'o'}) {
                int glyphId = font.getGlyphIndex(c);
                if (glyphId <= 0) continue;

                Field field = generate(font, fontKey, msdfFont, glyphId, SHIPPING_PX_RANGE);
                float widthEm = 2f / FONT_PX;

                field.trueDistance = false;
                Band median = band(field, widthEm, true);
                field.trueDistance = true;
                Band trueDist = band(field, widthEm, true);
                field.trueDistance = false;

                long differ = 0, band = 0;
                for (int i = 0; i < median.mask.length; i++) {
                    if (median.mask[i] != trueDist.mask[i]) differ++;
                    if (median.mask[i] || trueDist.mask[i]) band++;
                }
                System.out.printf("[corners] '%c'  outer band %d px, median vs true distance differ "
                                + "%d px (%.2f%%)%n", c, band, differ, band == 0 ? 0 : 100.0 * differ / band);

                if (c == 'A') {
                    write(median, "corner-median-A");
                    write(trueDist, "corner-truedistance-A");
                }
            }
        } finally {
            font.dispose();
        }
    }

    // ── the shader's own band, on the CPU ───────────────────────────────────────────────────

    /** One reconstructed stroke band, plus how much of its contour rests on clipped texels. */
    private static final class Band {
        final boolean[] mask;          // inside the stroke ring
        final float[] coverage;        // the ring's alpha, for the picture
        final float[] outer;           // the ring's OUTER coverage, read at sub-pixel precision
        final float[] fill;            // the glyph's own coverage, for the picture
        final int w, h;
        final double clippedFractionOnContour;
        double outwardTexels;

        Band(boolean[] mask, float[] coverage, float[] outer, float[] fill, int w, int h, double clipped) {
            this.mask = mask;
            this.coverage = coverage;
            this.outer = outer;
            this.fill = fill;
            this.w = w;
            this.h = h;
            this.clippedFractionOnContour = clipped;
        }
    }

    private static final class Field {
        final float[] rgba;
        final int w, h;
        final float pxRange;
        /** Read MTSDF's true-distance alpha instead of the median of rgb. */
        boolean trueDistance;

        Field(float[] rgba, int w, int h, float pxRange) {
            this.rgba = rgba;
            this.w = w;
            this.h = h;
            this.pxRange = pxRange;
        }

        /** Bilinear, as GL_LINEAR samples it; returns the median of rgb and whether any tap clipped. */
        float sample(float x, float y, boolean[] clipped) {
            float fx = Math.max(0, Math.min(w - 1.001f, x - 0.5f));
            float fy = Math.max(0, Math.min(h - 1.001f, y - 0.5f));
            int x0 = (int) fx, y0 = (int) fy;
            float tx = fx - x0, ty = fy - y0;
            float m00 = medianAt(x0, y0, clipped), m10 = medianAt(x0 + 1, y0, clipped);
            float m01 = medianAt(x0, y0 + 1, clipped), m11 = medianAt(x0 + 1, y0 + 1, clipped);
            return (m00 * (1 - tx) + m10 * tx) * (1 - ty) + (m01 * (1 - tx) + m11 * tx) * ty;
        }

        private float medianAt(int x, int y, boolean[] clipped) {
            x = Math.max(0, Math.min(w - 1, x));
            y = Math.max(0, Math.min(h - 1, y));
            int i = (y * w + x) * 4;
            // AS THE ATLAS STORES IT. Eight bits per channel, which costs nothing where the field has
            // a gradient -- a level set's position error is the value error divided by the slope it
            // crosses -- and costs everything where the field has flattened out.
            if (trueDistance) {
                float a = q(rgba[i + 3]);
                if (clipped != null && (a <= 1e-4f || a >= 1f - 1e-4f)) clipped[0] = true;
                return a;
            }
            float r = q(rgba[i]), g = q(rgba[i + 1]), b = q(rgba[i + 2]);
            float median = Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
            // CLIPPED, i.e. the texel is at the end of the stored range and no longer carries a
            // distance. The generator writes 0 and 1 exactly there.
            if (clipped != null && (median <= 1e-4f || median >= 1f - 1e-4f)) clipped[0] = true;
            return median;
        }
    }

    private static float q(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f) / 255f;
    }

    /** {@code text.shader}'s MSDF_MODE fragment, for an OUTSET stroke of {@code widthEm}. */
    private static Band band(Field field, float widthEm, boolean texelHeadroom) {
        float scale = FONT_PX * ZOOM / (float) CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX;  // screen px per texel
        int w = Math.round(field.w * scale), h = Math.round(field.h * scale);

        float screenPxRange = field.pxRange * scale;
        // AS THE RENDERER SIZES IT: em against the size the text is DRAWN at, so the offset holds the
        // same fraction of the letterform at every zoom. Measuring it against the raster size instead
        // -- which is clamped -- is what made the outline thin away as a canvas zoomed in.
        float strokeWidthPx = widthEm * FONT_PX * ZOOM;
        // THE HEADROOM, and its unit is the whole question. One screen pixel is what ships; the
        // shoulder it is keeping clear of belongs to the TEXEL grid, and at low magnification one
        // screen pixel is a third of a texel -- so the bilinear footprint straddles the shoulder even
        // though the threshold itself is nominally inside the range.
        float headroom = texelHeadroom ? Math.max(1f, scale) : 1f;
        float fieldReach = Math.max(screenPxRange * 0.5f - headroom, 0f);
        float outward = Math.min(strokeWidthPx, fieldReach);

        boolean[] mask = new boolean[w * h];
        float[] coverage = new float[w * h];
        float[] outerCoverage = new float[w * h];
        float[] fill = new float[w * h];
        long contourTexels = 0, contourClipped = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean[] clipped = new boolean[1];
                float sd = field.sample((x + 0.5f) / scale, (y + 0.5f) / scale, clipped);
                float screenPxDist = screenPxRange * (sd - 0.5f);

                float opacity = clamp01(screenPxDist + 0.5f);
                float ringOuter = clamp01(screenPxDist + outward + 0.5f);
                float ring = Math.max(ringOuter - opacity, 0f);

                int i = y * w + x;
                fill[i] = opacity;
                coverage[i] = ring;
                outerCoverage[i] = ringOuter;
                mask[i] = ringOuter >= 0.5f;

                // ON THE CONTOUR the stroke actually draws: where its outer edge is being resolved.
                if (ringOuter > 0.05f && ringOuter < 0.95f) {
                    contourTexels++;
                    if (clipped[0]) contourClipped++;
                }
            }
        }
        Band band = new Band(mask, coverage, outerCoverage, fill, w, h,
                contourTexels == 0 ? 0 : (double) contourClipped / contourTexels);
        band.outwardTexels = outward / scale;
        return band;
    }


    /**
     * The headroom {@code text.shader} keeps between the stroke's threshold and the end of the stored
     * range. ONE SCREEN PIXEL today, which is the wrong unit: the field's shoulder is a property of
     * the TEXEL GRID, so at low magnification one screen pixel is a third of a texel and the bilinear
     * footprint straddles the shoulder anyway. Set {@code FIX} to measure the texel-sized version.
     */
    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    private static Field generate(CgFont font, CgFontKey fontKey, FreeTypeMSDFIntegration.Font msdfFont,
                                  int glyphId, float pxRange) {
        CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
        CgMsdfAtlasConfig config = new CgMsdfAtlasConfig(
                base.atlasScalePx(), pxRange, base.pageSize(), base.spacingPx(), base.miterLimit(),
                base.alignOriginX(), base.alignOriginY(), base.overlapSupport(),
                base.errorCorrectionMode(), base.distanceCheckMode(),
                base.minDeviationRatio(), base.minImproveRatio(),
                base.edgeColoringMode(), base.edgeColoringAngleThreshold());

        CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
        CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(fontKey, config);
        CgGlyphGenerationResult result = CgMsdfGenerator.prepareGlyph(key, fontKey, msdfFont, atlasKey, config);
        assertNotNull("generation produced no result at pxRange " + pxRange, result);
        float[] data = result.getMsdfData();
        assertNotNull("generation produced no pixels at pxRange " + pxRange, data);
        // THE RANGE THE SHADER IS TOLD, not the configured one: the layout reserves a texel, so the
        // field stops short of the cell edge and CgMsdfGenerator hands the reduced number down.
        // Reconstructing with the nominal range puts every threshold in the wrong place.
        System.out.printf("[stroke-range]   pxRange config=%.1f stored=%.3f (%dx%d texels)%n",
                pxRange, result.getPxRange(), result.getWidth(), result.getHeight());
        return new Field(data, result.getWidth(), result.getHeight(), result.getPxRange());
    }

    private static void write(Band band, String name) throws Exception {
        // Nearest-neighbour magnified for reading. The SAMPLING regime is untouched -- this only
        // makes a 3px scallop visible on a screen, exactly as the gallery's canvas does.
        int view = 4;
        BufferedImage img = new BufferedImage(band.w * view, band.h * view, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < band.h; y++) {
            for (int x = 0; x < band.w; x++) {
                int i = y * band.w + x;
                // The gallery's own colours: near-black ground, off-white fill, blue stroke.
                float f = band.fill[i], s = band.coverage[i];
                int r = (int) ((0.07f * (1 - s - f) + 0.10f * s + 0.95f * f) * 255);
                int g = (int) ((0.08f * (1 - s - f) + 0.40f * s + 0.96f * f) * 255);
                int b = (int) ((0.11f * (1 - s - f) + 0.62f * s + 0.98f * f) * 255);
                int rgb = (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
                for (int vy = 0; vy < view; vy++) {
                    for (int vx = 0; vx < view; vx++) img.setRGB(x * view + vx, y * view + vy, rgb);
                }
            }
        }
        File dir = new File("build/stroke-field");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File out = new File(dir, name + ".png");
        ImageIO.write(img, "png", out);
        System.out.println("[stroke-range] wrote " + out.getAbsolutePath());
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgStrokeFieldRangeTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
