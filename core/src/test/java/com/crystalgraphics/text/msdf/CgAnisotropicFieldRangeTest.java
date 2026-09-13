package com.crystalgraphics.text.msdf;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>What one screen pixel is worth in field units, under a transform that is not a similarity.</b>
 *
 * <p>{@code text.shader} converts the stored field into screen pixels through {@code screenPxRange},
 * which decides the antialiasing ramp and the stroke's width. msdfgen's canonical form averages the
 * two uv derivatives into one number; that is exact only when the transform preserves angles and
 * scales both axes alike. This reproduces both forms as plain arithmetic and scores them against the
 * analytic answer, so the claim is a number rather than a screenshot.</p>
 *
 * <p>The Jacobian maps a screen step to a texel step, so a contour whose screen-space normal is
 * {@code g} has {@code pxRange / |J g|} screen pixels of field across it. That IS the shader's new
 * formula, so these cases are really asking where the OLD one departs from it.</p>
 */
public class CgAnisotropicFieldRangeTest {

    private static final float PX_RANGE = 24f;

    /** The shader's formula: direction from the field, magnitude from the Jacobian. */
    private static double jacobianRange(double[] jdx, double[] jdy, double gx, double gy) {
        double sx = jdx[0] * gx + jdy[0] * gy;
        double sy = jdx[1] * gx + jdy[1] * gy;
        return PX_RANGE / Math.hypot(sx, sy);
    }

    /** msdfgen's: 0.5 * dot(unitRange, 1/fwidth(uv)), one value for every direction. */
    private static double fwidthRange(double[] jdx, double[] jdy) {
        double fwU = Math.abs(jdx[0]) + Math.abs(jdy[0]);
        double fwV = Math.abs(jdx[1]) + Math.abs(jdy[1]);
        return 0.5 * (PX_RANGE / fwU + PX_RANGE / fwV);
    }

    @Test
    public void theTwoFormsAgreeWhereverTheOldOneIsValid() {
        // Uniform magnification: one screen px covers 1/s of a texel, both axes.
        for (double s : new double[]{1.0, 2.0, 3.5, 8.0}) {
            double[] jdx = {1.0 / s, 0.0};
            double[] jdy = {0.0, 1.0 / s};
            double truth = PX_RANGE * s;

            assertEquals("the Jacobian form must be exact under uniform scale",
                    truth, jacobianRange(jdx, jdy, 1, 0), 1e-9);
            assertEquals("and must not depend on the contour's direction there",
                    truth, jacobianRange(jdx, jdy, 0, 1), 1e-9);
            assertEquals("the form this replaces was already correct here, so nothing may move",
                    truth, fwidthRange(jdx, jdy), 1e-9);
        }
    }

    @Test
    public void aRotatedStemWasAlreadyBeingBlurred() {
        // A pure rotation preserves length, so the truth is unchanged by the angle.
        System.out.println("=== rotation, uniform scale 1 ===");
        double worstError = 0;
        for (int deg = 0; deg <= 90; deg += 15) {
            double r = Math.toRadians(deg);
            double[] jdx = {Math.cos(r), Math.sin(r)};
            double[] jdy = {-Math.sin(r), Math.cos(r)};

            double jac = jacobianRange(jdx, jdy, 1, 0);
            double fw = fwidthRange(jdx, jdy);
            worstError = Math.max(worstError, PX_RANGE / fw);

            System.out.printf(Locale.ROOT, "  %2ddeg  exact %.4f | jacobian %.4f | fwidth %.4f"
                    + "  (ramp %.2fx too wide)%n", deg, PX_RANGE, jac, fw, PX_RANGE / fw);

            assertEquals("a rotation cannot change how much field crosses a pixel",
                    PX_RANGE, jac, 1e-9);
        }

        // fwidth is an abs-SUM, so it overcounts most at 45 degrees -- by sqrt(2), which is a ramp
        // 41% wider than it should be. That was costing every rotated glyph, stroked or not.
        assertTrue("the old form was expected to blur a rotated stem; if it no longer does, this "
                + "test is measuring the wrong thing", worstError > 1.4);
    }

    @Test
    public void anisotropyNeedsADifferentAnswerPerDirection() {
        // scale(2, 1): a screen px covers half a texel across, a whole one down.
        double[] jdx = {0.5, 0.0};
        double[] jdy = {0.0, 1.0};

        double across = jacobianRange(jdx, jdy, 1, 0);
        double down = jacobianRange(jdx, jdy, 0, 1);
        double single = fwidthRange(jdx, jdy);

        System.out.printf(Locale.ROOT, "=== scale(2,1) === exact across %.4f, down %.4f"
                + " | fwidth answers %.4f for both%n", PX_RANGE * 2, PX_RANGE, single);

        assertEquals("a vertical contour is magnified twice, so twice the field crosses a pixel",
                PX_RANGE * 2, across, 1e-9);
        assertEquals("a horizontal one is not magnified at all", PX_RANGE, down, 1e-9);

        // The old form cannot be right for both, and is right for neither.
        assertTrue("fwidth's single answer is supposed to sit between the two it cannot tell apart",
                single > down && single < across);
    }
}
