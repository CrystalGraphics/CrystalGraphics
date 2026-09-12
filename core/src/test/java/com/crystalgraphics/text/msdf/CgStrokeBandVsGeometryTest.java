package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFShape;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>Does the stored field agree with the ACTUAL GEOMETRY about where an inward stroke reaches?</b>
 *
 * <p>{@link CgMsdfInteriorBandTest} compared the median against MTSDF's true distance and found them
 * painting the same texels — which rules out the reconstruction and nothing else. Both channels are
 * generated from one shape, so an error they SHARE is invisible to that comparison; {@code
 * text.shader}'s own header says as much and the comparison was read as proof anyway.</p>
 *
 * <p>So this compares against the shape itself, and it asks the question in a form that needs no
 * registration between the two grids: <b>is the surviving fill CONNECTED?</b> An {@code h}'s stem and
 * leg join only through the shoulder, so if the joint closes, the fill falls into separate pieces. One
 * component means the joint is open; more than one means it closed. Counting components on each grid
 * compares the two without having to reproduce the generator's translate, padding or y-orientation —
 * the earlier attempt at that put the pictures at different zooms and could not be read.</p>
 *
 * <p>If the geometry stays connected where the field splits, the field is wrong and the joint is
 * fixable. If both split, the letterform is simply thinner than the stroke asks for and no amount of
 * field quality changes it.</p>
 */
public class CgStrokeBandVsGeometryTest {

    /** Inward reach, em. 2px at font-size 72 — what the gallery's inset row asks for. */
    private static final double BAND_EM = 2.0 / 72.0;

