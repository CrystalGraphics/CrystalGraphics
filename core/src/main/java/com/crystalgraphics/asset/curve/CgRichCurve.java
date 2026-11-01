package com.crystalgraphics.asset.curve;

import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * // an easing on the segment its key starts, an argument after it where the easing takes one
 * CgRichCurve e = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0, "back_out", 1.5), List.of(1, 10))));
 * CgRichCurve stairs = CgRichCurve.decode(Map.of("keys", List.of(List.of(0, 0, "steps", 4), List.of(1, 8))));
 * }</pre>
 *
 * <ul>
 *   <li>The easings are GeckoLib's ({@link #ease}), {@code expo} Unreal's; {@code catmull_rom} takes the segment's
 *       start twice and the key after its end, as GeckoLib does. An interpolation the format does not name fails at
 *       decode.</li>
 *   <li>A key written without tangents in {@code auto} (the default) gets Unreal's auto tangent, flat at the ends;
 *       stored tangents are evaluated as stored. {@code smart_auto} is {@code FRichCurve}'s: as stored where written
 *       (its {@code AutoSetTangents} never recomputes one), else as {@code auto}, as its curve editor treats it.
 *       Sequencer's smart tangent ({@code CalcSmartTangent}) is a channel's, not this curve's.</li>
 *   <li>No keys gives {@code default} (0 unless written); one key holds everywhere.</li>
 *   <li>A patched key value ({@link #setValue}) recomputes the auto tangents, as an edit does.</li>
 * </ul>
 */
public final class CgRichCurve {

    /** A segment's interpolation, by its first key. */
    public static final int CONSTANT = 0, LINEAR = 1, CUBIC = 2;
    /**
     * The easings, each {@code lerp(v1, v2, f(u))} over its segment, as GeckoLib 4.8's {@code EasingType}: a family's
     * {@code _in}, {@code _out} and {@code _in_out} at {@code EASED + family * 3 + mode}. A key's argument, where given,
     * is {@code back}'s, {@code elastic}'s and {@code bounce}'s, and {@code steps}' count.
     */
    public static final int EASED = 3, STEPS = EASED + 33, CATMULL_ROM = STEPS + 1;
    private static final String[] FAMILIES = {"sin", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce",
            "expo_unclamped"};
    private static final String[] MODES = {"_in", "_out", "_in_out"};
    private static final int BACK = 7, ELASTIC = 8, BOUNCE = 9, EXPO_UNCLAMPED = 10;
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
    /** Per key, its easing's argument; NaN where it gives none. */
    private final float[] easeArg;
    private final int[] interp, tangentMode, weightMode;
    /** Keys whose tangents were not written: recomputed as auto. */
    private final boolean[] autoTangent;
    private final float defaultValue;
    private final Extrapolation pre, post;
    /** A sampled curve: {@code values} evenly over {@code [sampleStart, sampleEnd]}; keys unused. */
    private final boolean sampled;
    private final float sampleStart, sampleEnd;
    /** Per key, the weighted segment it starts as evenly spaced samples ({@link #bakeWeighted}); null where solved. */
    private float[][] baked;
    private float bakeTolerance;

    private CgRichCurve(int n, float defaultValue, Extrapolation pre, Extrapolation post, boolean sampled,
                        float sampleStart, float sampleEnd) {
        times = new float[n];
        values = new float[n];
        arrive = new float[n];
        leave = new float[n];
        arriveWeight = new float[n];
        leaveWeight = new float[n];
        easeArg = new float[n];
        Arrays.fill(easeArg, Float.NaN);
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

    /**
     * A curve from its file form: {@code { keys: [...], default, pre, post, interp }} or
     * {@code { samples: { range, values } }}. {@code interp} is the interpolation of every key that names none.
     */
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
        int all = map.get("interp") == null ? CUBIC : interp(String.valueOf(map.get("interp")));
        for (int i = 0; i < keys.size(); i++) c.readKey(i, (List<?>) keys.get(i), all);
        c.autoSetTangents();
        return c;
    }

    /**
     * The interpolation named {@code s}: {@code constant}, {@code linear}, {@code cubic}, an easing
     * ({@code sin_in} ... {@code bounce_in_out}, {@code expo_unclamped_in} ...), {@code steps} or {@code catmull_rom}.
     *
     * @throws IllegalArgumentException for any other name
     */
    public static int interp(String s) {
        switch (s) {
            case "constant": return CONSTANT;
            case "linear": return LINEAR;
            case "cubic": return CUBIC;
            case "steps": return STEPS;
            case "catmull_rom": return CATMULL_ROM;
            default:
                int mode = s.endsWith(MODES[2]) ? 2 : s.endsWith(MODES[1]) ? 1 : s.endsWith(MODES[0]) ? 0 : -1;
                int family = mode < 0 ? -1 : Arrays.asList(FAMILIES).indexOf(s.substring(0, s.length() - MODES[mode].length()));
                if (family >= 0) return EASED + family * 3 + mode;
                throw new IllegalArgumentException("no interpolation '" + s + "'");
        }
    }

    /** Every interpolation's name, as {@link #interp(String)} reads them: what a declaration's enum lists. */
    public static String[] interpNames() {
        List<String> names = new ArrayList<>(Arrays.asList("constant", "linear", "cubic"));
        for (String family : FAMILIES) for (String mode : MODES) names.add(family + mode);
        names.add("steps");
        names.add("catmull_rom");
        return names.toArray(new String[0]);
    }

    private void readKey(int i, List<?> key, int all) {
        times[i] = num(key.get(0));
        values[i] = num(key.get(1));
        interp[i] = all;
        tangentMode[i] = AUTO;
        autoTangent[i] = true;
        int at = 2;
        if (key.size() > at && key.get(at) instanceof String s) {
            interp[i] = interp(s);
            at++;
            if (interp[i] >= EASED && key.size() > at && key.get(at) instanceof Number arg) {
                easeArg[i] = arg.floatValue();
                if (interp[i] == STEPS && !(easeArg[i] >= 2f)) throw new IllegalArgumentException("steps of " + easeArg[i] + ": at least 2");
                at++;
            }
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

    /** The interpolation of the segment key {@code key} starts: {@link #CONSTANT}, {@link #LINEAR} or {@link #CUBIC}. */
    public int interp(int key) {
        return interp[key];
    }

    /** Key {@code key}'s arriving and leaving tangents, value a time, as evaluated (auto ones as computed). */
    public float arrive(int key) {
        return arrive[key];
    }

    public float leave(int key) {
        return leave[key];
    }

    /** Whether key {@code key} weights either tangent: a 2D Bezier, which a Hermite does not reproduce. */
    public boolean weighted(int key) {
        return weightMode[key] != WEIGHT_NONE;
    }

    /** Sets key {@code key}'s value in place, recomputing auto tangents: a patch to the live curve. */
    public void setValue(int key, float value) {
        values[key] = value;
        if (!sampled) autoSetTangents();
        if (baked != null) bakeWeighted(bakeTolerance);
    }

    /** Whether any segment is weighted: evaluating it solves a cubic unless {@link #bakeWeighted baked}. */
    public boolean hasWeighted() {
        for (int k = 0; k + 1 < times.length; k++) {
            if (interp[k] == CUBIC && ((weightMode[k] & WEIGHT_LEAVE) != 0 || (weightMode[k + 1] & WEIGHT_ARRIVE) != 0)) return true;
        }
        return false;
    }

    /**
     * Replaces each weighted segment's solve by evenly spaced samples, linear between, as many as keep every midpoint
     * within {@code tolerance} of the solved curve (relative to the segment's larger end, at least 1). The solve stays
     * out of the frame: evaluating a baked segment is a table lookup.
     *
     * <pre>{@code
     * CgRichCurve c = CgRichCurve.decode(tree);
     * if (c.hasWeighted()) c.bakeWeighted(1e-4f);   // at compile
     * }</pre>
     */
    public void bakeWeighted(float tolerance) {
        bakeTolerance = tolerance;
        float[][] out = new float[times.length][];
        for (int a = 0; a + 1 < times.length; a++) {
            int b = a + 1;
            boolean lw = (weightMode[a] & WEIGHT_LEAVE) != 0, aw = (weightMode[b] & WEIGHT_ARRIVE) != 0;
            float dt = times[b] - times[a];
            if (interp[a] != CUBIC || !(lw || aw) || !(dt > 0f)) continue;
            float limit = tolerance * Math.max(1f, Math.max(Math.abs(values[a]), Math.abs(values[b])));
            float[] table = null;
            for (int n = 8; n <= 4096; n *= 2) {
                table = new float[n + 1];
                for (int i = 0; i <= n; i++) table[i] = weighted(a, b, times[a] + dt * i / n, lw, aw);
                float worst = 0f;
                for (int i = 0; i < n; i++) {
                    float mid = weighted(a, b, times[a] + dt * (i + 0.5f) / n, lw, aw);
                    worst = Math.max(worst, Math.abs(mid - (table[i] + table[i + 1]) * 0.5f));
                }
                if (worst <= limit) break;
            }
            out[a] = table;
        }
        baked = out;
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
        if (interp[a] >= EASED) {
            // GeckoLib reaches the segment's end value exactly at its end (EasingType.apply), steps included
            if (time >= times[b]) return p3;
            if (interp[a] == CATMULL_ROM) {
                // GeckoLib's points (BakedAnimationsAdapter.addSplineArgs): the start twice, then the next key's value
                float after = b + 1 < times.length ? values[b + 1] : p3;
                return (float) catmullRom(alpha, p0, p0, p3, after);
            }
            return (float) (p0 + (p3 - p0) * ease(interp[a], easeArg[a], alpha));
        }
        boolean leaveWeighted = (weightMode[a] & WEIGHT_LEAVE) != 0, arriveWeighted = (weightMode[b] & WEIGHT_ARRIVE) != 0;
        if (!leaveWeighted && !arriveWeighted) {
            float third = 1f / 3f;
            return bezier(p0, p0 + leave[a] * dt * third, p3 - arrive[b] * dt * third, p3, alpha);
        }
        float[] table = baked != null ? baked[a] : null;
        if (table != null) {
            float u = Math.min(1f, Math.max(0f, alpha)) * (table.length - 1);
            int i = Math.min((int) u, table.length - 2);
            return table[i] + (table[i + 1] - table[i]) * (u - i);
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

    /** Key {@code key}'s easing argument, NaN where it gives none. */
    public float easeArg(int key) {
        return easeArg[key];
    }

    /**
     * An easing's share of its segment at {@code u}, 0 to 1: {@code interp} one of {@link #EASED}'s or {@link #STEPS},
     * {@code arg} its argument or NaN. GeckoLib 4.8's {@code EasingType} (MIT), but {@code expo} is Unreal's, 0 at 0: {@code in} is the family's function,
     * {@code out} {@code 1 - f(1 - u)}, {@code in_out} {@code f(2u) / 2} below a half and {@code 1 - f(2(1 - u)) / 2}
     * above. Catmull-Rom is the curve's alone, from its neighbours.
     */
    public static double ease(int interp, float arg, double u) {
        if (interp == STEPS) return steps(Float.isNaN(arg) ? 2 : (int) arg, u);
        int family = (interp - EASED) / 3, mode = (interp - EASED) % 3;
        if (mode == 0) return base(family, arg, u);
        if (mode == 1) return 1 - base(family, arg, 1 - u);
        return u < 0.5 ? base(family, arg, u * 2) / 2 : 1 - base(family, arg, (1 - u) * 2) / 2;
    }

    /** A family's {@code in} function (EasingType.java:196-347). */
    private static double base(int family, float arg, double n) {
        switch (family) {
            case 0: return 1 - Math.cos(n * Math.PI / 2f);
            case 1: return n * n;
            case 2: return n * n * n;
            case 3: return n * n * n * n;
            case 4: return n * n * n * n * n;
            case 5: return n == 0 ? 0 : Math.pow(2, 10 * (n - 1));      // Unreal's InterpExpoIn (UnrealMathUtility.h:1350)
            case EXPO_UNCLAMPED: return Math.pow(2, 10 * (n - 1));       // GeckoLib's: 2^-10 at 0
            case 6: return 1 - Math.sqrt(1 - n * n);
            case BACK: {
                double s = Float.isNaN(arg) ? 1.70158 : arg * 1.70158;
                return n * n * ((s + 1) * n - s);
            }
            case ELASTIC: {
                double k = Float.isNaN(arg) ? 1 : arg;
                return 1 - Math.pow(Math.cos(n * Math.PI / 2f), 3) * Math.cos(n * k * Math.PI);
            }
            case BOUNCE: {
                double k = Float.isNaN(arg) ? 0.5 : arg;
                double one = 121f / 16f * n * n;
                double two = 121f / 4f * k * Math.pow(n - 6f / 11f, 2) + 1 - k;
                double three = 121 * k * k * Math.pow(n - 9f / 11f, 2) + 1 - k * k;
                double four = 484 * k * k * k * Math.pow(n - 10.5f / 11f, 2) + 1 - k * k * k;
                return Math.min(Math.min(one, two), Math.min(three, four));
            }
            default: throw new IllegalStateException("no easing family " + family);
        }
    }

    /**
     * GeckoLib's step (EasingType.java:347-380), its binary search kept: capped at {@code (count - 1) / count} past
     * that share, and one step lower at exactly it, where the search never tests the last step.
     */
    private static double steps(int count, double u) {
        if (u < 0) return 0;
        double length = 1.0 / count, last = (count - 1) * length;
        if (u > last) return last;
        int left = 0, right = count - 1;
        while (right - left != 1) {
            int test = left + (right - left) / 2;
            if (u >= test * length) left = test;
            else right = test;
        }
        return left * length;
    }

    /** GeckoLib's {@code CatmullRomEasing.getPointOnSpline}. */
    private static double catmullRom(double u, double p0, double p1, double p2, double p3) {
        return 0.5 * (2 * p1 + (p2 - p0) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (3 * p1 - p0 - 3 * p2 + p3) * u * u * u);
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
