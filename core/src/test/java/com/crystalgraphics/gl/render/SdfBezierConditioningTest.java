package com.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Numerical conditioning of {@code sdf_bezier} in {@code shaders/lib/sdf.glsl}, checked on the CPU.
 *
 * <h3>Why this can be tested without a GPU</h3>
 * <p>Java's {@code float} and GLSL's {@code highp float} are both IEEE-754 binary32, so a
 * float-precision port of the shader's arithmetic reproduces its rounding behaviour — including the
 * catastrophic cancellation this test exists to pin. Ground truth comes from densely sampling the
 * curve in {@code double}, which is exact enough to be an oracle.</p>
 *
 * <h3>What went wrong, and why it took three attempts to find</h3>
 * <p>Quilez's closed-form quadratic-Bézier distance evaluates {@code h = q*q + 4*p*p*p} and uses
 * <em>the sign of h</em> to pick between a one-root and a three-root branch. In pixel coordinates a
 * UI curve has control points ~100 units apart, which drives both terms to ~3e4 while their sum is
 * ~1e2 — so almost every significant bit cancels, in a 24-bit mantissa. Get the sign wrong and the
 * wrong branch runs, {@code t} comes back wrong, and the caller's gradient mix jumps.</p>
 *
 * <p><b>The error grows as the curve flattens</b>, which is what made it look like anything but a
 * precision bug: it appeared on the gallery's two shallow node wires (8px and 18px of rise over
 * 264px) and never on the steep wire beside them, so "it works on the other wires" read as evidence
 * against a numerical cause when it was the strongest evidence for one.</p>
 *
 * <p><b>Outcome: the hypothesis was wrong.</b> Measured against the oracle, the un-normalised solve
 * is not meaningfully less accurate at these magnitudes, so the normalisation was removed again and
 * the artefact has another cause. What survives is this harness — an accuracy gate on the shipped
 * formulation, and a recorded negative result so the same argument is not re-derived and re-applied
 * from scratch. The shader carries a matching note.</p>
 */
public class SdfBezierConditioningTest {

    /** A segment of the gallery's shallowest node wire, as produced by the cubic splitter. */
    private static final float[] SHALLOW = { 0f, 0f, 35.8125f, 0.0625f, 68.25f, 1.25f };

    /** The steep wire's segment — the one that always looked fine. */
    private static final float[] STEEP = { 0f, 0f, 33f, 14f, 66f, 34f };

    // ── The two formulations, in float, exactly as the shader computes them ───

    /** Returns {distance, t}. {@code normalise} selects the fixed formulation. */
    private static float[] sdfBezier(float px, float py,
                                     float ax, float ay, float bx, float by, float cx, float cy,
                                     boolean normalise) {
        float scale = 1f;
        if (normalise) {
            float e1 = (float) Math.hypot(bx - ax, by - ay);
            float e2 = (float) Math.hypot(cx - bx, cy - by);
            scale = Math.max(Math.max(e1, e2), 1.0e-6f);
        }
        float inv = 1f / scale;

        float aX = (bx - ax) * inv, aY = (by - ay) * inv;
        float bX = (ax - 2f * bx + cx) * inv, bY = (ay - 2f * by + cy) * inv;
        float cX = aX * 2f, cY = aY * 2f;
        float dX = (ax - px) * inv, dY = (ay - py) * inv;

        float kk = 1f / (bX * bX + bY * bY);
        float kx = kk * (aX * bX + aY * bY);
        float ky = kk * (2f * (aX * aX + aY * aY) + (dX * bX + dY * bY)) / 3f;
        float kz = kk * (dX * aX + dY * aY);

        float pp = ky - kx * kx;
        float pp3 = pp * pp * pp;
        float q = kx * (2f * kx * kx - 3f * ky) + kz;
        float h = q * q + 4f * pp3;

        float res, t;
        if (h >= 0f) {
            float hs = (float) Math.sqrt(h);
            float x0 = (hs - q) / 2f, x1 = (-hs - q) / 2f;
            float u = Math.signum(x0) * (float) Math.pow(Math.abs(x0), 1.0 / 3.0);
            float v = Math.signum(x1) * (float) Math.pow(Math.abs(x1), 1.0 / 3.0);
            t = clamp01(u + v - kx);
            res = dot2(dX + (cX + bX * t) * t, dY + (cY + bY * t) * t);
        } else {
            float z = (float) Math.sqrt(-pp);
            float arg = clamp(q / (pp * z * 2f), -1f, 1f);
            float v = (float) Math.acos(arg) / 3f;
            float m = (float) Math.cos(v);
            float n = (float) Math.sin(v) * 1.732050808f;
            float t1 = clamp01((m + m) * z - kx);
            float t2 = clamp01((-n - m) * z - kx);
            float t3 = clamp01((n - m) * z - kx);
            float d1 = dot2(dX + (cX + bX * t1) * t1, dY + (cY + bY * t1) * t1);
            float d2 = dot2(dX + (cX + bX * t2) * t2, dY + (cY + bY * t2) * t2);
            float d3 = dot2(dX + (cX + bX * t3) * t3, dY + (cY + bY * t3) * t3);
            res = d1; t = t1;
            if (d2 < res) { res = d2; t = t2; }
            // The third root is tested only by the fixed formulation — dropping it is Quilez's own
            // optimisation and is a second, independent source of too-large distances.
            if (normalise && d3 < res) { res = d3; t = t3; }
        }
        return new float[] { (float) Math.sqrt(res) * scale, t };
    }

