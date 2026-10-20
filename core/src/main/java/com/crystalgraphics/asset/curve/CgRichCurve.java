package com.crystalgraphics.asset.curve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One curve as the format writes it (Unreal's {@code FRichCurve}): keys with constant, linear or cubic segments,
 * weighted tangents, and extrapolation before and after; or evenly spaced samples. Evaluated from flat arrays,
 * allocating nothing.
 *
 * <p>Evaluation follows {@code FRichCurve::Eval} and {@code UE::Curves::EvalForTwoKeys} (Unreal,
 * {@code Engine/Private/Curves/RichCurve.cpp:1104-1253}, {@code CurveEvaluation.cpp:96-250}), written from the format's
 * § Curves: a cubic segment is the Bezier {@code v1, v1 + leave1·dt/3, v2 - arrive2·dt/3, v2}; a weighted side's handle
 * has its stored weight for length, solved for the time.</p>
 *
 * <pre>{@code
 * CgRichCurve c = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0), List.of(1, 1, "linear"))));
 * c.eval(0.25f);                                     // 0.25 for linear; 0.15625 had both keys been cubic and flat
 *
 * CgRichCurve s = CgRichCurve.decode(Map.of("samples", Map.of("range", List.of(0, 1), "values", List.of(0, 2, 1))));
 * s.eval(0.25f);                                     // 1: linear between samples, clamped outside
 * }</pre>
 *
 * <ul>
 *   <li>A key written without tangents in {@code auto} (the default) gets Unreal's auto tangent, flat at the ends;
 *       stored tangents are evaluated as stored. {@code smart_auto} is computed as {@code auto}.</li>
 *   <li>No keys gives {@code default} (0 unless written); one key holds everywhere.</li>
 *   <li>A patched key value ({@link #setValue}) recomputes the auto tangents, as an edit does.</li>
 * </ul>
 */
public final class CgRichCurve {

    /** A segment's interpolation, by its first key. */
    public static final int CONSTANT = 0, LINEAR = 1, CUBIC = 2;
    /** Tangent modes. */
    public static final int AUTO = 0, SMART_AUTO = 1, USER = 2, BREAK = 3;
    /** Weight modes: bit 1 arrive, bit 2 leave. */
    public static final int WEIGHT_NONE = 0, WEIGHT_ARRIVE = 1, WEIGHT_LEAVE = 2, WEIGHT_BOTH = 3;

    /** Extrapolation outside the keys. */
    public enum Extrapolation {
        CONSTANT, LINEAR, CYCLE, CYCLE_WITH_OFFSET, OSCILLATE, NONE;

        static Extrapolation of(Object name, String where) {
            if (name == null) return CONSTANT;
            try {
                return valueOf(name.toString().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ": no extrapolation '" + name + "'");
            }
        }
    }

    private final float[] times, values, arrive, leave, arriveWeight, leaveWeight;
    private final int[] interp, tangentMode, weightMode;
    /** Keys whose tangents were not written: recomputed as auto. */
    private final boolean[] autoTangent;
    private final float defaultValue;
    private final Extrapolation pre, post;
    /** A sampled curve: {@code values} evenly over {@code [sampleStart, sampleEnd]}; keys unused. */
    private final boolean sampled;
    private final float sampleStart, sampleEnd;

    private CgRichCurve(int n, float defaultValue, Extrapolation pre, Extrapolation post, boolean sampled,
                        float sampleStart, float sampleEnd) {
        times = new float[n];
        values = new float[n];
        arrive = new float[n];
        leave = new float[n];
        arriveWeight = new float[n];
        leaveWeight = new float[n];
        interp = new int[n];
        tangentMode = new int[n];
        weightMode = new int[n];
        autoTangent = new boolean[n];
        this.defaultValue = defaultValue;
        this.pre = pre;
        this.post = post;
        this.sampled = sampled;
        this.sampleStart = sampleStart;
        this.sampleEnd = sampleEnd;
    }

    /** A curve from its file form: {@code { keys: [...], default, pre, post }} or {@code { samples: { range, values } }}. */
    public static CgRichCurve decode(Object tree) {
        if (!(tree instanceof Map<?, ?> map)) throw new IllegalArgumentException("a curve is a map, not " + tree);
        float def = map.get("default") instanceof Number d ? d.floatValue() : 0f;
        Extrapolation pre = Extrapolation.of(map.get("pre"), "pre"), post = Extrapolation.of(map.get("post"), "post");
        Object samples = map.get("samples");
        if (samples != null) {
            Map<?, ?> s = (Map<?, ?>) samples;
            List<?> range = (List<?>) s.get("range"), vals = (List<?>) s.get("values");
            CgRichCurve c = new CgRichCurve(vals.size(), def, pre, post, true, num(range.get(0)), num(range.get(1)));
            for (int i = 0; i < vals.size(); i++) c.values[i] = num(vals.get(i));
            return c;
        }
        List<?> keys = map.get("keys") instanceof List<?> l ? l : new ArrayList<>();
        CgRichCurve c = new CgRichCurve(keys.size(), def, pre, post, false, 0f, 0f);
        for (int i = 0; i < keys.size(); i++) c.readKey(i, (List<?>) keys.get(i));
        c.autoSetTangents();
        return c;
    }

    private void readKey(int i, List<?> key) {
        times[i] = num(key.get(0));
        values[i] = num(key.get(1));
        interp[i] = CUBIC;
        tangentMode[i] = AUTO;
        autoTangent[i] = true;
        int at = 2;
        if (key.size() > at && key.get(at) instanceof String s) {
            interp[i] = s.equals("constant") ? CONSTANT : s.equals("linear") ? LINEAR : CUBIC;
            at++;
            if (interp[i] == CUBIC && key.size() > at && key.get(at) instanceof String mode) {
                tangentMode[i] = mode.equals("smart_auto") ? SMART_AUTO : mode.equals("user") ? USER : mode.equals("break") ? BREAK : AUTO;
                at++;
                if (key.size() > at + 1 && key.get(at) instanceof Number a && key.get(at + 1) instanceof Number l) {
                    arrive[i] = a.floatValue();
                    leave[i] = l.floatValue();
                    autoTangent[i] = false;
                    at += 2;
                }
            }
        }
        if (key.size() > at && key.get(at) instanceof Map<?, ?> w) {
            arriveWeight[i] = w.get("arrive_weight") instanceof Number n ? n.floatValue() : 0f;
            leaveWeight[i] = w.get("leave_weight") instanceof Number n ? n.floatValue() : 0f;
            Object mode = w.get("weight_mode");
            weightMode[i] = "arrive".equals(mode) ? WEIGHT_ARRIVE : "leave".equals(mode) ? WEIGHT_LEAVE
                    : "both".equals(mode) ? WEIGHT_BOTH : WEIGHT_NONE;
        }
    }

    private static float num(Object o) {
        if (!(o instanceof Number n)) throw new IllegalArgumentException("a curve's number, not " + o);
        return n.floatValue();
    }

    /** Unreal's {@code AutoSetTangents} at tension 0, for the keys whose tangents were not written. */
    private void autoSetTangents() {
        int n = times.length;
        for (int k = 0; k < n; k++) {
            if (!autoTangent[k] || (tangentMode[k] != AUTO && tangentMode[k] != SMART_AUTO)) continue;
            if (k == 0 || k == n - 1) {
                arrive[k] = 0f;
                leave[k] = 0f;
            } else if (interp[k] == CUBIC) {
                float dt = Math.max(1e-4f, times[k + 1] - times[k - 1]);
                float t = (values[k + 1] - values[k - 1]) / dt;
                arrive[k] = t;
                leave[k] = t;
            }
        }
    }

    /** How many keys, or samples. */
    public int size() {
        return times.length;
    }

    public float time(int key) {
        return times[key];
    }

    public float value(int key) {
        return values[key];
    }

    /** Sets key {@code key}'s value in place, recomputing auto tangents: a patch to the live curve. */
    public void setValue(int key, float value) {
        values[key] = value;
        if (!sampled) autoSetTangents();
    }

    /** The value at {@code time}. */
    public float eval(float time) {
        if (sampled) return evalSamples(time);
        int n = times.length;
        if (n == 0) return defaultValue;
        float offset = 0f;
        if (n >= 2) {
            float min = times[0], max = times[n - 1], span = max - min;
            Extrapolation e = time <= min ? pre : time >= max ? post : null;
            if (e == Extrapolation.NONE && (time < min || time > max)) return defaultValue;
            if (e != null && e != Extrapolation.LINEAR && e != Extrapolation.CONSTANT && e != Extrapolation.NONE && span > 0f) {
                float start = time;
                int cycles = 0;
                if (time > max) {
                    cycles = (int) Math.floor((max - time) / span);
                    time += span * cycles;
                } else if (time < min) {
                    cycles = (int) Math.floor((time - min) / span);
                    time -= span * cycles;
                }
                if (time == max && start < min) time = min;
                if (time == min && start > max) time = max;
                cycles = Math.abs(cycles);
                if (e == Extrapolation.CYCLE_WITH_OFFSET) {
                    offset = (start <= min ? values[0] - values[n - 1] : values[n - 1] - values[0]) * cycles;
                } else if (e == Extrapolation.OSCILLATE && cycles % 2 == 1) {
                    time = min + (max - time);
                }
            }
        }
        float v;
        if (n < 2 || time < times[0]) {
            v = pre == Extrapolation.LINEAR && n > 1 ? line(0, 1, time) : values[0];
        } else if (time <= times[n - 1]) {
            int first = 1, count = n - 2;
            while (count > 0) {
                int step = count / 2, middle = first + step;
                if (time >= times[middle]) {
                    first = middle + 1;
                    count -= step + 1;
                } else {
                    count = step;
                }
            }
            v = segment(first - 1, first, time);
        } else {
            v = post == Extrapolation.LINEAR ? line(n - 1, n - 2, time) : values[n - 1];
        }
        return v + offset;
    }

    /** The line through keys {@code a} and {@code b}, from {@code a}. */
    private float line(int a, int b, float time) {
        float dt = times[b] - times[a];
        if (Math.abs(dt) <= 1e-8f) return values[a];
        return (values[b] - values[a]) / dt * (time - times[a]) + values[a];
    }

    private float segment(int a, int b, float time) {
        float dt = times[b] - times[a];
        if (!(dt > 0f)) return values[a];
        if (interp[a] == CONSTANT) return time < times[b] ? values[a] : values[b];
        float alpha = (time - times[a]) / dt, p0 = values[a], p3 = values[b];
        if (interp[a] == LINEAR) return p0 + alpha * (p3 - p0);
        boolean leaveWeighted = (weightMode[a] & WEIGHT_LEAVE) != 0, arriveWeighted = (weightMode[b] & WEIGHT_ARRIVE) != 0;
        if (!leaveWeighted && !arriveWeighted) {
            float third = 1f / 3f;
            return bezier(p0, p0 + leave[a] * dt * third, p3 - arrive[b] * dt * third, p3, alpha);
        }
        return weighted(a, b, time, leaveWeighted, arriveWeighted);
    }

    /** A 2D Bezier whose weighted handles have their stored length, solved for the time. */
    private float weighted(int a, int b, float time, boolean leaveWeighted, boolean arriveWeighted) {
        float t1 = times[a], t2 = times[b], x = t2 - t1, third = 1f / 3f;
        double angle = Math.atan(leave[a]);
        float lw = leaveWeighted ? leaveWeight[a] : (float) Math.sqrt(x * x + (leave[a] * x) * (leave[a] * x)) * third;
        float h1x = (float) Math.cos(angle) * lw + t1, h1y = (float) Math.sin(angle) * lw + values[a];
        angle = Math.atan(arrive[b]);
        float aw = arriveWeighted ? arriveWeight[b] : (float) Math.sqrt(x * x + (arrive[b] * x) * (arrive[b] * x)) * third;
        float h2x = (float) -Math.cos(angle) * aw + t2, h2y = (float) -Math.sin(angle) * aw + values[b];
        double n1 = (h1x - t1) / x, n2 = (h2x - t1) / x, alpha = (time - t1) / x;
        // x(s) = 3 n1 s (1-s)^2 + 3 n2 s^2 (1-s) + s^3, in power form: c3 s^3 + c2 s^2 + c1 s + c0 = alpha
        double c3 = 1.0 - 3.0 * n2 + 3.0 * n1, c2 = 3.0 * n2 - 6.0 * n1, c1 = 3.0 * n1, c0 = -alpha;
        double s = solveInUnit(c3, c2, c1, c0, alpha);
        return bezier(values[a], h1y, h2y, values[b], (float) s);
    }

    /** The root of the cubic in [0, 1] (Cardano), the largest where there are several, {@code fallback} where none. */
    private static double solveInUnit(double c3, double c2, double c1, double c0, double fallback) {
        double d = c3 != 0.0 ? c3 : 1e-16;
        double a = c2 / d, b = c1 / d, c = c0 / d;
        double sq = a * a, p = (-sq / 3.0 + b) / 3.0, q = (2.0 / 27.0 * a * sq - a * b / 3.0 + c) / 2.0;
        double cube = p * p * p, disc = q * q + cube, sub = a / 3.0;
        double[] roots = {Double.NaN, Double.NaN, Double.NaN};
        int count;
        if (Math.abs(disc) <= 1e-8) {
            if (Math.abs(q) <= 1e-8) {
                roots[0] = 0;
                count = 1;
            } else {
                double u = Math.cbrt(-q);
                roots[0] = 2 * u;
                roots[1] = -u;
                count = 2;
            }
        } else if (disc < 0) {
            double phi = Math.acos(-q / Math.sqrt(-cube)) / 3.0, t = 2 * Math.sqrt(-p);
            roots[0] = t * Math.cos(phi);
            roots[1] = -t * Math.cos(phi + Math.PI / 3);
            roots[2] = -t * Math.cos(phi - Math.PI / 3);
            count = 3;
        } else {
            double sd = Math.sqrt(disc);
            roots[0] = Math.cbrt(sd - q) - Math.cbrt(sd + q);
            count = 1;
        }
        if (count == 1) return roots[0] - sub;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < count; i++) {
            double r = roots[i] - sub;
            if (r >= -1e-8 && r <= 1 + 1e-8 && (best < 0.0 || r > best)) best = r;
        }
        return best == Double.NEGATIVE_INFINITY ? 0.0 : best;
    }

    private static float bezier(float p0, float p1, float p2, float p3, float t) {
        float p01 = p0 + (p1 - p0) * t, p12 = p1 + (p2 - p1) * t, p23 = p2 + (p3 - p2) * t;
        float p012 = p01 + (p12 - p01) * t, p123 = p12 + (p23 - p12) * t;
        return p012 + (p123 - p012) * t;
    }

    private float evalSamples(float time) {
        int n = values.length;
        if (n == 0) return defaultValue;
        if (n == 1 || !(sampleEnd > sampleStart)) return values[0];
        float u = (time - sampleStart) / (sampleEnd - sampleStart) * (n - 1);
        if (u <= 0f) return values[0];
        if (u >= n - 1) return values[n - 1];
        int i = (int) u;
        float f = u - i;
        return values[i] + (values[i + 1] - values[i]) * f;
    }
}
