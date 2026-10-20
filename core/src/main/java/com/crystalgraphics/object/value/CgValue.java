package com.crystalgraphics.object.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An input's value in one of the format's forms ({@code docs/vfx/assets.md} § Values), as the file holds it: in
 * the asset's own units, before the compile converts it. What a module input, a renderer's field or a track's value
 * decodes to.
 *
 * <pre>{@code
 * CgValue.constant(0.5)                                         // 0.5
 * CgValue.range(0.2, 0.4)                                       // { range: [0.2, 0.4] }
 * CgValue.binding("user.radius").scale(0.8)                     // { bind: user.radius, scale: 0.8 }
 * CgValue.function("crystalgraphics:add", Map.of("a", a, "b", b))
 * CgValue.range(0.5, 1.0).inUnit("turns")                       // { unit: turns, range: [0.5, 1.0] }
 * }</pre>
 *
 * <ul>
 *   <li>A constant holds a number, a boolean, a string or a list of numbers, as written.</li>
 *   <li>Nested values (a range's ends, a function's inputs, a struct's fields) are themselves {@code CgValue}s
 *       where they may take a form, or constants where they may not.</li>
 *   <li>Immutable.</li>
 * </ul>
 */
public abstract class CgValue {

    private final String unit;

    CgValue(String unit) {
        this.unit = unit;
    }

    /** Its form. */
    public abstract CgValueForm form();

    /** A unit this value is written in, overriding the asset's ({@code { unit: turns, ... }}); null for none. */
    public final String unit() {
        return unit;
    }

    /** The same value written in {@code unit}. */
    public abstract CgValue inUnit(String unit);

    /** A constant: a number, a vector, a boolean, a string. */
    public static Constant constant(Object value) {
        return new Constant(null, value);
    }

    /** One roll between {@code low} and {@code high}. */
    public static Range range(Object low, Object high) {
        return new Range(null, low, high, false, null);
    }

    /** One end or the other. */
    public static Pick pick(Object a, Object b) {
        return new Pick(null, a, b);
    }

    /** A parameter read: {@code user.radius}, {@code engine.time}. */
    public static Binding binding(String parameter) {
        return new Binding(null, parameter, null, null, null, null, null, null);
    }

    /** A registered function of other values. */
    public static Function function(String id, Map<String, CgValue> inputs) {
        return new Function(null, id, inputs);
    }

    // ── The forms ─────────────────────────────────────────────────────────────────────

    /** A number, a vector, a boolean, a string or a list, as written. */
    public static final class Constant extends CgValue {
        private final Object value;

        Constant(String unit, Object value) {
            super(unit);
            this.value = Objects.requireNonNull(value);
        }

        /** The value as written. */
        public Object value() {
            return value;
        }

        @Override public CgValueForm form() { return CgValueForm.CONSTANT; }
        @Override public Constant inUnit(String unit) { return new Constant(unit, value); }
    }

    /** One roll between two: Niagara's uniform ranges, Cascade's {@code Uniform} with its locked axes. */
    public static final class Range extends CgValue {
        private final Object low, high;
        private final boolean perChannel;
        private final String locked;

        Range(String unit, Object low, Object high, boolean perChannel, String locked) {
            super(unit);
            this.low = Objects.requireNonNull(low);
            this.high = Objects.requireNonNull(high);
            this.perChannel = perChannel;
            this.locked = locked;
        }

        /** The range's low end. */
        public Object low() { return low; }
        /** The range's high end. */
        public Object high() { return high; }
        /** Whether each channel of a vector rolls apart. */
        public boolean perChannel() { return perChannel; }
        /** {@code xy}, {@code xz}, {@code yz} or {@code xyz}: one channel's roll copied into another; null for none. */
        public String locked() { return locked; }

        /** The same range rolled per channel, with {@code locked} channels copied. */
        public Range perChannel(boolean on, String locked) {
            return new Range(unit(), low, high, on, locked);
        }

        @Override public CgValueForm form() { return CgValueForm.RANGE; }
        @Override public Range inUnit(String unit) { return new Range(unit, low, high, perChannel, locked); }
    }

    /** One end or the other: Cascade's {@code extreme} table op. */
    public static final class Pick extends CgValue {
        private final Object a, b;

        Pick(String unit, Object a, Object b) {
            super(unit);
            this.a = Objects.requireNonNull(a);
            this.b = Objects.requireNonNull(b);
        }

        /** The first end. */
        public Object a() { return a; }
        /** The second end. */
        public Object b() { return b; }

        @Override public CgValueForm form() { return CgValueForm.PICK; }
        @Override public Pick inUnit(String unit) { return new Pick(unit, a, b); }
    }

    /**
     * A curve, a curve per channel, or a gradient, looked up {@code by} something ({@code age}, {@code loop}…).
     * Each curve is a curve id or an inline curve as written.
     */
    public static final class Curve extends CgValue {
        private final CgValueForm form;
        private final List<Object> curves;
        private final String by;

        Curve(String unit, CgValueForm form, List<Object> curves, String by) {
            super(unit);
            if (form != CgValueForm.CURVE && form != CgValueForm.GRADIENT) throw new IllegalArgumentException(form.name());
            this.form = form;
            this.curves = Collections.unmodifiableList(curves);
            this.by = by;
        }

        /** One curve, looked up {@code by} something. */
        public static Curve of(Object curve, String by) {
            return new Curve(null, CgValueForm.CURVE, Collections.singletonList(curve), by);
        }

        /** {@code { curves: [x, y, z] }}: one per channel. */
        public static Curve perChannel(List<Object> curves, String by) {
            return new Curve(null, CgValueForm.CURVE, curves, by);
        }

        /** A colour gradient, looked up {@code by} something. */
        public static Curve gradient(Object curve, String by) {
            return new Curve(null, CgValueForm.GRADIENT, Collections.singletonList(curve), by);
        }

        /** Its curves: one, or one per channel. */
        public List<Object> curves() { return curves; }
        /** Whether it was written as {@code curves:}, one per channel. */
        public boolean isPerChannel() { return form == CgValueForm.CURVE && curves.size() != 1; }
        /** The lookup; null for the declaration's default. */
        public String by() { return by; }

        @Override public CgValueForm form() { return form; }
        @Override public Curve inUnit(String unit) { return new Curve(unit, form, curves, by); }
    }

    /** Values looked up {@code by} something, interpolated or not, clamped or wrapped. */
    public static final class Array extends CgValue {
        private final List<Object> values;
        private final String by;
        private final boolean interpolate;
        private final String address;

        public Array(List<Object> values, String by, boolean interpolate, String address) {
            this(null, values, by, interpolate, address);
        }

        Array(String unit, List<Object> values, String by, boolean interpolate, String address) {
            super(unit);
            this.values = Collections.unmodifiableList(values);
            this.by = by;
            this.interpolate = interpolate;
            this.address = address;
        }

        /** The array's values. */
        public List<Object> values() { return values; }
        /** The lookup; null for the declaration's default. */
        public String by() { return by; }
        /** Whether it blends between neighbouring values. */
        public boolean interpolate() { return interpolate; }
        /** {@code clamp} or {@code wrap}; null for the default. */
        public String address() { return address; }

        @Override public CgValueForm form() { return CgValueForm.ARRAY; }
        @Override public Array inUnit(String unit) { return new Array(unit, values, by, interpolate, address); }
    }

    /**
     * A parameter read: {@code scale × remap(value)}. Niagara's linked inputs, Cascade's
     * parameter distributions.
     */
    public static final class Binding extends CgValue {
        private final String parameter;
        private final Object scale, defaultValue;
        private final List<Object> remapIn, remapOut;
        private final Object mode;
        private final String component;

        Binding(String unit, String parameter, Object scale, List<Object> remapIn, List<Object> remapOut, Object mode,
                Object defaultValue, String component) {
            super(unit);
            this.parameter = Objects.requireNonNull(parameter);
            this.scale = scale;
            this.remapIn = remapIn;
            this.remapOut = remapOut;
            this.mode = mode;
            this.defaultValue = defaultValue;
            this.component = component;
        }

        /** {@code user.radius}, {@code engine.time}, {@code global.wind}. */
        public String parameter() { return parameter; }
        /** The factor, itself any value; null for 1. */
        public Object scale() { return scale; }
        /** {@code remap.in} and {@code remap.out}, each two ends; null without a remap. */
        public List<Object> remapIn() { return remapIn; }
        /** The range the clamped value maps onto. */
        public List<Object> remapOut() { return remapOut; }
        /** {@code remap}, {@code direct} or {@code absolute}, or one per channel as a list; null for {@code remap}. */
        public Object mode() { return mode; }
        /** The value with nothing bound. */
        public Object defaultValue() { return defaultValue; }
        /** One mesh of a bound actor; null for none. */
        public String component() { return component; }

        /** The same binding, scaled. */
        public Binding scale(Object scale) {
            return new Binding(unit(), parameter, scale, remapIn, remapOut, mode, defaultValue, component);
        }

        /** The same binding, clamped to {@code in} and mapped onto {@code out}. */
        public Binding remap(List<Object> in, List<Object> out) {
            return new Binding(unit(), parameter, scale, in, out, mode, defaultValue, component);
        }

        /** The same binding with its remap mode. */
        public Binding mode(Object mode) {
            return new Binding(unit(), parameter, scale, remapIn, remapOut, mode, defaultValue, component);
        }

        /** The same binding with a value for when nothing is bound. */
        public Binding defaultValue(Object value) {
            return new Binding(unit(), parameter, scale, remapIn, remapOut, mode, value, component);
        }

        /** The same binding, reading one mesh of the bound actor. */
        public Binding component(String component) {
            return new Binding(unit(), parameter, scale, remapIn, remapOut, mode, defaultValue, component);
        }

        @Override public CgValueForm form() { return CgValueForm.BINDING; }
        @Override public Binding inUnit(String unit) {
            return new Binding(unit, parameter, scale, remapIn, remapOut, mode, defaultValue, component);
        }
    }

    /** A dynamic input: a registered function of other values ({@code crystalgraphics:random_range}). */
    public static final class Function extends CgValue {
        private final String id;
        private final Map<String, CgValue> inputs;

        Function(String unit, String id, Map<String, CgValue> inputs) {
            super(unit);
            this.id = Objects.requireNonNull(id);
            this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }

        /** The function's id. */
        public String id() { return id; }
        /** Its inputs by name. */
        public Map<String, CgValue> inputs() { return inputs; }

        @Override public CgValueForm form() { return CgValueForm.FUNCTION; }
        @Override public Function inUnit(String unit) { return new Function(unit, id, inputs); }
    }

    /** A data interface's source: {@code { mesh: <id> }}, {@code { texture: <id> }}, {@code { field: <id> }}. */
    public static final class Data extends CgValue {
        private final String key;
        private final Object source;

        public Data(String key, Object source) {
            this(null, key, source);
        }

        Data(String unit, String key, Object source) {
            super(unit);
            if (CgValueForm.byKey(key) != CgValueForm.DATA) throw new IllegalArgumentException("not a data key: " + key);
            this.key = key;
            this.source = Objects.requireNonNull(source);
        }

        /** {@code mesh}, {@code texture} or {@code field}. */
        public String key() { return key; }
        /** An asset id, or for a field an inline field as written. */
        public Object source() { return source; }

        @Override public CgValueForm form() { return CgValueForm.DATA; }
        @Override public Data inUnit(String unit) { return new Data(unit, key, source); }
    }

    /** A struct input's members by name, each a value: {@code { outward: 50, radial: { range: [0, 10] } }}. */
    public static final class Struct extends CgValue {
        private final Map<String, CgValue> fields;

        public Struct(Map<String, CgValue> fields) {
            this(null, fields);
        }

        Struct(String unit, Map<String, CgValue> fields) {
            super(unit);
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        /** Its members by name. */
        public Map<String, CgValue> fields() { return fields; }

        @Override public CgValueForm form() { return CgValueForm.STRUCT; }
        @Override public Struct inUnit(String unit) { return new Struct(unit, fields); }
    }
}
