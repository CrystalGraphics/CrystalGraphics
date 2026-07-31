package com.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Does {@code sdf_bezier}'s accuracy depend on where the curve sits on screen?
 *
 * <h3>Why this test exists</h3>
 * <p>A dropout on the gallery's node wires was chased through four wrong hypotheses. The evidence
 * that broke it open: two wires with <b>identical shape</b>, in two different canvases, behave
 * differently — one glitches, one does not. Shape, colour, animation, splitting and submission order
 * were all eliminated by a probe row. The only remaining difference is the curve's <b>absolute
 * position</b>, since the shader receives screen coordinates with the canvas origin and the page's
 * scroll offset already baked in.</p>
 *
 * <p>An earlier test measured cancellation error in <em>canvas-local</em> coordinates (x 78–342,
 * y 26–78) and concluded it did not matter. That was the one regime where it would not show:
 * {@code sdf_bezier} computes {@code d = A - p} in absolute coordinates, so every intermediate scales
 * with the origin, while the answer — a distance of a few pixels — does not. The relative error
 * therefore grows with however far down the page a curve happens to be drawn.</p>
 *
 * <p>This measures the same geometry at a sweep of plausible screen origins, and compares the
 * as-shipped formulation against one that translates to the curve's own frame first.</p>
 */
public class SdfBezierAbsoluteOriginTest {

    /** The node wire that misbehaves, in canvas-local coordinates. */
    private static final float[] SHALLOW = { 78f, 26f, 210f, 30f, 342f, 34f };

    /** Distance error that would be visible as a dropout in a 2.5px half-width stroke. */
    private static final double VISIBLE = 0.25;

    /**
     * The shipped formulation must stay accurate wherever on screen the curve lands.
     *
     * <p>A UI scrolls, so "accurate near the origin" is not a property anything can rely on.</p>
     */
    @Test
    public void accuracyMustNotDependOnScreenPosition() {
        double atOrigin = maxError(SHALLOW, 0f, 0f, false);
        double worstOffset = 0;
        float worstX = 0, worstY = 0;

        for (float oy = 0; oy <= 1200; oy += 150) {
            for (float ox = 0; ox <= 1600; ox += 200) {
                double e = maxError(SHALLOW, ox, oy, false);
                if (e > worstOffset) { worstOffset = e; worstX = ox; worstY = oy; }
            }
        }

        assertTrue("distance error grows with screen position: " + atOrigin + " at the origin but "
                        + worstOffset + " at (" + worstX + ", " + worstY + "). A scrolling page moves "
                        + "curves through exactly this range, so the stroke degrades as it scrolls.",
                worstOffset < VISIBLE);
    }

    /**
     * If the above fails, this says whether translating to the curve's own frame is the answer —
     * so the fix is chosen on a measurement rather than on the plausibility of the argument.
     */
    @Test
    public void translatingToTheCurvesOwnFrameRemovesAnyPositionDependence() {
        double worstPlain = 0, worstLocal = 0;
        for (float oy = 0; oy <= 1200; oy += 150) {
            for (float ox = 0; ox <= 1600; ox += 200) {
                worstPlain = Math.max(worstPlain, maxError(SHALLOW, ox, oy, false));
                worstLocal = Math.max(worstLocal, maxError(SHALLOW, ox, oy, true));
            }
        }
        assertTrue("translating to the curve's own frame did not help (plain=" + worstPlain
                + ", local=" + worstLocal + ")", worstLocal < VISIBLE);
    }

    // ── Machinery ─────────────────────────────────────────────────────────────

    /** Worst distance error over the painted band, with the curve translated to (ox, oy). */
    private double maxError(float[] g, float ox, float oy, boolean translateToLocal) {
        float ax = g[0] + ox, ay = g[1] + oy;
        float bx = g[2] + ox, by = g[3] + oy;
        float cx = g[4] + ox, cy = g[5] + oy;

        double worst = 0;
        for (int k = 0; k <= 40; k++) {
            float u = k / 40f, mu = 1 - u;
            double px = mu * mu * ax + 2 * mu * u * bx + u * u * cx;
            double py = mu * mu * ay + 2 * mu * u * by + u * u * cy;
            for (int off = -4; off <= 4; off++) {
                double qy = py + off * 0.8;
                float got = sdfBezier((float) px, (float) qy, ax, ay, bx, by, cx, cy, translateToLocal);
                double truth = bruteForce(px, qy, ax, ay, bx, by, cx, cy);
                if (truth > 4.0) continue;          // outside the painted band
                worst = Math.max(worst, Math.abs(got - truth));
            }
        }
        return worst;
    }

