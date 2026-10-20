package com.crystalgraphics.object.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A system of units a value is written in, and the conversion of a value into the host's by its {@link CgQuantity}
 * ({@code core.md} § Units): a length in metres, a time in seconds, a signed permutation of the axes, and angles in
 * degrees or radians. The host's world is metres, +Y up, right-handed, radians ({@code aaa-design} §2.18).
 *
 * <pre>{@code
 * CgUnits.UNREAL.toHost(List.of(0, 0, -980), CgQuantity.ACCELERATION);   // [0, -9.8, 0]: gravity, z up to y up
 * CgUnits.UNREAL.toHost(50, CgQuantity.LENGTH);                           // 0.5
 * CgUnits.UNREAL.toHost(Map.of("range", List.of(100, 200)), CgQuantity.LENGTH);   // { range: [1.0, 2.0] }
 * }</pre>
 *
 * <ul>
 *   <li>Axes permute only three-number vectors; a {@code vec2} of a length (a sprite's size) scales alone.</li>
 *   <li>{@link CgQuantity#NONE}, {@link CgQuantity#COLOUR} and {@link CgQuantity#ROTATION} come back untouched: colour
 *       is the reader's space, a rotation its convention's, neither a scale.</li>
 *   <li>A value of another shape (an enum, a bool, a curve's form) comes back untouched.</li>
 * </ul>
 *
 * @param metres  host metres per one of its lengths
 * @param seconds host seconds per one of its times
 * @param axes    host component {@code i} is {@code sign(axes[i]) * value[|axes[i]| - 1]}
 * @param degrees whether its angles are degrees
 */
public record CgUnits(double metres, double seconds, int[] axes, boolean degrees) {

    /**
     * Unreal's: centimetres, Z up and left-handed, degrees. Its axes reach the host as Unreal's own glTF exporter maps
     * them to a right-handed Y-up space, {@code (X, Z, Y)} ({@code GLTFCoreUtilities.cpp}, {@code ConvertVector}).
     */
    public static final CgUnits UNREAL = new CgUnits(0.01, 1, new int[] {1, 3, 2}, true);
    /** The host's own: converts nothing. */
    public static final CgUnits HOST = new CgUnits(1, 1, new int[] {1, 2, 3}, false);

    /** {@code value}, a {@code quantity} written in these units, in the host's. */
    public Object toHost(Object value, CgQuantity quantity) {
        if (value instanceof Number n) return factor(quantity) == 1 ? value : scalar(n.doubleValue(), quantity);
        if (value instanceof List<?> list) {
            if (list.size() == 3 && permutes(quantity) && allNumbers(list)) return vector(list, quantity);
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(toHost(o, quantity));
            return out;
        }
        if (value instanceof Map<?, ?> form && form.get("range") instanceof List<?> range) {
            Map<Object, Object> out = new LinkedHashMap<>(form);
            out.put("range", toHost(range, quantity));
            return out;
        }
        return value;
    }

    private double factor(CgQuantity q) {
        double angle = degrees ? Math.PI / 180 : 1;
        return switch (q) {
            case LENGTH, POSITION, OFFSET -> metres;
            case SPEED -> metres / seconds;
            case ACCELERATION -> metres / (seconds * seconds);
            case TIME -> seconds;
            case FREQUENCY, RATE -> 1 / seconds;
            case PER_LENGTH -> 1 / metres;
            case ANGLE -> angle;
            case ANGULAR_SPEED -> angle / seconds;
            default -> 1;
        };
    }

    private Object scalar(double v, CgQuantity q) {
        return v * factor(q);
    }

    private static boolean permutes(CgQuantity q) {
        return switch (q) {
            case POSITION, OFFSET, LENGTH, SPEED, ACCELERATION, DIRECTION, SCALE, ANGULAR_SPEED -> true;
            default -> false;
        };
    }

    private Object vector(List<?> v, CgQuantity q) {
        double f = factor(q);
        // An angular velocity is a pseudovector: where the axes flip handedness its sense flips too.
        double sense = q == CgQuantity.ANGULAR_SPEED && flipsHandedness() ? -1 : 1;
        List<Object> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            int from = Math.abs(axes[i]) - 1;
            double sign = q == CgQuantity.SCALE ? 1 : Math.signum(axes[i]);
            out.add(((Number) v.get(from)).doubleValue() * f * sign * sense);
        }
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
