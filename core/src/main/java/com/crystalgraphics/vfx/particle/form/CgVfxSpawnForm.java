package com.crystalgraphics.vfx.particle.form;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.asset.kind.CgModuleTypes;
import com.crystalgraphics.object.CgProperty;

/**
 * One spawn value an emitter gives in a form (a pick, a curve by random, id or the loop's seconds) rather than a plain
 * range: which value, its spec input, the value as its file wrote it, and that value compiled. The Step kernel's launch
 * builds it per spawn; the spawn row's word holds its first end.
 *
 * <pre>{@code
 * CgVfxSpawnForm size = CgVfxSpawnForm.of(CgVfxSpawnInput.SPRITE_SIZE, "sprite_size", CgVfxSpawnForm.skewed(0.1f, 0.5f, 2f));
 * CgVfxSpawnForm front = CgVfxSpawnForm.of(CgVfxSpawnInput.RADIUS, "sphere_radius", CgVfxSpawnForm.swept(9f, 33.5f, 1.6f));
 * size.scaled(0.5f);   // the same form at half the size
 * }</pre>
 *
 * <ul>
 *   <li>A plain range is no spawn form: the spawn row's two ends are its form.</li>
 *   <li>A curve named by id does not scale: {@link #scaled} throws for it.</li>
 * </ul>
 *
 * @param target   the spawn value it gives
 * @param input    its spec input's name: {@code sphere_radius}, {@code cylinder_radius} or {@code ring_radius} for a radius
 * @param value    the value as written
 * @param compiled its compiled form, the kernel's and the Java reference's
 */
public record CgVfxSpawnForm(CgVfxSpawnInput target, String input, Object value, CgVfxFormInput compiled) {

    private static final String NS = "crystalgraphics:";
    /** Samples a skewed size's curve takes. */
    private static final int SKEW_SAMPLES = 17;

    /** {@code value} for {@code input} of {@code target}'s module, compiled against its spec declaration. */
    public static CgVfxSpawnForm of(CgVfxSpawnInput target, String input, Object value) {
        String module = NS + target.module();
        CgProperty property = null;
        for (CgProperty p : CgModuleTypes.builtIn().get(module).inputs().properties()) if (p.name().equals(input)) property = p;
        if (property == null) throw new IllegalArgumentException(module + " has no input " + input);
        CgVfxFormInput compiled = new CgVfxFormCompiler(new CgVfxCurveBlock(), id -> null).compile(module, property, value);
        return new CgVfxSpawnForm(target, input, value, compiled);
    }

    /** A size {@code min + (max - min) x^skew} by a random draw: most small and a few large (aaa-design 2.60). */
    public static Object skewed(float min, float max, float skew) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < SKEW_SAMPLES; i++) {
            double x = (double) i / (SKEW_SAMPLES - 1);
            values.add(min + (max - (double) min) * Math.pow(x, skew));
        }
        Map<String, Object> c = Map.of("samples", Map.of("range", List.of(0.0, 1.0), "values", values));
        return Map.of("curves", List.of(c, c), "by", "random");
    }

    /** A radius running out at {@code speed} blocks a second and easing to a stop at the loop's end: its quadratic, exactly. */
    public static Object swept(float radius, float speed, float duration) {
        List<Object> keys = List.of(
                List.of(0.0, (double) radius, "cubic", "break", 0.0, (double) speed),
                List.of((double) duration, radius + (double) speed * duration / 2.0, "cubic", "break", 0.0, 0.0));
        return Map.of("curve", Map.of("keys", keys), "by", "loop");
    }

    /** This form with every value times {@code s}, if its target scales; itself if not. */
    public CgVfxSpawnForm scaled(float s) {
        if (!target.scales() || s == 1f) return this;
        return of(target, input, scaledValue(value, s));
    }

    /** Its first channel's first end: a pick's first, a curve's value at its domain's start. */
    public float firstEnd() {
        if (compiled instanceof CgVfxFormInput.Range r) return r.min()[0];
        if (compiled instanceof CgVfxFormInput.Pick p) return p.a()[0];
        if (compiled instanceof CgVfxFormInput.Curve c) return c.block().sample(c.slot(), c.block().start(c.slot()), 0);
        throw new IllegalStateException(target + " in the " + compiled.form().specName() + " form has no first end");
    }

    /** The least and the most its first channel takes, as {@code {low, high}}. */
    public float[] bounds() {
        if (compiled instanceof CgVfxFormInput.Range r) return order(r.min()[0], r.max()[0]);
        if (compiled instanceof CgVfxFormInput.Pick p) return order(p.a()[0], p.b()[0]);
        if (compiled instanceof CgVfxFormInput.Curve c) {
            float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
            float[] texels = c.block().texels();
            for (int i = 0, at = c.slot() * CgVfxCurveBlock.TEXELS * 4; i < CgVfxCurveBlock.TEXELS; i++) {
                lo = Math.min(lo, texels[at + 4 * i]);
                hi = Math.max(hi, texels[at + 4 * i]);
            }
            return new float[]{lo, hi};
        }
        throw new IllegalStateException(target + " in the " + compiled.form().specName() + " form has no bounds");
    }

    private static float[] order(float a, float b) {
        return new float[]{Math.min(a, b), Math.max(a, b)};
    }

    /** A value form's numbers times {@code s}: ends, picks, curve values and slopes; times, domains and weights kept. */
    private static Object scaledValue(Object v, float s) {
        if (v instanceof Number n) return n.doubleValue() * s;
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(scaledValue(o, s));
            return out;
        }
        if (!(v instanceof Map<?, ?> m)) return v;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object x = e.getValue();
            out.put(k, switch (k) {
                case "range", "pick" -> scaledValue(x, s);
                case "curve" -> scaledCurve(x, s);
                case "curves" -> {
                    List<Object> curves = new ArrayList<>();
                    for (Object c : (List<?>) x) curves.add(scaledCurve(c, s));
                    yield curves;
                }
                default -> x;
            });
        }
        return out;
    }

    private static Object scaledCurve(Object c, float s) {
        if (!(c instanceof Map<?, ?> m)) throw new IllegalArgumentException("a curve named by id does not scale: " + c);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        if (m.get("samples") instanceof Map<?, ?> samples) {
            Map<String, Object> scaled = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : samples.entrySet()) scaled.put(String.valueOf(e.getKey()), e.getValue());
            scaled.put("values", scaledValue(samples.get("values"), s));
            out.put("samples", scaled);
        }
        if (m.get("keys") instanceof List<?> keys) {
            List<Object> scaled = new ArrayList<>();
            for (Object key : keys) {
                List<Object> k = new ArrayList<>((List<?>) key);
                // A key's time first, then its value, then any slopes after its mode.
                for (int i = 1; i < k.size(); i++) if (k.get(i) instanceof Number n) k.set(i, n.doubleValue() * s);
                scaled.add(k);
            }
            out.put("keys", scaled);
        }
        return out;
    }
}
