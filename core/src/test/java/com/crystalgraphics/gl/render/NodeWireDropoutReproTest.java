package com.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Headless reproduction of the dropout that travels along the gallery's shallow node wires.
 *
 * <h3>Why this test is shaped the way it is</h3>
 * <p>Three hypotheses were argued from the code and all three were wrong, so this stops arguing and
 * reproduces. It evaluates the shipped fragment maths in float precision — Java {@code float} and
 * GLSL {@code highp float} are the same IEEE-754 binary32 — over the exact control points the
 * gallery submits, across a full animation period.</p>
 *
 * <p>Crucially it evaluates coverage <b>twice per pixel</b>, and the difference is the diagnosis:</p>
 * <ul>
 *   <li><b>Bounded</b> — a segment may only contribute where the pixel lies inside that instance's
 *       derived bounding quad, which is what the GPU actually rasterises.</li>
 *   <li><b>Unbounded</b> — the same coverage with the quad ignored, i.e. what the distance maths
 *       alone claims.</li>
 * </ul>
 *
 * <p>A pixel covered when unbounded but not when bounded is a <b>bounding-quad</b> bug: the geometry
 * is right and the rasteriser was never asked to shade that pixel. A pixel missing in both is a
 * <b>maths</b> bug. One run separates them, which four rounds of reading the code did not.</p>
 */
public class NodeWireDropoutReproTest {

    private static final float SRC_X = 78f, DST_X = 342f;

    /** {srcY, dstY, sagPhase} — the three wires the gallery actually draws. */
    private static final float[][] WIRES = {
            { 26f, 34f, 0.0f },     // shallow, misbehaves
            { 52f, 70f, 1.8f },     // shallow, misbehaves
            { 78f, 34f, 1.6f },     // steep, clean
    };

    private static final float HALF_WIDTH = 2.5f;
    private static final float FEATHER = 1f;

    /** A pixel this far inside the stroke must be solidly painted — no antialiasing excuse. */
    private static final float SOLIDLY_INSIDE = 1.2f;

    @Test
    public void shallowWire1_hasNoHoleAcrossAFullAnimationCycle() {
        assertNoDropout(WIRES[0], "wire 1 (shallow)");
    }

    @Test
    public void shallowWire2_hasNoHoleAcrossAFullAnimationCycle() {
        assertNoDropout(WIRES[1], "wire 2 (shallow)");
    }

    @Test
    public void steepWire3_hasNoHoleAcrossAFullAnimationCycle() {
        assertNoDropout(WIRES[2], "wire 3 (steep, the control)");
    }

    // ── The scan ──────────────────────────────────────────────────────────────

    private void assertNoDropout(float[] wire, String label) {
        float srcY = wire[0], dstY = wire[1], phase = wire[2];
        float[] segs = new float[CgCurveSplitter.MAX_CUBIC_SEGMENTS * 9];

        for (int frame = 0; frame < 60; frame++) {
            float t = frame * (5.71f / 60f);
            float sag = 10f * (float) Math.sin(t * 1.1f + phase);

            int n = CgCurveSplitter.splitCubic(
                    SRC_X, srcY, SRC_X + 96f, srcY + sag,
                    DST_X - 96f, dstY - sag, DST_X, dstY, segs);

            // Walk the piecewise curve itself and probe a little either side of it — a hole has to
            // be ON the stroke to be visible, and this samples exactly there.
            for (int seg = 0; seg < n; seg++) {
                int o = seg * 9;
                for (int k = 0; k <= 50; k++) {
                    float u = k / 50f, mu = 1 - u;
                    float px = mu * mu * segs[o] + 2 * mu * u * segs[o + 3] + u * u * segs[o + 6];
                    float py = mu * mu * segs[o + 1] + 2 * mu * u * segs[o + 4] + u * u * segs[o + 7];

                    // Probe the FULL stroke width, not just its centre. The first version swept only
                    // +/-1.2px and found nothing; the artefact in the screenshots is a notch at the
                    // stroke's edge, which that scan walked straight past.
                    for (int off = -9; off <= 9; off++) {
                        float qy = py + off * (HALF_WIDTH * 0.95f / 9f);

                        // THE ORACLE MUST BE INDEPENDENT OF THE CODE UNDER TEST. The first version
                        // decided which pixels "should be covered" by asking the same maths it was
                        // checking, so wherever that maths was wrong the pixel was skipped rather
                        // than flagged — it could not fail at a segment joint even in principle,
                        // which is exactly where the artefact turned out to be. Ground truth is now
                        // the brute-forced distance to the ORIGINAL cubic.
                        double trueDist = distanceToCubic(px, qy, srcY, dstY, sag);
                        if (trueDist > HALF_WIDTH - 0.6) continue;   // not solidly inside the stroke

                        float bounded = coverage(px, qy, segs, n, true);
                        float unbounded = coverage(px, qy, segs, n, false);

                        assertTrue(label + ": HOLE at (" + px + ", " + qy + ") on frame " + frame
                                        + " (sag=" + sag + ", segments=" + n + "). True distance to the"
                                        + " cubic is " + trueDist + " so this pixel is solidly inside"
                                        + " the stroke, but coverage=" + bounded
                                        + " (unbounded=" + unbounded + ").",
                                bounded >= 0.9f);
                    }
                }
            }
        }
    }

