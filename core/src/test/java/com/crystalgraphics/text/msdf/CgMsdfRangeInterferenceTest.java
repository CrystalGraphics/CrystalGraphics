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
import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>How far the stored range can be widened before dense scripts start filling in.</b>
 *
 * <p>{@code CgMsdfAtlasConfig.DEFAULT_PX_RANGE} buys stroke reach — a stroke can only be as wide as
 * the field carries real distance — and the bill is range interference: once the range exceeds the
 * gap between two edges, their fields overlap, the median picks values from the wrong edge between
 * them, and the gap fills. Dense kanji are where the gaps are smallest, which is why this measures M+
 * 1p and not Latin.</p>
 *
 * <p>The question is asked as <b>counters</b>: a kanji's enclosed white regions are exactly what
 * interference destroys, and counting them needs no registration between grids. A high-resolution
 * generation of the same glyph — three times the scale, so a third of the range in em — is the
 * reference for how many there should be.</p>
 */
public class CgMsdfRangeInterferenceTest {

    /** Dense by construction: many strokes inside one em, which is where gaps get small. */
    private static final String DENSE = "鬱驚籠鷹麗顔曜識議護";

    private static final float[] RANGES = {6f, 8f, 12f, 16f, 20f};

    @Test
    public void aWiderRangeDoesNotCloseDenseCounters() throws Exception {
        CgFont font = CgFont.load(fontBytes(), "range-interference", CgFontStyle.REGULAR, 80);
        try {
            CgFontKey fontKey = font.getKey();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            assertNotNull("the face must expose an msdfgen font", msdfFont);

            int measured = 0;
            int worstLoss = 0;
            float worstLossRange = 0;
            double worstInkDrift = 0;
            StringBuilder report = new StringBuilder();

            for (char c : DENSE.toCharArray()) {
                int glyphId = font.getGlyphIndex(c);
                if (glyphId <= 0) continue;

                // THE REFERENCE: same shape, three times the scale, so the range spans a third as much
                // of the em and cannot interfere. What the glyph actually has.
                Result truthField = generate(fontKey, msdfFont, glyphId, 6f, 240);
                int truth = counters(truthField);
                double truthInk = inkEmSquared(truthField, 240);
                if (truth < 2) continue;   // not dense enough to say anything about
                measured++;

                report.append(String.format("[range-interference] U+%04X  truth %2d counters, ink %.4f em2",
                        (int) c, truth, truthInk));
                for (float pxRange : RANGES) {
                    Result field = generate(fontKey, msdfFont, glyphId, pxRange,
                            CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX);
                    int found = counters(field);
                    int lost = truth - found;
                    // INK IS THE OTHER HALF OF THE QUESTION: interference can bloat a stroke without
                    // closing anything, and a counter count would not see it. Measured in em rather than
                    // texels so the scales compare.
                    double ink = inkEmSquared(field, CgMsdfAtlasConfig.DEFAULT_ATLAS_SCALE_PX);
                    double inkDrift = 100 * (ink - truthInk) / truthInk;
                    worstInkDrift = Math.max(worstInkDrift, Math.abs(inkDrift));
                    report.append(String.format("  |  pxRange %2.0f: %2d (%+d), ink %+.1f%%",
                            pxRange, found, -lost, inkDrift));
                    if (lost > worstLoss) {
                        worstLoss = lost;
                        worstLossRange = pxRange;
                    }
                }
                report.append(System.lineSeparator());
            }

            System.out.print(report);
            assertTrue("no dense glyph resolved — the test font is not the one this measures",
                    measured > 0);
            System.out.printf("[range-interference] %d dense glyphs; worst loss %d counters (pxRange %.0f), "
                            + "worst ink drift %.1f%%%n",
                    measured, worstLoss, worstLossRange, worstInkDrift);

            // What the pairing has to hold: widening the range for stroke reach must not close a dense
            // script's counters or swell its strokes. Both halves, because either alone can pass while
            // the glyph is visibly wrong.
            assertTrue("a dense glyph lost " + worstLoss + " counters at pxRange " + worstLossRange
                    + " — the range is interfering and the shipping pairing is no longer safe",
                    worstLoss == 0);
            assertTrue(String.format("ink drifted %.1f%% from the high-resolution reference — the range "
                    + "is bloating or thinning dense strokes", worstInkDrift), worstInkDrift < 6.0);
        } finally {
            font.dispose();
        }
    }

