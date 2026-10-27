package com.crystalgraphics.object.value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgValueType;

/**
 * A system of units a value is written in, and the conversion of a value into the host's by its {@link CgQuantity}
 * ({@code core.md} § Units): a length in metres, a time in seconds, a signed permutation of the axes, an angle in
 * radians and a rotation's convention. The host's world is metres, +Y up, right-handed, radians ({@code aaa-design}
 * §2.18).
 *
 * <pre>{@code
 * CgUnits.UNREAL.toHost(List.of(0, 0, -980), CgQuantity.ACCELERATION);   // [0, -9.8, 0]: gravity, z up to y up
 * CgUnits.UNREAL.toHost(50, CgQuantity.LENGTH);                           // 0.5
 * CgUnits.UNREAL.toHost(Map.of("range", List.of(100, 200)), CgQuantity.LENGTH);   // { range: [1.0, 2.0] }
 * CgUnits.HOST.toHost(Map.of("range", List.of(0, 0.5), "unit", "turns"), CgQuantity.ANGLE);   // { range: [0, π] }
 *
 * // An asset's units: its own block over its namespace's units.yaml.
 * CgUnits kit = CgUnits.of(Map.of("length", "cm", "axes", "unreal", "angles", "degrees"), CgUnits.HOST);
 * Object radius = kit.toHost(written, property);   // a declared vec3 permutes; a vec2 or three numbers for one does not
 * }</pre>
 *
 * <ul>
 *   <li>Axes permute only a three-number vector: through {@link #toHost(Object, CgProperty)}, only for an input declared
 *       {@code vec3} or {@code position}.</li>
 *   <li>A value's {@code unit:} (an angle's or a length's) overrides these units for that value alone, and a
 *       {@code { value, unit }} comes back as its converted value. On an input declaring no quantity, the unit says
 *       which: an angle or a length. A {@code rotation}-typed input is a {@link CgQuantity#ROTATION}.</li>
 *   <li>Forms convert inside: a range's ends, a pick's, an array's values, a curve's values and slopes (by the loop its
 *       times too), {@code add}, {@code random_range} and {@code random_sign} by their inputs, a per-axis curve or a
 *       lock through the axes.
 *       A binding is the bound value's and converts nothing.</li>
 *   <li>{@link CgQuantity#NONE} and {@link CgQuantity#COLOUR} come back untouched: colour is the reader's space. A
 *       {@link CgQuantity#ROTATION} converts its angles to radians and keeps its convention ({@link #rotation()}) for
 *       its reader.</li>
 * </ul>
 *
 * @param metres   host metres per one of its lengths
 * @param seconds  host seconds per one of its times
 * @param axes     host component {@code i} is {@code sign(axes[i]) * value[|axes[i]| - 1]}
 * @param radians  radians per one of its angles
 * @param rotation its rotations' convention: {@code unreal_rotator} or {@code quaternion}
 */
public record CgUnits(double metres, double seconds, int[] axes, double radians, String rotation) {

    /**
     * Unreal's: centimetres, Z up and left-handed, degrees, rotators. Its axes reach the host as Unreal's own glTF
     * exporter maps them to a right-handed Y-up space, {@code (X, Z, Y)} ({@code GLTFCoreUtilities.cpp},
     * {@code ConvertVector}).
     */
    public static final CgUnits UNREAL = new CgUnits(0.01, 1, new int[] {1, 3, 2}, Math.PI / 180, "unreal_rotator");
    /** The host's own: converts nothing. */
    public static final CgUnits HOST = new CgUnits(1, 1, new int[] {1, 2, 3}, 1, "quaternion");

    /** {@code block}'s keys ({@code length}, {@code axes}, {@code angles}, {@code rotation}, {@code time}) over {@code base}'s. */
    public static CgUnits of(@Nullable Object block, CgUnits base) {
        if (block == null) return base;
        if (!(block instanceof Map<?, ?> m)) throw new IllegalArgumentException("units are a map, not " + block);
        double metres = m.containsKey("length") ? length(m.get("length")) : base.metres;
        int[] axes = m.containsKey("axes") ? axes(m.get("axes")) : base.axes;
        double radians = m.containsKey("angles") ? angle(m.get("angles")) : base.radians;
        String rotation = m.containsKey("rotation") ? rotation(m.get("rotation")) : base.rotation;
        double seconds = m.containsKey("time") ? time(m.get("time")) : base.seconds;
        return new CgUnits(metres, seconds, axes, radians, rotation);
    }

    private static double length(Object v) {
        if (v instanceof Map<?, ?> m && m.get("metres") instanceof Number n) return n.doubleValue();
        Double metres = LENGTHS.get(String.valueOf(v));
        if (metres == null) throw new IllegalArgumentException("no length unit " + v + ": m, cm, mm, in, ft, px or { metres }");
        return metres;
    }