    /**
     * Brute-forced distance from a point to the ORIGINAL cubic — the independent oracle.
     *
     * <p>Sampled in double at a density far finer than a pixel, so it is exact enough to judge a
     * float result by, and it knows nothing about how the shader splits or solves.</p>
     */
    private static double distanceToCubic(double px, double py, float srcY, float dstY, float sag) {
        double x0 = SRC_X,        y0 = srcY;
        double x1 = SRC_X + 96,   y1 = srcY + sag;
        double x2 = DST_X - 96,   y2 = dstY - sag;
        double x3 = DST_X,        y3 = dstY;

        double best = Double.MAX_VALUE;
        final int N = 20_000;
        for (int i = 0; i <= N; i++) {
            double u = i / (double) N, mu = 1 - u;
            double a = mu * mu * mu, b = 3 * mu * mu * u, c = 3 * mu * u * u, d = u * u * u;
            double qx = a * x0 + b * x1 + c * x2 + d * x3;
            double qy = a * y0 + b * y1 + c * y2 + d * y3;
            best = Math.min(best, Math.hypot(qx - px, qy - py));
        }
        return best;
    }

    /** Max coverage over all segments, optionally restricted to each instance's bounding quad. */
    private static float coverage(float px, float py, float[] segs, int n, boolean applyBounds) {
        float best = 0f;
        for (int i = 0; i < n; i++) {
            int o = i * 9;
            float ax = segs[o],     ay = segs[o + 1];
            float bx = segs[o + 3], by = segs[o + 4];
            float cx = segs[o + 6], cy = segs[o + 7];

            if (applyBounds && !insideBoundingQuad(px, py, ax, ay, bx, by, cx, cy)) continue;

            best = Math.max(best, strokeCoverage(px, py, ax, ay, bx, by, cx, cy));
        }
        return best;
    }

    /** CG_CURVE_PAD / CG_CURVE_HULL_MIN / CG_CURVE_HULL_MAX, exactly as cg_env.glsl builds them. */
    private static boolean insideBoundingQuad(float px, float py,
                                              float ax, float ay, float bx, float by,
                                              float cx, float cy) {
        float pad = HALF_WIDTH * 1.41422f + FEATHER + 1f;
        float minX = Math.min(Math.min(ax, bx), cx) - pad;
        float maxX = Math.max(Math.max(ax, bx), cx) + pad;
        float minY = Math.min(Math.min(ay, by), cy) - pad;
        float maxY = Math.max(Math.max(ay, by), cy) + pad;
        return px >= minX && px <= maxX && py >= minY && py <= maxY;
    }

    /** lib/stroke.glsl for a round cap (the cap block is skipped entirely for CAP_ROUND). */
    private static float strokeCoverage(float px, float py,
                                        float ax, float ay, float bx, float by,
                                        float cx, float cy) {
        float dist = sdfBezier(px, py, ax, ay, bx, by, cx, cy);
        float signed = dist - HALF_WIDTH;
        return 1f - smoothstep(-FEATHER * 0.5f, FEATHER * 0.5f, signed);
    }

