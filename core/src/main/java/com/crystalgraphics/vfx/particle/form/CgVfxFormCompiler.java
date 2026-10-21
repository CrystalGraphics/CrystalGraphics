package com.crystalgraphics.vfx.particle.form;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.curve.CgCurveDefinition;
import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * Compiles a module input's value, in any of {@code core.md} § Values' forms, into a {@link CgVfxFormInput}: its
 * form class, its words or curve-block slot, and its Java evaluation. One compiler a definition, holding its curve
 * block.
 *
 * <pre>{@code
 * CgVfxFormCompiler compiler = new CgVfxFormCompiler(new CgVfxCurveBlock(), curveId -> curves.get(curveId));
 * CgVfxFormInput drag = compiler.compile("drag", dragProperty, Map.of("curve", fade, "by", "age"));   // element "drag"
 * String key = CgVfxFormCompiler.shapeKey("drag", List.of(drag));      // "drag(drag:CURVE_BLOCK)"
 * }</pre>
 *
 * <ul>
 *   <li>A form the declaration does not accept throws, naming the input and the forms it takes.</li>
 *   <li>The roll key is {@link #key}{@code (element, input)}: the element's id in its stack, never its place, so the
 *       same on every side and moved by no reordering.</li>
 *   <li>A value with a {@code unit:} and a {@code struct} form are not compiled here: units convert before, a struct's
 *       fields compile one by one.</li>
 * </ul>
 */
public final class CgVfxFormCompiler {

    private final CgVfxCurveBlock block;
    private final Function<String, CgCurveDefinition> curves;

    /** A compiler baking curves into {@code block}, resolving a curve by id through {@code curves}. */
    public CgVfxFormCompiler(CgVfxCurveBlock block, Function<String, CgCurveDefinition> curves) {
        this.block = block;
        this.curves = curves;
    }

    public CgVfxCurveBlock block() {
        return block;
    }

    /**
     * The roll key of {@code element}'s {@code input} ({@code core.md} § Values): by the element's id and the input's
     * name, never by place, the same on every side.
     */
    public static int key(String element, String input) {
        return (element + "/" + input).hashCode();
    }

    /** {@code value}, written for {@code input} of the element whose id is {@code element}. */
    public CgVfxFormInput compile(String element, CgProperty input, Object value) {
        CgValueForm form = CgValueForm.of(value);
        if (!input.accepts(form)) {
            throw new IllegalArgumentException(element + "'s " + input.name() + " is written as a " + form.specName()
                    + ", and takes " + input.forms());
        }
        return compile(element, input.name(), components(input.type()), input.meta().rolls(), form, value);
    }

    private CgVfxFormInput compile(String moduleId, String name, int n, @Nullable CgPropertyMeta.Rolls rolls,
                                   CgValueForm form, Object value) {
        int key = key(moduleId, name);
        Map<?, ?> m = value instanceof Map<?, ?> map ? map : Map.of();
        switch (form) {
            case CONSTANT:
                return new CgVfxFormInput.Constant(name, numbers(value, n));
            case RANGE: {
                List<?> ends = (List<?>) m.get("range");
                return new CgVfxFormInput.Range(name, key, numbers(ends.get(0), n), numbers(ends.get(1), n),
                        Boolean.TRUE.equals(m.get("per_channel")), (String) m.get("locked"), rolls);
            }
            case PICK: {
                List<?> ends = (List<?>) m.get("pick");
                return new CgVfxFormInput.Pick(name, key, numbers(ends.get(0), n), numbers(ends.get(1), n), rolls);
            }
            case CURVE: case GRADIENT: {
                CgRichCurve[] channels = channels(m, n);
                int slot = block.add(channels);
                return new CgVfxFormInput.Curve(name, form, key, block, slot, channels.length,
                        CgVfxFormInput.Lookup.of(m.get("by")));
            }
            case ARRAY: {
                List<?> items = (List<?>) m.get("array");
                float[][] values = new float[items.size()][];
                for (int i = 0; i < values.length; i++) values[i] = numbers(items.get(i), n);
                return new CgVfxFormInput.Array(name, key, values, CgVfxFormInput.Lookup.of(m.containsKey("by") ? m.get("by") : "random"),
                        !Boolean.FALSE.equals(m.get("interpolate")), m.get("address") == null ? "clamp" : String.valueOf(m.get("address")));
            }
            case BINDING:
                return binding(name, m, n);
            case FUNCTION: {
                String fn = String.valueOf(m.get("fn"));
                Map<?, ?> args = (Map<?, ?>) m.get("inputs");
                boolean add = fn.endsWith(":add");
                Object a = args.get(add ? "a" : "min"), b = args.get(add ? "b" : "max");
                if (!fn.endsWith(":add") && !fn.endsWith(":random_range")) {
                    throw new IllegalArgumentException(moduleId + "'s " + name + ": no function " + fn);
                }
                return new CgVfxFormInput.Function(name, key, fn,
                        compile(moduleId, name + "." + (add ? "a" : "min"), n, rolls, CgValueForm.of(a), a),
                        compile(moduleId, name + "." + (add ? "b" : "max"), n, rolls, CgValueForm.of(b), b), rolls);
            }
            case DATA: {
                for (String k : CgValueForm.DATA.keys()) {
                    if (m.containsKey(k)) return new CgVfxFormInput.Data(name, k, String.valueOf(m.get(k)));
                }
                throw new IllegalArgumentException(moduleId + "'s " + name + ": no data key in " + value);
            }
            default:
                throw new IllegalArgumentException(moduleId + "'s " + name + ": the " + form.specName() + " form is not compiled here");
        }
    }

    private CgRichCurve[] channels(Map<?, ?> m, int n) {
        Object c = m.containsKey("curves") ? m.get("curves") : m.containsKey("curve") ? m.get("curve") : m.get("gradient");
        if (c instanceof List<?> list) {
            CgRichCurve[] out = new CgRichCurve[list.size()];
            for (int i = 0; i < out.length; i++) out[i] = curve(list.get(i));
            return out;
        }
        if (c instanceof String id) {
            CgCurveDefinition d = curves.apply(id);
            if (d == null) throw new IllegalArgumentException("no curve " + id);
            CgRichCurve[] out = new CgRichCurve[d.channels()];
            for (int i = 0; i < out.length; i++) out[i] = d.curve(i);
            return out;
        }
        CgRichCurve one = curve(c);
        CgRichCurve[] out = new CgRichCurve[Math.min(4, n)];
        Arrays.fill(out, one);
        return out;
    }

    private CgRichCurve curve(Object c) {
        if (c instanceof String id) {
            CgCurveDefinition d = curves.apply(id);
            if (d == null) throw new IllegalArgumentException("no curve " + id);
            return d.curve(0);
        }
        return CgRichCurve.decode(c);
    }

    private static CgVfxFormInput binding(String name, Map<?, ?> m, int n) {
        String path = String.valueOf(m.get("bind"));
        float scale = m.get("scale") instanceof Number s ? s.floatValue() : 1f;
        float[] fallback = m.containsKey("default") ? numbers(m.get("default"), n) : new float[n];
        float[] inLo = null, inHi = null, outLo = null, outHi = null;
        if (m.get("remap") instanceof Map<?, ?> r) {
            List<?> in = (List<?>) r.get("in"), out = (List<?>) r.get("out");
            inLo = numbers(in.get(0), n);
            inHi = numbers(in.get(1), n);
            outLo = numbers(out.get(0), n);
            outHi = numbers(out.get(1), n);
        }
        String[] modes;
        Object mode = m.get("mode");
        if (mode instanceof List<?> list) {
            modes = new String[list.size()];
            for (int i = 0; i < modes.length; i++) modes[i] = String.valueOf(list.get(i));
        } else {
            modes = new String[]{mode == null ? "remap" : String.valueOf(mode)};
        }
        return new CgVfxFormInput.Binding(name, path, scale, inLo, inHi, outLo, outHi, modes, fallback);
    }

    /** {@code value} as {@code n} numbers: a number repeated, a list, a boolean as 0 or 1. */
    private static float[] numbers(Object value, int n) {
        float[] out = new float[n];
        if (value instanceof Number x) {
            Arrays.fill(out, x.floatValue());
        } else if (value instanceof Boolean b) {
            Arrays.fill(out, b ? 1f : 0f);
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < n; i++) out[i] = ((Number) list.get(Math.min(i, list.size() - 1))).floatValue();
        } else {
            throw new IllegalArgumentException("a number or numbers, not " + value);
        }
        return out;
    }

    private static int components(CgValueType type) {
        switch (type.kind()) {
            case VEC2: return 2;
            case VEC3: case POSITION: return 3;
            case VEC4: case COLOUR: case ROTATION: return 4;
            default: return 1;
        }
    }

    /** A pool's shape key for one module: its kind, then each input's name and form class, in order. */
    public static String shapeKey(String kind, List<CgVfxFormInput> inputs) {
        StringBuilder b = new StringBuilder(kind).append('(');
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) b.append(',');
            b.append(inputs.get(i).name()).append(':').append(inputs.get(i).formClass());
        }
        return b.append(')').toString();
    }
}
