package com.crystalgraphics.render.ui.svg;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Holds the directly-built rounded rect to the geometry the {@code d}-string round-trip produced.
 *
 * <p>The claim being checked is stronger than "close enough". {@code Float.toString} emits the shortest
 * decimal that parses back to the same {@code float}, so composing coordinates into a path string and
 * re-parsing them was always exactly the identity — which means building them in place has to agree to the
 * bit, and any drift would be a real mistake rather than rounding.</p>
 *
 * <p>It matters because a rect is usually the <em>backing plate</em> of an icon: the shape everything else
 * sits on. A corner off by an ulp is not visible on the rect, it is visible as a seam where the rect meets
 * whatever is drawn over it.</p>
 */
public class CgSvgRoundedRectTest {

    /** The exact shapes the shipped artwork uses — small, integer-ish, radius well under half the side. */
    @Test
    public void shippedShapedRectsMatchTheStringForm() {
        float[][] cases = {
                {0, 0, 16, 16, 2, 2},
                {0, 0, 16, 16, 3, 3},
                {1, 1, 14, 14, 2, 2},
                {0.5f, 0.5f, 15f, 15f, 1.5f, 1.5f},
                {2, 3, 12, 10, 4, 2},          // rx != ry
                {0, 0, 24, 24, 12, 12},        // radius exactly half -- a stadium
                {0, 0, 10, 20, 5, 10},
                {-4, -4, 28, 28, 6, 6},        // negative origin, as a padded viewBox gives
        };
        for (float[] c : cases) {
            for (int steps : new int[]{2, 3, 5, 9, 16}) {
                assertSameAsStringForm(c[0], c[1], c[2], c[3], c[4], c[5], steps);
            }
        }
    }

    /** Randomised, to catch a corner case the shipped set happens not to contain. */
    @Test
    public void randomisedRectsMatchTheStringForm() {
        Random random = new Random(0x2ECDL);
        for (int trial = 0; trial < 2000; trial++) {
            float x = random.nextFloat() * 40f - 20f;
            float y = random.nextFloat() * 40f - 20f;
            float w = 0.5f + random.nextFloat() * 40f;
            float h = 0.5f + random.nextFloat() * 40f;
            float rx = random.nextFloat() * w / 2f;
            float ry = random.nextFloat() * h / 2f;
            if (rx <= 0f || ry <= 0f) continue;
            assertSameAsStringForm(x, y, w, h, rx, ry, 1 + random.nextInt(16));
        }
    }

    /** A rect drawn through the public seam, so the wiring is covered and not only the helper. */
    @Test
    public void aRoundedRectTagStillBecomesOneClosedContour() {
        CgSvgScanner.Tag tag = CgSvgScanner.scan(
                "<svg><rect x=\"1\" y=\"1\" width=\"14\" height=\"14\" rx=\"3\"/></svg>").stream()
                .filter(t -> t.name().equals("rect")).findFirst().orElseThrow();
        List<CgSvgPath.Polyline> contours = CgSvgGeometry.of(tag, 5);
        assertEquals(1, contours.size());
        assertTrue("a rounded rect should be closed", contours.get(0).closed());
        assertTrue("expected corner arcs, got " + contours.get(0).points().size(),
                contours.get(0).points().size() > 8);
    }

    private static void assertSameAsStringForm(float x, float y, float w, float h,
                                               float rx, float ry, int steps) {
        String what = "rect(" + x + "," + y + " " + w + "x" + h + " r" + rx + "," + ry + " @" + steps + ")";
        List<CgSvgPath.Polyline> expected = CgSvgPath.parse(stringForm(x, y, w, h, rx, ry), steps);
        List<CgSvgPath.Polyline> actual = CgSvgPath.roundedRect(x, y, w, h, rx, ry, steps);

        assertEquals(what + ": contour count", expected.size(), actual.size());
        for (int c = 0; c < expected.size(); c++) {
            List<float[]> want = expected.get(c).points();
            List<float[]> got = actual.get(c).points();
            assertEquals(what + ": closed", expected.get(c).closed(), actual.get(c).closed());
            assertEquals(what + ": point count", want.size(), got.size());
            for (int i = 0; i < want.size(); i++) {
                assertEquals(what + ": x[" + i + "]",
                        Float.floatToIntBits(want.get(i)[0]), Float.floatToIntBits(got.get(i)[0]));
                assertEquals(what + ": y[" + i + "]",
                        Float.floatToIntBits(want.get(i)[1]), Float.floatToIntBits(got.get(i)[1]));
            }
        }
    }

    /** The path text {@code addRect} used to compose, frozen. */
    private static String stringForm(float x, float y, float w, float h, float rx, float ry) {
        return "M" + (x + rx) + " " + y
                + " H" + (x + w - rx) + " A" + rx + " " + ry + " 0 0 1 " + (x + w) + " " + (y + ry)
                + " V" + (y + h - ry) + " A" + rx + " " + ry + " 0 0 1 " + (x + w - rx) + " " + (y + h)
                + " H" + (x + rx) + " A" + rx + " " + ry + " 0 0 1 " + x + " " + (y + h - ry)
                + " V" + (y + ry) + " A" + rx + " " + ry + " 0 0 1 " + (x + rx) + " " + y + " Z";
    }
}