    /**
     * Enclosed background regions in the thresholded field — a kanji's counters. Flood-fills the
     * outside from the border; whatever background it cannot reach is enclosed by ink.
     */
    private static int counters(Result field) {
        int w = field.w, h = field.h;
        boolean[] ink = new boolean[w * h];
        for (int i = 0; i < w * h; i++) {
            float r = field.rgba[i * 4], g = field.rgba[i * 4 + 1], b = field.rgba[i * 4 + 2];
            ink[i] = Math.max(Math.min(r, g), Math.min(Math.max(r, g), b)) >= 0.5f;
        }

        boolean[] seen = new boolean[w * h];
        Deque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            push(queue, seen, ink, x, 0, w, h);
            push(queue, seen, ink, x, h - 1, w, h);
        }
        for (int y = 0; y < h; y++) {
            push(queue, seen, ink, 0, y, w, h);
            push(queue, seen, ink, w - 1, y, w, h);
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int x = i % w, y = i / w;
            push(queue, seen, ink, x - 1, y, w, h);
            push(queue, seen, ink, x + 1, y, w, h);
            push(queue, seen, ink, x, y - 1, w, h);
            push(queue, seen, ink, x, y + 1, w, h);
        }

        // Whatever background the outside never reached is a counter. Count its components, ignoring
        // specks a texel or two across -- those are reconstruction noise, not a feature of the letter.
        int counters = 0;
        boolean[] counted = new boolean[w * h];
        for (int start = 0; start < w * h; start++) {
            if (ink[start] || seen[start] || counted[start]) continue;
            int size = 0;
            queue.clear();
            queue.add(start);
            counted[start] = true;
            while (!queue.isEmpty()) {
                int i = queue.poll();
                size++;
                int x = i % w, y = i / w;
                for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int j = ny * w + nx;
                    if (ink[j] || counted[j]) continue;
                    counted[j] = true;
                    queue.add(j);
                }
            }
            if (size >= 4) counters++;
        }
        return counters;
    }

    private static void push(Deque<Integer> queue, boolean[] seen, boolean[] ink, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        if (seen[i] || ink[i]) return;
        seen[i] = true;
        queue.add(i);
    }

    /** Thresholded ink area, in em² — comparable between cells generated at different scales. */
    private static double inkEmSquared(Result field, int atlasScalePx) {
        int inked = 0;
        for (int i = 0; i < field.w * field.h; i++) {
            float r = field.rgba[i * 4], g = field.rgba[i * 4 + 1], b = field.rgba[i * 4 + 2];
            if (Math.max(Math.min(r, g), Math.min(Math.max(r, g), b)) >= 0.5f) inked++;
        }
        return inked / (double) (atlasScalePx * atlasScalePx);
    }

    private record Result(float[] rgba, int w, int h) {
    }

    private static Result generate(CgFontKey fontKey, FreeTypeMSDFIntegration.Font msdfFont,
                                   int glyphId, float pxRange, int atlasScalePx) {
        CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
        CgMsdfAtlasConfig config = new CgMsdfAtlasConfig(
                atlasScalePx, pxRange, base.pageSize(), base.spacingPx(), base.miterLimit(),
                base.alignOriginX(), base.alignOriginY(), base.overlapSupport(),
                base.errorCorrectionMode(), base.distanceCheckMode(),
                base.minDeviationRatio(), base.minImproveRatio(),
                base.edgeColoringMode(), base.edgeColoringAngleThreshold());

        CgGlyphKey key = new CgGlyphKey(fontKey, glyphId, true, 0);
        CgGlyphGenerationResult result = CgMsdfGenerator.prepareGlyph(
                key, fontKey, msdfFont, new CgMsdfAtlasKey(fontKey, config), config);
        assertNotNull("generation produced no result at pxRange " + pxRange, result);
        return new Result(result.getMsdfData(), result.getWidth(), result.getHeight());
    }

    private static byte[] fontBytes() throws Exception {
        try (InputStream in = CgMsdfRangeInterferenceTest.class
                .getResourceAsStream("/fonts/MPLUS1p-Regular.ttf")) {
            assertNotNull("M+ 1p must be on the test classpath", in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}