    /** Oracle in double, sampled densely — exact enough to judge a float result by. */
    private static double bruteForce(double px, double py,
                                     double ax, double ay, double bx, double by,
                                     double cx, double cy) {
        double best = Double.MAX_VALUE;
        final int N = 4_000;
        for (int i = 0; i <= N; i++) {
            double u = i / (double) N, mu = 1 - u;
            double qx = mu * mu * ax + 2 * mu * u * bx + u * u * cx;
            double qy = mu * mu * ay + 2 * mu * u * by + u * u * cy;
            best = Math.min(best, Math.hypot(qx - px, qy - py));
        }
        return best;
    }

    /** The shipped solve in float; {@code toLocal} translates everything to A first. */
    private static float sdfBezier(float px, float py,
                                   float ax, float ay, float bx, float by, float cx, float cy,
                                   boolean toLocal) {
        if (toLocal) {
            px -= ax; py -= ay;
            bx -= ax; by -= ay;
            cx -= ax; cy -= ay;
            ax = 0f;  ay = 0f;
        }

        float bX = ax - 2f * bx + cx, bY = ay - 2f * by + cy;
        float bb = bX * bX + bY * bY;

        float ex = cx - ax, ey = cy - ay;
        float extent = Math.max(ex * ex + ey * ey, 1.0e-12f);
        if (bb < 1.0e-6f * extent || bb < 4.0e-2f) {
            float pax = px - ax, pay = py - ay;
            float bax = cx - ax, bay = cy - ay;
            float den = bax * bax + bay * bay;
            float ts = den > 0f ? clamp01((pax * bax + pay * bay) / den) : 0f;
            return (float) Math.hypot(pax - bax * ts, pay - bay * ts);
        }

        float aX = bx - ax, aY = by - ay;
        float cX = aX * 2f, cY = aY * 2f;
        float dX = ax - px, dY = ay - py;

        float kk = 1f / bb;
        float kx = kk * (aX * bX + aY * bY);
        float ky = kk * (2f * (aX * aX + aY * aY) + (dX * bX + dY * bY)) / 3f;
        float kz = kk * (dX * aX + dY * aY);

        float pp = ky - kx * kx;
        float q = kx * (2f * kx * kx - 3f * ky) + kz;
        float h = q * q + 4f * pp * pp * pp;

        float res;
        if (h >= 0f) {
            float hs = (float) Math.sqrt(h);
            float x0 = (hs - q) / 2f, x1 = (-hs - q) / 2f;
            float u = Math.signum(x0) * (float) Math.pow(Math.max(Math.abs(x0), 1.0e-30f), 1.0 / 3.0);
            float v = Math.signum(x1) * (float) Math.pow(Math.max(Math.abs(x1), 1.0e-30f), 1.0 / 3.0);
            float t = clamp01(u + v - kx);
            res = dot2(dX + (cX + bX * t) * t, dY + (cY + bY * t) * t);
        } else {
            float z = (float) Math.sqrt(-pp);
            float v = (float) Math.acos(clamp(q / (pp * z * 2f), -1f, 1f)) / 3f;
            float m = (float) Math.cos(v);
            float nn = (float) Math.sin(v) * 1.732050808f;
            float t1 = clamp01((m + m) * z - kx);
            float t2 = clamp01((-nn - m) * z - kx);
            float t3 = clamp01((nn - m) * z - kx);
            res = Math.min(dot2(dX + (cX + bX * t1) * t1, dY + (cY + bY * t1) * t1),
                  Math.min(dot2(dX + (cX + bX * t2) * t2, dY + (cY + bY * t2) * t2),
                           dot2(dX + (cX + bX * t3) * t3, dY + (cY + bY * t3) * t3)));
        }
        return (float) Math.sqrt(res);
    }

    private static float dot2(float x, float y) { return x * x + y * y; }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
