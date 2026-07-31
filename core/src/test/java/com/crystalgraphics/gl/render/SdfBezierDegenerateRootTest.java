package com.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Diagnostic for the dropout that travels along shallow animated curves.
 *
 * <h3>The hypothesis under test</h3>
 * <p>{@code sdf_bezier}'s one-root branch computes {@code sign(x) * pow(abs(x), 1/3)}. GLSL
 * implements {@code pow(x,y)} as {@code exp2(y * log2(x))}, so {@code x == 0} evaluates
 * {@code log2(0) = -inf}, then {@code y * -inf = -inf} — and drivers differ on whether that returns
 * {@code 0} or {@code NaN}. Paired with {@code sign(0) == 0} the NaN path yields {@code 0 * NaN},
 * which is {@code NaN}, and that reaches {@code t}, the coverage, and the caller's gradient — the
 * fragment vanishes.</p>
 *
 * <p>{@code x = (±h - q)/2} is exactly zero when {@code p = ky - kx² == 0}. That is a
 * <b>codimension-1 locus</b>: a thin curve through the pixel plane rather than a region. As a stroke
 * animates, the locus sweeps across it — which is what "one empty tiny quad running through the wire
 * left to right" looks like, and why it depends on the individual wire's geometry.</p>
 *
 * <h3>What this test can and cannot prove</h3>
 * <p>Java's {@code Math.pow(0, 1/3)} returns {@code 0}, so the NaN itself is not reproducible here —
 * it is a driver behaviour. What IS checkable on the CPU, in the same float precision, is whether
 * the geometry ever drives {@code x} to (or through) zero <em>inside the band a stroke actually
 * paints</em>. If it never gets close, the hypothesis is dead and the shader must not be changed on
 * the strength of it. If it does, the guard is justified.</p>
 *
 * <p>Both the failing wires and the clean one are measured, because the whole question is why two of
 * three misbehave.</p>
 *
 * <h3>Outcome: confirmed</h3>
 * <p>The two shallow wires drive {@code |x|} below {@code 1e-3} inside the painted band; the steep
 * wire stays more than an order of magnitude further away. That is the discriminator, and it is the
 * reason the guard in {@code sdf_bezier}'s one-root branch exists. These tests stay as the pin: if
 * someone removes the {@code max()} as redundant, the mechanism is still described here and the
 * geometry that reaches it is still measured.</p>
 */
public class SdfBezierDegenerateRootTest {

    /** Gallery node-wire layout, in canvas-local coordinates. */
    private static final float SRC_X = 78f, DST_X = 342f;

    /** {srcY, dstY, sagPhase} for the three wires actually drawn — (s+d) even. */
    private static final float[][] WIRES = {
            { 26f, 34f, 0.0f },        // wire 1 — shallow, misbehaves
            { 52f, 70f, 1.8f },        // wire 2 — shallow, misbehaves
            { 78f, 34f, 1.6f },        // wire 3 — steep, clean
    };

    /** Half-width 2.5 + feather 1 → the stroke paints within ~3px of the curve. */
    private static final double PAINTED_BAND = 3.5;

    @Test
    public void theShallowWiresDriveTheCubeRootArgumentThroughZeroInsideTheStroke() {
        double shallow1 = minAbsRootArgInBand(WIRES[0]);
        double shallow2 = minAbsRootArgInBand(WIRES[1]);

        assertTrue("wire 1 never approaches the degenerate root (min |x| = " + shallow1
                + "); if this holds, pow(0,1/3) cannot be the cause", shallow1 < 1.0e-3);
        assertTrue("wire 2 never approaches the degenerate root (min |x| = " + shallow2
                + "); if this holds, pow(0,1/3) cannot be the cause", shallow2 < 1.0e-3);
    }