    private static final Map<String, Double> LENGTHS = Map.of("m", 1.0, "cm", 0.01, "mm", 0.001, "in", 0.0254, "ft", 0.3048,
            "px", 1.0 / 16);

    private static int[] axes(Object v) {
        return switch (String.valueOf(v)) {
            case "unreal" -> new int[] {1, 3, 2};
            case "gltf", "minecraft" -> new int[] {1, 2, 3};
            default -> throw new IllegalArgumentException("axes " + v + " are not read yet: unreal, gltf or minecraft");
        };
    }

    private static double angle(Object v) {
        return switch (String.valueOf(v)) {
            case "degrees" -> Math.PI / 180;
            case "radians" -> 1;
            case "turns" -> 2 * Math.PI;
            default -> throw new IllegalArgumentException("no angle unit " + v + ": degrees, radians or turns");
        };
    }

    private static String rotation(Object v) {
        String r = String.valueOf(v);
        if (!r.equals("unreal_rotator") && !r.equals("quaternion")) {
            throw new IllegalArgumentException("rotations as " + v + " are not read yet: unreal_rotator or quaternion");
        }
        return r;
    }

    private static double time(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Map<?, ?> m) {
            if (!Boolean.FALSE.equals(m.get("seconds"))) return 1;
            if (m.get("frames") instanceof Number f) return 1 / f.doubleValue();
        }
        throw new IllegalArgumentException("time " + v + " is not { seconds: true } nor { seconds: false, frames: n }");
    }

    /** {@code value}, written for input {@code p}, in the host's units: its axes permute only for a declared vec3. */
    public Object toHost(Object value, CgProperty p) {
        // A rotation-typed input is a rotation whether or not it says so.
        CgQuantity q = p.quantity() == CgQuantity.NONE && p.type() == CgValueType.ROTATION ? CgQuantity.ROTATION : p.quantity();
        return convert(value, q, p.type() == CgValueType.VEC3 || p.type() == CgValueType.POSITION);
    }

    /** {@code value}, a {@code quantity} written in these units, in the host's; any three numbers a vector. */
    public Object toHost(Object value, CgQuantity quantity) {
        return convert(value, quantity, true);
    }

    private Object convert(Object value, CgQuantity q, boolean vector) {
        if (value instanceof Number n) return factor(q) == 1 ? value : n.doubleValue() * factor(q);
        if (value instanceof List<?> list) {
            if (vector && list.size() == 3 && permutes(q) && allNumbers(list)) return vector(list, q);
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(convert(o, q, vector));
            return out;
        }
        if (value instanceof Map<?, ?> form) return form(form, q, vector);
        return value;
    }

    private Object form(Map<?, ?> m, CgQuantity q, boolean vector) {
        if (m.get("unit") instanceof String unit) {
            Map<Object, Object> rest = new LinkedHashMap<>(m);
            rest.remove("unit");
            CgUnits in = inUnit(unit);
            // An input declaring no quantity takes the unit's: an angle's or a length's.
            if (q == CgQuantity.NONE) q = LENGTHS.containsKey(unit) ? CgQuantity.LENGTH : CgQuantity.ANGLE;
            return rest.size() == 1 && rest.containsKey("value") ? in.convert(rest.get("value"), q, vector) : in.form(rest, q, vector);
        }
        if (m.containsKey("bind")) return bound(m, q, vector);
        if (q == CgQuantity.NONE || q == CgQuantity.COLOUR) return m;
        Map<Object, Object> out = new LinkedHashMap<>(m);
        boolean loop = "loop".equals(m.get("by"));
        for (String k : List.of("range", "pick", "array", "value")) if (m.containsKey(k)) out.put(k, convert(m.get(k), q, vector));
        if (m.containsKey("curve")) out.put("curve", curve(m.get("curve"), factor(q), loop));
        if (m.get("curves") instanceof List<?> curves) {
            boolean axial = vector && curves.size() == 3 && permutes(q);
            List<Object> c = new ArrayList<>(curves.size());
            for (int i = 0; i < curves.size(); i++) {
                c.add(axial ? curve(curves.get(Math.abs(axes[i]) - 1), axisFactor(i, q), loop) : curve(curves.get(i), factor(q), loop));
            }
            out.put("curves", c);
        }
        if (m.containsKey("fn")) {
            String fn = String.valueOf(m.get("fn"));
            // Each is linear in its inputs, so converting them converts it.
            boolean linear = fn.endsWith(":add") || fn.endsWith(":random_range") || fn.endsWith(":random_sign");
            if (!linear && !(factor(q) == 1 && (!permutes(q) || Arrays.equals(axes, HOST.axes)))) {
                throw new IllegalArgumentException(fn + " does not convert units yet: add, random_range and random_sign do");
            }
            Map<Object, Object> inputs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) m.get("inputs")).entrySet()) inputs.put(e.getKey(), convert(e.getValue(), q, vector));
            out.put("inputs", inputs);
        }
        if (vector && permutes(q) && m.get("locked") instanceof String locked) out.put("locked", locked(locked));
        return out;
    }

    /** These units with a value's own {@code unit:}: an angle's or a length's. */
    private CgUnits inUnit(String unit) {
        if (LENGTHS.containsKey(unit)) return new CgUnits(LENGTHS.get(unit), seconds, axes, radians, rotation);
        return new CgUnits(metres, seconds, axes, angle(unit), rotation);
    }

    /** A curve's values and slopes times {@code f}; by the loop, its times scale by the time unit and its slopes back. */
    private Object curve(Object c, double f, boolean loop) {
        double t = loop ? seconds : 1;
        if (f == 1 && t == 1) return c;
        if (!(c instanceof Map<?, ?> m)) throw new IllegalArgumentException("a curve asset " + c + " does not convert units yet");
        Map<Object, Object> out = new LinkedHashMap<>(m);
        if (m.get("default") instanceof Number d) out.put("default", d.doubleValue() * f);
        if (m.get("samples") instanceof Map<?, ?> samples) {
            Map<Object, Object> s = new LinkedHashMap<>(samples);
            s.put("values", scaled((List<?>) samples.get("values"), f));
            s.put("range", scaled((List<?>) samples.get("range"), t));
            out.put("samples", s);
        }
        if (m.get("keys") instanceof List<?> keys) {
            List<Object> k = new ArrayList<>(keys.size());
            for (Object key : keys) {
                List<Object> one = new ArrayList<>((List<?>) key);
                one.set(0, ((Number) one.get(0)).doubleValue() * t);
                one.set(1, ((Number) one.get(1)).doubleValue() * f);
                // Past the value: the interpolation and tangent mode, then the arrive and leave slopes.
                for (int i = 2; i < one.size(); i++) if (one.get(i) instanceof Number n) one.set(i, n.doubleValue() * f / t);
                k.add(one);
            }
            out.put("keys", k);
        }
        return out;
    }

    private static List<Object> scaled(List<?> values, double f) {
        List<Object> out = new ArrayList<>(values.size());
        for (Object v : values) out.add(((Number) v).doubleValue() * f);
        return out;
    }

    /** Axis letters named in these units, named in the host's, sorted: a lock's, a field's tiled axes. */
    public String hostAxes(String locked) {
        return locked(locked);
    }

    /** A lock's axes, named in these units, named in the host's: a lock pairs two channels, so either order. */
    private String locked(String locked) {
        char[] host = new char[locked.length()];
        for (int i = 0; i < host.length; i++) {
            int from = "xyz".indexOf(locked.charAt(i));
            for (int a = 0; a < 3; a++) if (Math.abs(axes[a]) - 1 == from) host[i] = "xyz".charAt(a);
        }
        Arrays.sort(host);
        return new String(host);
    }

    private double factor(CgQuantity q) {
        return switch (q) {
            case LENGTH, POSITION, OFFSET -> metres;
            case SPEED -> metres / seconds;
            case ACCELERATION -> metres / (seconds * seconds);
            case TIME -> seconds;
            case FREQUENCY, RATE -> 1 / seconds;
            case PER_LENGTH -> 1 / metres;
            case ANGLE, ROTATION -> radians;
            case ANGULAR_SPEED -> radians / seconds;
            default -> 1;
        };
    }

    private static boolean permutes(CgQuantity q) {
        return switch (q) {
            case POSITION, OFFSET, LENGTH, SPEED, ACCELERATION, DIRECTION, SCALE, ANGULAR_SPEED -> true;
            default -> false;
        };
    }

    /** Host component {@code i}'s factor: the quantity's, its axis's sign unless a scale, and a pseudovector's sense. */
    private double axisFactor(int i, CgQuantity q) {
        // An angular velocity is a pseudovector: where the axes flip handedness its sense flips too.
        double sense = q == CgQuantity.ANGULAR_SPEED && flipsHandedness() ? -1 : 1;
        double sign = q == CgQuantity.SCALE ? 1 : Math.signum(axes[i]);
        return factor(q) * sign * sense;
    }

    /**
     * A binding converted as its input's constant would be: its result {@code scale × remap(value)} by the input's
     * quantity, folded in at load since that is linear. {@code in}, {@code default} and the bound value stay the asset's
     * numbers; a vector's per-channel parts permute through the axes, {@code out} (else {@code scale}) takes the factor
     * and signs, and {@code channels} names the value's channel each host channel reads. Only a {@code user.} parameter
     * holds the asset's numbers; what the engine binds ({@code particles.}, {@code engine.}) is in host units already.
     */
    private Map<?, ?> bound(Map<?, ?> m, CgQuantity q, boolean vector) {
        if (q == CgQuantity.NONE || q == CgQuantity.COLOUR || !String.valueOf(m.get("bind")).startsWith("user.")) return m;
        boolean axial = vector && permutes(q);
        boolean identity = !axial || Arrays.equals(axes, HOST.axes);
        if (factor(q) == 1 && identity) return m;
        if (q == CgQuantity.ROTATION) {
            throw new IllegalArgumentException("a bound " + q + " does not convert units yet: " + m.get("bind"));
        }
        Map<Object, Object> out = new LinkedHashMap<>(m);
        if (m.get("remap") instanceof Map<?, ?> remap) {
            Map<Object, Object> r = new LinkedHashMap<>(remap);
            List<Object> in = new ArrayList<>(), to = new ArrayList<>();
            for (Object end : (List<?>) remap.get("in")) in.add(axial ? permuted(end) : end);
            for (Object end : (List<?>) remap.get("out")) {
                to.add(axial ? vector(three(end), q) : convert(end, q, false));
            }
            r.put("in", in);
            r.put("out", to);
            out.put("remap", r);
        } else {
            // Unmapped, the factor rides on the scale: one number, so every channel must take the same.
            double f = axial ? axisFactor(0, q) : factor(q);
            if (axial) {
                for (int i = 1; i < 3; i++) {
                    if (axisFactor(i, q) != f) {
                        throw new IllegalArgumentException("a bound " + q + " with no remap does not convert to axes that"
                                + " flip a sign yet: " + m.get("bind"));
                    }
                }
            }
            out.put("scale", (m.get("scale") instanceof Number s ? s.doubleValue() : 1.0) * f);
        }
        if (axial && m.get("mode") instanceof List<?> modes && modes.size() == 3) out.put("mode", permuted(modes));
        if (axial && !identity) out.put("channels", List.of(Math.abs(axes[0]) - 1, Math.abs(axes[1]) - 1, Math.abs(axes[2]) - 1));
        return out;
    }

    /** {@code v}'s three channels in host order, unscaled: a number stands for all three. */
    private Object permuted(Object v) {
        List<?> list = three(v);
        List<Object> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) out.add(list.get(Math.abs(axes[i]) - 1));
        return out;
    }

    private static List<?> three(Object v) {
        if (v instanceof List<?> list) return list;
        return List.of(v, v, v);
    }

    private Object vector(List<?> v, CgQuantity q) {
        List<Object> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) out.add(((Number) v.get(Math.abs(axes[i]) - 1)).doubleValue() * axisFactor(i, q));
        return out;
    }

    /**
     * Unreal's rotator (roll, pitch, yaw in radians, about its x, y and z) as the host's quaternion {@code (x, y, z, w)}:
     * Niagara's {@code RotatorToQuat} ({@code NiagaraQuaternionUtils.ush:167-181}), then through Unreal's axes. Those
     * swap y and z, a reflection, so the axis permutes and the angle's sense flips: {@code (-qx, -qz, -qy, qw)}.
     */
    public static float[] rotatorToHost(double roll, double pitch, double yaw) {
        return rotatorToHost(roll, pitch, yaw, new float[4]);
    }

    /** {@link #rotatorToHost(double, double, double)} into {@code out}, allocating nothing: {@code out}. */
    public static float[] rotatorToHost(double roll, double pitch, double yaw, float[] out) {
        double sr = Math.sin(roll / 2), cr = Math.cos(roll / 2), sp = Math.sin(pitch / 2), cp = Math.cos(pitch / 2);
        double sy = Math.sin(yaw / 2), cy = Math.cos(yaw / 2);
        double x = cr * sp * sy - sr * cp * cy, y = -cr * sp * cy - sr * cp * sy, z = cr * cp * sy - sr * sp * cy;
        double w = cr * cp * cy + sr * sp * sy;
        out[0] = (float) -x;
        out[1] = (float) -z;
        out[2] = (float) -y;
        out[3] = (float) w;
        return out;
    }

    /** Whether its axes differ from the host's in handedness: an odd signed permutation. */
    public boolean flipsHandedness() {
        int swaps = 0;
        int[] p = new int[3];
        for (int i = 0; i < 3; i++) p[i] = Math.abs(axes[i]) - 1;
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 3; j++) if (p[i] > p[j]) swaps++;
        int negatives = 0;
        for (int a : axes) if (a < 0) negatives++;
        return ((swaps + negatives) & 1) == 1;
    }

    private static boolean allNumbers(List<?> list) {
        for (Object o : list) if (!(o instanceof Number)) return false;
        return true;
    }
}