    @Test
    public void theFieldAgreesWithTheGeometryAboutTheInwardBand() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "band-probe", CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            for (char c : new char[]{'h', 'g'}) {
                int glyphId = font.getGlyphIndex(c);
                if (glyphId <= 0) continue;

                CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
                CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(fontKey, config);
                CgGlyphGenerationResult result =
                        CgMsdfGenerator.prepareGlyph(key, fontKey, msdfFont, atlasKey, config);
                assertNotNull("no generation result for '" + c + "'", result);
                float[] data = result.getMsdfData();
                assertNotNull("no pixels for '" + c + "'", data);

                int w = result.getWidth();
                int h = result.getHeight();
                double pxRange = result.getPxRange();
                // The atlas is generated at this many pixels per em, so an em converts to texels by
                // it, and a texel of distance is 1/pxRange of stored value.
                double atlasScale = config.atlasScalePx();
                double bandTexels = BAND_EM * atlasScale;
                double bandTop = 0.5 + bandTexels / pxRange;

                System.out.printf("[band] '%c' %dx%d atlasScale=%.0f pxRange=%.1f bandEm=%.5f "
                                + "bandTexels=%.2f bandTop=%.3f%n",
                        c, w, h, atlasScale, pxRange, BAND_EM, bandTexels, bandTop);

                MSDFShape shape = msdfFont.loadGlyphByIndex(glyphId).getShape();
                try {
                    shape.normalize();
                    boolean[] fieldFill = fieldFill(data, w, h, bandTop);
                    int fieldParts = components(fieldFill, w, h);
                    printMap(fieldFill, fieldStroke(data, w, h, bandTop), w, h, c, "FIELD");

                    // The exact grid is sampled over the glyph's own box at the same cell count, so
                    // the two are the same PICTURE at different zooms -- which connectivity does not
                    // care about, and registration would have been needed for anything else.
                    boolean[] exactFill = new boolean[w * h];
                    boolean[] exactStroke = new boolean[w * h];
                    exactBand(shape, w, h, exactFill, exactStroke);
                    int exactParts = components(exactFill, w, h);
                    printMap(exactFill, exactStroke, w, h, c, "EXACT");

                    System.out.printf("[band] '%c' surviving-fill components: field=%d exact=%d%n",
                            c, fieldParts, exactParts);
                    assertTrue("'" + c + "': the field splits the surviving fill into " + fieldParts
                                    + " pieces where the geometry gives " + exactParts + ". A field "
                                    + "that closes a joint the shape leaves open is a FIELD fault and "
                                    + "the joint is fixable; equal counts mean the letterform is "
                                    + "thinner than the stroke and nothing can be done about it.",
                            fieldParts == exactParts);
                } finally {
                    shape.free();
                }
            }
        } finally {
            font.dispose();
        }
    }

    /** Ink the inward stroke does NOT cover, per the atlas: what stays fill. */
    private static boolean[] fieldFill(float[] data, int w, int h, double bandTop) {
        boolean[] fill = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            double median = median3(data[4 * i], data[4 * i + 1], data[4 * i + 2]);
            fill[i] = median > bandTop;
        }
        return fill;
    }

    private static boolean[] fieldStroke(float[] data, int w, int h, double bandTop) {
        boolean[] stroke = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            double median = median3(data[4 * i], data[4 * i + 1], data[4 * i + 2]);
            stroke[i] = median >= 0.5 && median <= bandTop;
        }
        return stroke;
    }

    /** The same two sets from the shape's own distances, over the glyph's own box. */
    private static void exactBand(MSDFShape shape, int w, int h, boolean[] fill, boolean[] stroke) {
        double[] box = shape.getBounds();
        double sign = insideSign(shape, box);
        for (int y = 0; y < h; y++) {
            double sy = box[3] - (box[3] - box[1]) * (y + 0.5) / h;
            for (int x = 0; x < w; x++) {
                double sx = box[0] + (box[2] - box[0]) * (x + 0.5) / w;
                double d = sign * shape.getOneShotDistance(sx, sy);
                fill[y * w + x] = d > BAND_EM;
                stroke[y * w + x] = d >= 0 && d <= BAND_EM;
            }
        }
    }

    /** Four-connected components of the surviving fill, ignoring specks of one or two cells. */
    private static int components(boolean[] fill, int w, int h) {
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int parts = 0;
        for (int start = 0; start < w * h; start++) {
            if (!fill[start] || seen[start]) continue;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            int size = 0;
            while (top > 0) {
                int at = stack[--top];
                size++;
                int x = at % w;
                int y = at / w;
                if (x > 0) top = push(stack, top, seen, fill, at - 1);
                if (x < w - 1) top = push(stack, top, seen, fill, at + 1);
                if (y > 0) top = push(stack, top, seen, fill, at - w);
                if (y < h - 1) top = push(stack, top, seen, fill, at + w);
            }
            // A grid this coarse throws single-cell specks off an antialiased edge; they are not
            // pieces of letterform and counting them would make the comparison noise.
            if (size > 2) parts++;
        }
        return parts;
    }

    private static int push(int[] stack, int top, boolean[] seen, boolean[] fill, int at) {
        if (fill[at] && !seen[at]) {
            seen[at] = true;
            stack[top++] = at;
        }
        return top;
    }

    private static void printMap(boolean[] fill, boolean[] stroke, int w, int h, char c, String which) {
        int step = Math.max(1, w / 56);
        System.out.printf("[band] '%c' %s   # stroke  . fill  (space) outside%n", c, which);
        for (int y = 0; y < h; y += step) {
            StringBuilder line = new StringBuilder("[band]   ");
            for (int x = 0; x < w; x += step) {
                int i = y * w + x;
                line.append(fill[i] ? '.' : stroke[i] ? '#' : ' ');
            }
            System.out.println(line);
        }
    }

    private static double insideSign(MSDFShape shape, double[] box) {
        double d = shape.getOneShotDistance(box[0] - 1.0, box[1] - 1.0);
        return d == 0 ? 1 : -Math.signum(d);
    }

    private static double median3(float a, float b, float c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgStrokeBandVsGeometryTest.class.getResourceAsStream("/fonts/test-font.ttf")) {
            assertNotNull("test font resource must exist", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