    /**
     * The steep wire is the control. If it reaches the degenerate locus just as closely, then the
     * locus is not what separates the two failing wires from the clean one and this whole line of
     * reasoning is wrong — regardless of how good the mechanism sounds.
     */
    @Test
    public void theSteepWireIsMeasurablyFurtherFromTheDegenerateRoot() {
        double worstShallow = Math.min(minAbsRootArgInBand(WIRES[0]), minAbsRootArgInBand(WIRES[1]));
        double steep = minAbsRootArgInBand(WIRES[2]);

        assertTrue("the steep wire approaches the degenerate root as closely as the shallow ones "
                        + "(steep=" + steep + ", shallow=" + worstShallow + ") — so this cannot be "
                        + "what distinguishes them, and the hypothesis is refuted",
                steep > worstShallow * 10.0);
    }

    // ── Machinery ─────────────────────────────────────────────────────────────

    /**
     * Smallest {@code |x|} reached over the animation, across every split segment, restricted to
     * pixels the stroke actually paints. {@code x} is the cube-root argument of the one-root branch.
     */
    private double minAbsRootArgInBand(float[] wire) {
        float srcY = wire[0], dstY = wire[1], phase = wire[2];
        float[] segs = new float[CgCurveSplitter.MAX_CUBIC_SEGMENTS * 9];

        double best = Double.MAX_VALUE;

        // One full period of sag = 2*pi/1.1 ≈ 5.71s, sampled finely.
        for (int frame = 0; frame < 400; frame++) {
            float t = frame * (5.71f / 400f);
            float sag = 10f * (float) Math.sin(t * 1.1f + phase);

            int n = CgCurveSplitter.splitCubic(
                    SRC_X, srcY,
                    SRC_X + 96f, srcY + sag,
                    DST_X - 96f, dstY - sag,
                    DST_X, dstY,
                    segs);

            for (int i = 0; i < n; i++) {
                int o = i * 9;
                float ax = segs[o],     ay = segs[o + 1];
                float bx = segs[o + 3], by = segs[o + 4];
                float cx = segs[o + 6], cy = segs[o + 7];

                // Walk the curve and probe perpendicular offsets across the painted band.
                for (int k = 0; k <= 40; k++) {
                    float u = k / 40f, mu = 1 - u;
                    float px = mu * mu * ax + 2 * mu * u * bx + u * u * cx;
                    float py = mu * mu * ay + 2 * mu * u * by + u * u * cy;
                    for (int off = -7; off <= 7; off++) {
                        double probeY = py + off * (PAINTED_BAND / 7.0);
                        best = Math.min(best, absRootArg(px, (float) probeY, ax, ay, bx, by, cx, cy));
                    }
                }
            }
        }
        return best;
    }

    /**
     * {@code min(|x0|, |x1|)} for the one-root branch at this point, or {@code MAX_VALUE} when the
     * three-root branch runs instead (where the cube root is never evaluated).
     *
     * <p>Float throughout — Java {@code float} and GLSL {@code highp float} are both IEEE-754
     * binary32, so the rounding that decides how close to zero this gets is the same.</p>
     */
    private static double absRootArg(float px, float py,
                                     float ax, float ay, float bx, float by, float cx, float cy) {
        float bX = ax - 2f * bx + cx, bY = ay - 2f * by + cy;
        float bb = bX * bX + bY * bY;
        if (bb < 4.0e-2f) return Double.MAX_VALUE;   // shader routes these to sdf_segment

        float aX = bx - ax, aY = by - ay;
        float dX = ax - px, dY = ay - py;

        float kk = 1f / bb;
        float kx = kk * (aX * bX + aY * bY);
        float ky = kk * (2f * (aX * aX + aY * aY) + (dX * bX + dY * bY)) / 3f;
        float kz = kk * (dX * aX + dY * aY);

        float pp = ky - kx * kx;
        float q = kx * (2f * kx * kx - 3f * ky) + kz;
        float h = q * q + 4f * pp * pp * pp;

        if (h < 0f) return Double.MAX_VALUE;         // three-root branch, no cube root taken

        float hs = (float) Math.sqrt(h);
        float x0 = (hs - q) / 2f;
        float x1 = (-hs - q) / 2f;
        return Math.min(Math.abs(x0), Math.abs(x1));
    }
}
