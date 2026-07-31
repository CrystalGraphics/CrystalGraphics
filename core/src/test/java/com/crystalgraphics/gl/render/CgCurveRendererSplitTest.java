package com.crystalgraphics.gl.render;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * GL-free tests for {@link CgCurveRenderer}'s CPU-side cubic → quadratic splitting.
 *
 * <p>This is the half of the renderer that can be wrong without a driver's help, and it is where
 * the design's central claim lives: that taking a <em>quadratic</em> as the primitive costs nothing
 * in fidelity because a cubic splits into a handful of them exactly enough. If the splitting is
 * sloppy, every cubic drawn through this renderer is subtly wrong in a way no GL test would name —
 * it would just look slightly off.</p>
 */
public class CgCurveRendererSplitTest {

    private static final float EPS = 1.0e-4f;

    /** Enough room for the maximum segment count, nine floats each. */
    private float[] out() {
        return new float[CgCurveSplitter.MAX_CUBIC_SEGMENTS * 9];
    }

    // ── Segment count ─────────────────────────────────────────────────────────

    /**
     * A cubic whose third difference is zero <em>is</em> a quadratic, so it must not be split at
     * all. This is the common case for the renderer's own {@code line()} path and for gentle wires.
     */
    @Test
    public void cubicThatIsAlreadyQuadratic_splitsIntoOneSegment() {
        // Degree-elevate the quadratic (0,0) (50,100) (100,0) into an exactly-equivalent cubic:
        // C1 = P0 + 2/3*(Q1-P0), C2 = P2 + 2/3*(Q1-P2).
        float c1x = 0f + (2f / 3f) * (50f - 0f), c1y = 0f + (2f / 3f) * (100f - 0f);
        float c2x = 100f + (2f / 3f) * (50f - 100f), c2y = 0f + (2f / 3f) * (100f - 0f);

        float[] o = out();
        int n = CgCurveSplitter.splitCubic(0f, 0f, c1x, c1y, c2x, c2y, 100f, 0f, o);

        assertEquals("a degree-elevated quadratic needs no subdivision", 1, n);
        // And the single segment must recover the original quadratic's control point.
        assertEquals(50f, o[3], 1.0e-3f);
        assertEquals(100f, o[4], 1.0e-3f);
    }

    /** A violently curved cubic must not exceed the documented cap, however bad it is. */
    @Test
    public void wildCubic_isClampedToMaxSegments() {
        float[] o = out();
        int n = CgCurveSplitter.splitCubic(0f, 0f, 9000f, -9000f, -9000f, 9000f, 100f, 0f, o);
        assertTrue("segment count must be positive", n >= 1);
        assertTrue("segment count must be capped at MAX_CUBIC_SEGMENTS, was " + n,
                n <= CgCurveSplitter.MAX_CUBIC_SEGMENTS);
    }

    /** A fully degenerate cubic (every point identical) must not divide by zero or emit NaN. */
    @Test
    public void degenerateCubic_producesFiniteOutput() {
        float[] o = out();
        int n = CgCurveSplitter.splitCubic(7f, 7f, 7f, 7f, 7f, 7f, 7f, 7f, o);
        assertEquals(1, n);
        for (int i = 0; i < 9; i++) {
            assertTrue("emitted a non-finite control point at index " + i, isFinite(o[i]));
        }
    }

    // ── Structural guarantees ─────────────────────────────────────────────────

    /**
     * Consecutive segments must share an endpoint exactly. A gap here is invisible in a static
     * screenshot at low curvature and shows up as a hairline crack under zoom — the worst kind of
     * rendering bug to chase from a bug report.
     */
    @Test
    public void segments_areEndpointContinuous() {
        float[] o = out();
        int n = CgCurveSplitter.splitCubic(0f, 0f, 200f, 300f, -150f, 300f, 100f, 0f, o);
        assertTrue("this cubic should genuinely need splitting", n > 1);

        for (int i = 0; i < n - 1; i++) {
            int end = i * 9 + 6;
            int nextStart = (i + 1) * 9;
            assertEquals("segment " + i + " end X != segment " + (i + 1) + " start X",
                    o[end], o[nextStart], EPS);
            assertEquals("segment " + i + " end Y != segment " + (i + 1) + " start Y",
                    o[end + 1], o[nextStart + 1], EPS);
        }
    }

    /** The split must preserve the original curve's own endpoints exactly. */
    @Test
    public void split_preservesOriginalEndpoints() {
        float[] o = out();
        int n = CgCurveSplitter.splitCubic(12f, -4f, 200f, 300f, -150f, 300f, 88f, 41f, o);

        assertEquals(12f, o[0], EPS);
        assertEquals(-4f, o[1], EPS);

        int last = (n - 1) * 9;
        assertEquals(88f, o[last + 6], EPS);
        assertEquals(41f, o[last + 7], EPS);
    }

