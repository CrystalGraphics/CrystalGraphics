package com.crystalgraphics.render.ui.path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeCap;
import com.crystalgraphics.api.vector.CgStrokeJoin;
import com.crystalgraphics.api.vector.CgVectorPath;

public class CgPathGeometryTest {

    private static final CgVectorPath LINE = CgVectorPath.builder().moveTo(0, 0).lineTo(100, 0).build();

    @Test
    public void wangsFormulaGrowsWithCurvatureAndSize() {
        // A straight quadratic needs one segment; a bent one needs sqrt(0.25 * |second difference| / tolerance)
        assertEquals(1, CgPathFlattener.quadSegments(0, 0, 50, 0, 100, 0));
        assertEquals(10, CgPathFlattener.quadSegments(0, 0, 50, 50, 100, 0));
        assertTrue(CgPathFlattener.cubicSegments(0, 0, 0, 200, 200, 200, 200, 0)
                > CgPathFlattener.cubicSegments(0, 0, 0, 20, 20, 20, 20, 0));
    }

    @Test
    public void aScaledCurveFlattensFinerInItsOwnUnits() {
        CgVectorPath arc = CgVectorPath.builder().moveTo(0, 0).quadTo(5, 5, 10, 0).build();
        int small = CgPathFlattener.flatten(arc, 1, 0, 0, 1, 0, 0).get(0).points().size();
        int large = CgPathFlattener.flatten(arc, 20, 0, 0, 20, 0, 0).get(0).points().size();
        assertTrue(large > small);
    }

    @Test
    public void aButtStrokeOfALineIsItsLengthTimesItsWidth() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        List<CgPathFlattener.Contour> outline = CgPathStroker.outline(local, CgStroke.of(10f), 0.25f);
        assertEquals(1000f, area(outline), 0.5f);
    }

    @Test
    public void aSquareCapAddsHalfTheWidthAtEachEnd() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        assertEquals(1100f, area(CgPathStroker.outline(local, CgStroke.of(10f).cap(CgStrokeCap.SQUARE), 0.25f)), 0.5f);
    }

    @Test
    public void aRoundCapAddsADiscWithinTheTolerance() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        float area = area(CgPathStroker.outline(local, CgStroke.of(10f).cap(CgStrokeCap.ROUND), 0.05f));
        assertEquals(1000f + (float) Math.PI * 25f, area, 1f);
    }

    @Test
    public void aMiterPastItsLimitBevels() {
        CgVectorPath spike = CgVectorPath.builder().moveTo(0, 0).lineTo(50, 2).lineTo(0, 4).build();
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(spike, 1, 0, 0, 1, 0, 0);
        float miter = extentX(CgPathStroker.outline(local, CgStroke.of(4f).join(CgStrokeJoin.MITER).miterLimit(100f), 0.25f));
        float bevel = extentX(CgPathStroker.outline(local, CgStroke.of(4f).join(CgStrokeJoin.MITER), 0.25f));
        assertTrue("a sharp miter reaches far past the tip: " + miter, miter > 80f);
        assertTrue("limited, it bevels near the tip: " + bevel, bevel < 55f);
    }

    @Test
    public void dashesCutTheLengthIntoOnPieces() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        List<CgPathFlattener.Contour> pieces = CgPathDash.apply(local, CgStroke.of(1f).dash(10f, 15f));
        assertEquals(4, pieces.size());   // on at 0, 25, 50, 75
        assertEquals(10f, CgPathDash.length(pieces.get(1)), 1e-3f);
    }

    @Test
    public void zeroLengthDashesAreDotsFromTheStart() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        List<CgPathFlattener.Contour> dots = CgPathDash.apply(local, CgStroke.of(4f).dash(0f, 20f));
        assertEquals(5, dots.size());   // at 0, 20, 40, 60, 80
        assertEquals(0f, dots.get(0).points().get(0)[0], 1e-3f);
        assertEquals(1, dots.get(0).points().size());
    }

    @Test
    public void aTrimKeepsAFractionOfTheWholeLength() {
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(LINE, 1, 0, 0, 1, 0, 0);
        List<CgPathFlattener.Contour> kept = CgPathDash.apply(local, CgStroke.of(1f).trim(0.25f, 0.5f));
        assertEquals(1, kept.size());
        assertEquals(25f, kept.get(0).points().get(0)[0], 1e-3f);
        assertEquals(25f, CgPathDash.length(kept.get(0)), 1e-3f);
    }

    /** The nonzero-filled area of outline contours, by the shoelace formula on each: they do not overlap here. */
    private static float area(List<CgPathFlattener.Contour> contours) {
        float total = 0f;
        for (CgPathFlattener.Contour c : contours) {
            List<float[]> p = c.points();
            float sum = 0f;
            for (int i = 0; i < p.size(); i++) {
                float[] a = p.get(i), b = p.get((i + 1) % p.size());
                sum += a[0] * b[1] - b[0] * a[1];
            }
            total += Math.abs(sum) * 0.5f;
        }
        return total;
    }

    private static float extentX(List<CgPathFlattener.Contour> contours) {
        float max = Float.NEGATIVE_INFINITY;
        for (CgPathFlattener.Contour c : contours) for (float[] q : c.points()) max = Math.max(max, q[0]);
        return max;
    }
}
