package com.crystalgraphics.gl.render;

/**
 * CPU-side cubic → quadratic Bézier splitting for {@link CgCurveRenderer}, plus the colour
 * interpolation that keeps a split curve's gradient continuous across segment boundaries.
 *
 * <h3>Why this is not just private methods on {@link CgCurveRenderer}</h3>
 * <p>It is pure maths with no GPU state, and it must stay <em>reachable without one</em>.
 * {@link CgCurveRenderer} holds a {@code static final CgShaderBuffer} that allocates against
 * {@code CgBindingPoints} at class-init, so merely calling a static method on that class — even a
 * pure-maths one — initializes it and throws unless {@code CgRenderPipeline.init()} has already run.
 * That is exactly why {@code CgEngineBufferRegistry} seeds the {@code curve} token with a method
 * reference rather than a field read; the same hazard applies to anything else that wants this
 * arithmetic, tests very much included.</p>
 *
 * <p>Splitting this out means the maths can be unit tested with no GL context at all, which matters
 * because it is the half of the renderer that can be silently wrong: a bad split produces a curve
 * that still draws, still animates, and is merely the wrong shape.</p>
 */
final class CgCurveSplitter {

    /**
     * Upper bound on how many quadratics one cubic may split into. Four is the standard ceiling for
     * cubic-to-quadratic conversion at sub-pixel tolerance; beyond it the error term is already far
     * below anything visible at UI scale.
     */
    static final int MAX_CUBIC_SEGMENTS = 4;

    /** Flatness tolerance, in post-pose units. Sub-pixel at UI scale. */
    private static final float CUBIC_TOLERANCE = 0.1f;

    private CgCurveSplitter() {}

    /**
     * Splits a cubic Bézier into {@code n} quadratics, writing flattened {@code (p0,p1,p2)} triples
     * — nine floats per segment, Z always {@code 0} — into {@code out}, and returning {@code n}.
     *
     * <p>Segment count comes from the cubic's <em>third difference</em>
     * {@code d = p3 - 3*c2 + 3*c1 - p0}, which is what bounds the error of approximating a cubic by
     * the midpoint quadratic {@code Q1 = (3*C1 - P0 + 3*C2 - P3) / 4}. That error falls as
     * {@code |d|/n^3}, giving {@code n = ceil(cbrt(sqrt(3)*|d| / (18*tolerance)))}, clamped to
     * {@link #MAX_CUBIC_SEGMENTS}.</p>
     *
     * <p>A cubic that is a degree-elevated quadratic has {@code d == 0} and is correctly left
     * unsplit — which is the common case for the gentle S-curves a node graph draws.</p>
     *
     * @param out receives {@code n * 9} floats; must be at least {@code MAX_CUBIC_SEGMENTS * 9} long
     * @return the number of quadratic segments written, always in {@code [1, MAX_CUBIC_SEGMENTS]}
     */
    static int splitCubic(float x0, float y0,
                          float c1x, float c1y,
                          float c2x, float c2y,
                          float x1, float y1,
                          float[] out) {
        float dx = x1 - 3f * c2x + 3f * c1x - x0;
        float dy = y1 - 3f * c2y + 3f * c1y - y0;
        double dLen = Math.sqrt(dx * dx + dy * dy);

        int n = 1;
        if (dLen > 0d) {
            double ideal = Math.cbrt(Math.sqrt(3d) * dLen / (18d * CUBIC_TOLERANCE));
            n = (int) Math.ceil(ideal);
        }
        if (n < 1) n = 1;
        if (n > MAX_CUBIC_SEGMENTS) n = MAX_CUBIC_SEGMENTS;

        for (int i = 0; i < n; i++) {
            float t0 = i / (float) n;
            float t1 = (i + 1) / (float) n;
            float dt = t1 - t0;

            float ax = cubicAt(x0, c1x, c2x, x1, t0);
            float ay = cubicAt(y0, c1y, c2y, y1, t0);
            float bx = cubicAt(x0, c1x, c2x, x1, t1);
            float by = cubicAt(y0, c1y, c2y, y1, t1);

            // Control points of the sub-cubic over [t0,t1] — the endpoint tangents, scaled by the
            // sub-interval length. (Hermite form of de Casteljau's split, without the full tableau.)
            float a1x = ax + cubicDerivAt(x0, c1x, c2x, x1, t0) * dt / 3f;
            float a1y = ay + cubicDerivAt(y0, c1y, c2y, y1, t0) * dt / 3f;
            float b1x = bx - cubicDerivAt(x0, c1x, c2x, x1, t1) * dt / 3f;
            float b1y = by - cubicDerivAt(y0, c1y, c2y, y1, t1) * dt / 3f;

            float qx = (3f * a1x - ax + 3f * b1x - bx) * 0.25f;
            float qy = (3f * a1y - ay + 3f * b1y - by) * 0.25f;

            int o = i * 9;
            out[o]     = ax; out[o + 1] = ay; out[o + 2] = 0f;
            out[o + 3] = qx; out[o + 4] = qy; out[o + 5] = 0f;
            out[o + 6] = bx; out[o + 7] = by; out[o + 8] = 0f;
        }
        return n;
    }

    /** Cubic Bézier value at {@code t}. */
    static float cubicAt(float p0, float c1, float c2, float p3, float t) {
        float mt = 1f - t;
        return mt * mt * mt * p0
                + 3f * mt * mt * t * c1
                + 3f * mt * t * t * c2
                + t * t * t * p3;
    }

    /** Cubic Bézier first derivative at {@code t}. */
    static float cubicDerivAt(float p0, float c1, float c2, float p3, float t) {
        float mt = 1f - t;
        return 3f * mt * mt * (c1 - p0)
                + 6f * mt * t * (c2 - c1)
                + 3f * t * t * (p3 - c2);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Per-channel ARGB interpolation, so a split cubic's gradient stays continuous across segment
     * boundaries. Returns {@code a} exactly when both endpoints match, so a solid-colour stroke
     * cannot pick up rounding drift from being split.
     */
    static int lerpArgb(int a, int b, float t) {
        if (a == b) return a;
        return (lerpChannel(a, b, 24, t) << 24)
                | (lerpChannel(a, b, 16, t) << 16)
                | (lerpChannel(a, b, 8, t) << 8)
                | lerpChannel(a, b, 0, t);
    }

    /**
     * One 8-bit channel, interpolated with round-half-up rather than truncation.
     *
     * <p>The rounding is not incidental. Truncating the delta rounds an <em>ascending</em> ramp down
     * and a <em>descending</em> one up, so 0→255 and 255→0 met at 127 and 128 respectively: the same
     * gradient reversed end-for-end was not its own mirror. One unit of 255 is invisible in isolation
     * and systematic across a whole batch of split segments, which is the sort of thing that gets
     * noticed as banding long after anyone would think to look here.</p>
     */
    private static int lerpChannel(int a, int b, int shift, float t) {
        int ca = (a >>> shift) & 0xFF;
        int cb = (b >>> shift) & 0xFF;
        int v = (int) (ca + (cb - ca) * t + 0.5f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