    /** lib/sdf.glsl's sdf_bezier, including the degenerate guards and the cube-root guard. */
    private static float sdfBezier(float px, float py,
                                   float ax, float ay, float bx, float by, float cx, float cy) {
        float bX = ax - 2f * bx + cx, bY = ay - 2f * by + cy;
        float bb = bX * bX + bY * bY;

        float ex = cx - ax, ey = cy - ay;
        float extent = Math.max(ex * ex + ey * ey, 1.0e-12f);
        if (bb < 1.0e-6f * extent || bb < 4.0e-2f) {
            return segmentDistance(px, py, ax, ay, cx, cy);
        }

        // Mirrors the shipped sdf_bezier: solve in the curve's own frame. Without this the port
        // diverges from the shader it is meant to model, and the test measures the wrong code.
        float scale = Math.max(Math.max((float) Math.hypot(bx - ax, by - ay),
                                        (float) Math.hypot(cx - bx, cy - by)), 1.0e-6f);
        float inv = 1f / scale;
        float aX = (bx - ax) * inv, aY = (by - ay) * inv;
        bX *= inv; bY *= inv;
        float cX = aX * 2f, cY = aY * 2f;
        float dX = (ax - px) * inv, dY = (ay - py) * inv;

        float kk = 1f / (bX * bX + bY * bY);
        float kx = kk * (aX * bX + aY * bY);
        float ky = kk * (2f * (aX * aX + aY * aY) + (dX * bX + dY * bY)) / 3f;
        float kz = kk * (dX * aX + dY * aY);

        float pp = ky - kx * kx;
        float q = kx * (2f * kx * kx - 3f * ky) + kz;
        float h = q * q + 4f * pp * pp * pp;

        float res, t;
        if (h >= 0f) {
            float hs = (float) Math.sqrt(h);
            float x0 = (hs - q) / 2f, x1 = (-hs - q) / 2f;
            float u = Math.signum(x0) * (float) Math.pow(Math.max(Math.abs(x0), 1.0e-30f), 1.0 / 3.0);
            float v = Math.signum(x1) * (float) Math.pow(Math.max(Math.abs(x1), 1.0e-30f), 1.0 / 3.0);
            t = clamp01(u + v - kx);
            res = dot2(dX + (cX + bX * t) * t, dY + (cY + bY * t) * t);
        } else {
            float z = (float) Math.sqrt(-pp);
            float v = (float) Math.acos(clamp(q / (pp * z * 2f), -1f, 1f)) / 3f;
            float m = (float) Math.cos(v);
            float nn = (float) Math.sin(v) * 1.732050808f;
            float t1 = clamp01((m + m) * z - kx);
            float t2 = clamp01((-nn - m) * z - kx);
            float t3 = clamp01((nn - m) * z - kx);
            float d1 = dot2(dX + (cX + bX * t1) * t1, dY + (cY + bY * t1) * t1);
            float d2 = dot2(dX + (cX + bX * t2) * t2, dY + (cY + bY * t2) * t2);
            float d3 = dot2(dX + (cX + bX * t3) * t3, dY + (cY + bY * t3) * t3);
            res = d1;
            if (d2 < res) res = d2;
            if (d3 < res) res = d3;
        }
        return (float) Math.sqrt(res) * scale;
    }

    private static float segmentDistance(float px, float py, float ax, float ay, float bx, float by) {
        float pax = px - ax, pay = py - ay;
        float bax = bx - ax, bay = by - ay;
        float denom = bax * bax + bay * bay;
        float t = denom > 0f ? clamp01((pax * bax + pay * bay) / denom) : 0f;
        return (float) Math.hypot(pax - bax * t, pay - bay * t);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float u = clamp01((x - e0) / (e1 - e0));
        return u * u * (3f - 2f * u);
    }

    private static float dot2(float x, float y) { return x * x + y * y; }
    private static float clamp01(float v) { return clamp(v, 0f, 1f); }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
