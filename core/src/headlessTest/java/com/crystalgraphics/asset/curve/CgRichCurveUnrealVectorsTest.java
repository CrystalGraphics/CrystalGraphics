package com.crystalgraphics.asset.curve;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A curve samples as Unreal's {@code FRichCurve::Eval} on every key form. The reference below follows
 * {@code Engine/Private/Curves/CurveEvaluation.cpp:96-250} ({@code EvalForTwoKeys}, {@code WeightedEvalForTwoKeys}) in
 * double precision, solving the weighted time by bisection instead of Cardano; the curve under test must agree on a
 * dense grid, inside the keys and on both extrapolation sides.
 */
public class CgRichCurveUnrealVectorsTest {

    private static final float EPS = 2e-4f;

    /** One key in the reference: interpolation 0 constant, 1 linear, 2 cubic; weight 0 none, 1 arrive, 2 leave, 3 both. */
    private record K(double t, double v, int interp, double arrive, double leave, double aw, double lw, int weight) {
        List<Object> file() {
            List<Object> k = new ArrayList<>(Arrays.asList(t, v));
            if (interp == 0) {
                k.add("constant");
            } else if (interp == 1) {
                k.add("linear");
            } else {
                k.addAll(Arrays.asList("cubic", "user", arrive, leave));
                if (weight != 0) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("arrive_weight", aw);
                    w.put("leave_weight", lw);
                    w.put("weight_mode", new String[] {"none", "arrive", "leave", "both"}[weight]);
                    k.add(w);
                }
            }
            return k;
        }
    }

    private static K constant(double t, double v) {
        return new K(t, v, 0, 0, 0, 0, 0, 0);
    }

    private static K linear(double t, double v) {
        return new K(t, v, 1, 0, 0, 0, 0, 0);
    }

    private static K cubic(double t, double v, double arrive, double leave) {
        return new K(t, v, 2, arrive, leave, 0, 0, 0);
    }

    private static K weighted(double t, double v, double arrive, double leave, double aw, double lw, int mode) {
        return new K(t, v, 2, arrive, leave, aw, lw, mode);
    }

    private static CgRichCurve curve(String pre, String post, K... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pre != null) m.put("pre", pre);
        if (post != null) m.put("post", post);
        List<Object> list = new ArrayList<>();
        for (K k : keys) list.add(k.file());
        m.put("keys", list);
        return CgRichCurve.decode(m);
    }

    // ---- the reference: FRichCurve::Eval over EvalForTwoKeys --------------------------------------------------

    private static double eval(K[] k, boolean preLinear, boolean postLinear, double time) {
        int n = k.length;
        if (time < k[0].t) return preLinear && n > 1 ? line(k[0], k[1], time) : k[0].v;
        if (time > k[n - 1].t) return postLinear && n > 1 ? line(k[n - 1], k[n - 2], time) : k[n - 1].v;
        int i = 0;
        while (i < n - 2 && time >= k[i + 1].t) i++;
        return two(k[i], k[i + 1], time);
    }

    private static double line(K a, K b, double time) {
        return (b.v - a.v) / (b.t - a.t) * (time - a.t) + a.v;
    }

    private static double two(K a, K b, double time) {
        double diff = b.t - a.t;
        if (!(diff > 0)) return a.v;
        if (a.interp == 0) return time < b.t ? a.v : b.v;
        double alpha = (time - a.t) / diff;
        if (a.interp == 1) return a.v + alpha * (b.v - a.v);
        boolean unweighted = (a.weight == 0 || a.weight == 1) && (b.weight == 0 || b.weight == 2);
        if (unweighted) {
            return bezier(a.v, a.v + a.leave * diff / 3, b.v - b.arrive * diff / 3, b.v, alpha);
        }
        double angle = Math.atan(a.leave);
        double lw = a.weight == 0 || a.weight == 1 ? Math.hypot(diff, a.leave * diff) / 3 : a.lw;
        double h1x = Math.cos(angle) * lw, h1y = Math.sin(angle) * lw + a.v;
        angle = Math.atan(b.arrive);
        double aw = b.weight == 0 || b.weight == 2 ? Math.hypot(diff, b.arrive * diff) / 3 : b.aw;
        double h2x = -Math.cos(angle) * aw + diff, h2y = -Math.sin(angle) * aw + b.v;
        double n1 = h1x / diff, n2 = h2x / diff, lo = 0, hi = 1;
        for (int it = 0; it < 80; it++) {
            double s = (lo + hi) / 2, x = 3 * n1 * s * (1 - s) * (1 - s) + 3 * n2 * s * s * (1 - s) + s * s * s;
            if (x < alpha) lo = s;
            else hi = s;
        }
        return bezier(a.v, h1y, h2y, b.v, (lo + hi) / 2);
    }

    private static double bezier(double p0, double p1, double p2, double p3, double t) {
        double u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }

    private static void agree(String what, K[] keys, boolean preLinear, boolean postLinear) {
        CgRichCurve c = curve(preLinear ? "linear" : null, postLinear ? "linear" : null, keys);
        double lo = keys[0].t - 0.5, hi = keys[keys.length - 1].t + 0.5;
        for (int i = 0; i <= 400; i++) {
            double t = lo + (hi - lo) * i / 400;
            assertEquals(what + " at " + t, eval(keys, preLinear, postLinear, t), c.eval((float) t), EPS);
        }
    }

    // ---- key forms ---------------------------------------------------------------------------------------------

    @Test
    public void constantLinearAndCubicSegmentsMix() {
        agree("mix", new K[] {constant(0, 1), linear(1, 3), cubic(2, 0, 1.5, -2), linear(3.5, 2), cubic(4, 5, 0, 0)}, false, false);
    }

    @Test
    public void storedCubicTangentsAreEvaluatedAsStored() {
        agree("hermite", new K[] {cubic(0, 0, 0, 4), cubic(0.5, 1, -3, 2), cubic(2, -1, 0.7, 0.7)}, false, false);
    }

    @Test
    public void weightedOnEachSide() {
        agree("leave", new K[] {weighted(0, 0, 0, 1.2, 0, 0.8, 2), cubic(1, 1, 0.3, 0)}, false, false);
        agree("arrive", new K[] {cubic(0, 0, 0, 1.2), weighted(1, 1, -0.6, 0, 0.7, 0, 1)}, false, false);
        agree("both", new K[] {weighted(0, 0, 0, 2, 0, 0.5, 3), weighted(2, 3, -1, 0, 1.1, 0, 3)}, false, false);
        agree("neighbours", new K[] {weighted(0, 0, 0, 1, 0, 0.3, 2), weighted(1, 2, 1, 0.5, 0.4, 0.9, 3), cubic(3, 1, 0, 0)},
                false, false);
    }

    @Test
    public void extrapolationEitherSideConstantOrLinear() {
        K[] keys = {cubic(1, 2, 1, 1), cubic(3, 6, 2, 2)};
        agree("constant", keys, false, false);
        agree("linear", keys, true, true);
        agree("pre linear", keys, true, false);
        agree("post linear", keys, false, true);
    }

    // ---- hand-worked, from the formulas in the source ----------------------------------------------------------

    @Test
    public void handWorkedVectors() {
        // Bezier(0, 1/3, 2/3, 1) is the identity: tangents 1, dt 1
        CgRichCurve ident = curve(null, null, cubic(0, 0, 1, 1), cubic(1, 1, 1, 1));
        for (float t = 0f; t <= 1f; t += 0.1f) assertEquals(t, ident.eval(t), 1e-6f);
        // P0 = 0, P1 = 0 + 2*1/3, P2 = 1 - 0, P3 = 1 at t = .5: (0 + 3*(2/3) + 3*1 + 1) / 8
        assertEquals((3 * (2.0 / 3) + 3 + 1) / 8, curve(null, null, cubic(0, 0, 0, 2), cubic(1, 1, 0, 0)).eval(0.5f), 1e-6f);
        // a constant key holds up to the next key's time, then takes its value
        CgRichCurve step = curve(null, null, constant(0, 4), constant(2, 9));
        assertEquals(4f, step.eval(1.999f), 0f);
        assertEquals(9f, step.eval(2f), 0f);
        // a weighted flat handle of one third dt is the unweighted curve (3t^2 - 2t^3 at .25)
        assertEquals(0.15625f,
                curve(null, null, weighted(0, 0, 0, 0, 0, 1.0 / 3, 3), weighted(1, 1, 0, 0, 1.0 / 3, 0, 3)).eval(0.25f), 1e-4f);
    }
}