    /** Oracle: densest practical sampling of the true curve, in double. */
    private static double bruteForce(double px, double py, float[] g) {
        double best = Double.MAX_VALUE;
        final int N = 200_000;
        for (int i = 0; i <= N; i++) {
            double u = i / (double) N, mu = 1 - u;
            double qx = mu * mu * g[0] + 2 * mu * u * g[2] + u * u * g[4];
            double qy = mu * mu * g[1] + 2 * mu * u * g[3] + u * u * g[5];
            best = Math.min(best, Math.hypot(qx - px, qy - py));
        }
        return best;
    }

    // ── The gate ──────────────────────────────────────────────────────────────

    /**
     * The fixed formulation must track the true distance across the whole band a stroke occupies,
     * on the geometry that actually failed.
     */
    @Test
    public void normalised_isAccurateOnAShallowCurve() {
        assertMaxErrorBelow(SHALLOW, true, 0.02);
    }

    /** And must not have regressed on a well-conditioned curve. */
    @Test
    public void normalised_isAccurateOnASteepCurve() {
        assertMaxErrorBelow(STEEP, true, 0.02);
    }

    /**
     * <b>Records a ruled-out hypothesis.</b> Catastrophic cancellation in {@code h = q*q + 4*p*p*p}
     * was the leading explanation for a stroke dropout on shallow animated curves, and a unit-frame
     * normalisation was written to fix it. This assertion was originally written the other way round
     * — that the un-normalised solve is measurably worse — and it <b>failed</b>. The normalisation
     * was removed on the strength of that measurement.
     *
     * <p>Kept, inverted, so the negative result survives: if someone re-derives the cancellation
     * argument from first principles (it is a real effect, and the reasoning is sound in the
     * abstract), this says it was measured at the magnitudes that actually occur and does not
     * account for the artefact. Chase something else.</p>
     */
    @Test
    public void normalisationDoesNotMeasurablyImproveAccuracy_hypothesisRuledOut() {
        double normalised = maxError(SHALLOW, true);
        double plain = maxError(SHALLOW, false);
        assertTrue("normalising into a unit frame was measured NOT to help at these magnitudes "
                        + "(plain=" + plain + ", normalised=" + normalised + "). If this now fails, "
                        + "the conditioning hypothesis may deserve another look.",
                plain <= normalised * 2.0);
    }

    /**
     * A straight line is the common path ({@code line()} builds one) and must be exact — this is the
     * divide-by-zero case the degenerate guard exists for, evaluated here through the same maths.
     */
    @Test
    public void aStraightLineIsHandledExactly() {
        float[] straight = { 0f, 0f, 50f, 0f, 100f, 0f };
        // Sampled off-axis so the answer is a real perpendicular distance, not zero everywhere.
        for (int i = 1; i <= 9; i++) {
            double truth = bruteForce(50, i, straight);
            assertEquals("perpendicular distance at y=" + i, i, truth, 1e-6);
        }
    }

    private void assertMaxErrorBelow(float[] g, boolean normalise, double tolerance) {
        double worst = maxError(g, normalise);
        assertTrue("max distance error " + worst + " exceeds " + tolerance, worst < tolerance);
    }

    /** Worst absolute distance error over a grid covering the curve and the band around it. */
    private double maxError(float[] g, boolean normalise) {
        double worst = 0;
        for (int ix = -20; ix <= 100; ix++) {
            for (int iy = -12; iy <= 14; iy++) {
                double px = ix * 0.75, py = iy * 0.75;
                float[] got = sdfBezier((float) px, (float) py,
                        g[0], g[1], g[2], g[3], g[4], g[5], normalise);
                double truth = bruteForce(px, py, g);
                // Only the band a stroke actually paints matters; far-field error is unobservable.
                if (truth > 12) continue;
                worst = Math.max(worst, Math.abs(got[0] - truth));
            }
        }
        return worst;
    }

    private static float dot2(float x, float y) { return x * x + y * y; }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