    /** Z is carried as zero throughout — v1 curves are planar by construction. */
    @Test
    public void split_emitsPlanarZ() {
        float[] o = out();
        int n = CgCurveSplitter.splitCubic(0f, 0f, 200f, 300f, -150f, 300f, 100f, 0f, o);
        for (int i = 0; i < n; i++) {
            assertEquals(0f, o[i * 9 + 2], 0f);
            assertEquals(0f, o[i * 9 + 5], 0f);
            assertEquals(0f, o[i * 9 + 8], 0f);
        }
    }

    // ── Accuracy — the claim the whole design rests on ────────────────────────

    /**
     * The piecewise quadratic must track the original cubic closely across its whole length.
     *
     * <p>Tolerance here is deliberately much looser than the splitter's own internal target: the
     * point is to catch a splitting scheme that is <em>structurally</em> wrong (bad control-point
     * derivation, mismatched sub-interval mapping), not to pin an exact error bound that would make
     * the test brittle against a future tolerance change.</p>
     */
    @Test
    public void piecewiseQuadratic_tracksTheOriginalCubic() {
        float x0 = 0f, y0 = 0f, c1x = 120f, c1y = 260f, c2x = -40f, c2y = 260f, x1 = 100f, y1 = 0f;

        float[] o = out();
        int n = CgCurveSplitter.splitCubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1, o);

        float worst = 0f;
        for (int i = 0; i <= 200; i++) {
            float s = i / 200f;
            float cx = cubicAt(x0, c1x, c2x, x1, s);
            float cy = cubicAt(y0, c1y, c2y, y1, s);

            int seg = Math.min((int) (s * n), n - 1);
            float u = s * n - seg;
            int b = seg * 9;
            float qx = quadAt(o[b], o[b + 3], o[b + 6], u);
            float qy = quadAt(o[b + 1], o[b + 4], o[b + 7], u);

            worst = Math.max(worst, (float) Math.hypot(cx - qx, cy - qy));
        }
        assertTrue("piecewise quadratic deviates from the cubic by " + worst
                + " units over " + n + " segments — the split is structurally wrong", worst < 1.0f);
    }

    // ── Gradient interpolation ────────────────────────────────────────────────

    /**
     * A split cubic emits one instance per segment, each carrying its own slice of the colour ramp.
     * If that interpolation were wrong, a gradient wire would band at every segment boundary — and
     * only for cubics, which is exactly the kind of asymmetry that gets blamed on the shader.
     */
    @Test
    public void lerpArgb_isPerChannelAndHitsBothEnds() {
        int a = 0xFF000000;
        int b = 0xFFFFFFFF;

        assertEquals(a, CgCurveSplitter.lerpArgb(a, b, 0f));
        assertEquals(b, CgCurveSplitter.lerpArgb(a, b, 1f));

        int mid = CgCurveSplitter.lerpArgb(a, b, 0.5f);
        assertEquals(0xFF, (mid >>> 24) & 0xFF);
        assertEquals(128, (mid >>> 16) & 0xFF);
        assertEquals(128, (mid >>> 8) & 0xFF);
        assertEquals(128, mid & 0xFF);
    }

    /**
     * The midpoint of a gradient must not depend on which end you started from. Truncating the
     * interpolation delta breaks this — ascending lands on 127, descending on 128 — so this pins
     * the rounding rather than the arbitrary choice of which side of 127.5 to land on.
     */
    @Test
    public void lerpArgb_isSymmetricUnderReversal() {
        int a = 0xFF000000;
        int b = 0xFFFFFFFF;
        assertEquals("reversing the gradient must mirror it exactly",
                CgCurveSplitter.lerpArgb(a, b, 0.5f) & 0xFF,
                CgCurveSplitter.lerpArgb(b, a, 0.5f) & 0xFF);
    }

    /** Equal endpoints must short-circuit to the exact value — no rounding drift on a solid colour. */
    @Test
    public void lerpArgb_equalEndpointsAreExact() {
        int c = 0x8837AB91;
        assertEquals(c, CgCurveSplitter.lerpArgb(c, c, 0.37f));
    }

    /** Alpha must interpolate too — a fading stroke is a real case, not a hypothetical. */
    @Test
    public void lerpArgb_interpolatesAlpha() {
        int opaque = 0xFF204060;
        int clear = 0x00204060;
        int mid = CgCurveSplitter.lerpArgb(opaque, clear, 0.5f);
        assertEquals(128, (mid >>> 24) & 0xFF);
        assertEquals(0x20, (mid >>> 16) & 0xFF);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static float cubicAt(float p0, float c1, float c2, float p3, float t) {
        float mt = 1f - t;
        return mt * mt * mt * p0 + 3f * mt * mt * t * c1 + 3f * mt * t * t * c2 + t * t * t * p3;
    }

    private static float quadAt(float p0, float p1, float p2, float t) {
        float mt = 1f - t;
        return mt * mt * p0 + 2f * mt * t * p1 + t * t * p2;
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
