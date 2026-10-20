package com.crystalgraphics.object.value;

/**
 * What a number measures, so the loader knows how to convert it from the asset's units to the host's.
 *
 * <p>A property declares one; the file never says it. An imported value keeps its game's number, and the compile
 * converts it once by this quantity and the asset's {@code units}.</p>
 *
 * <pre>{@code
 * .property("radius", CgValueType.FLOAT).quantity(CgQuantity.LENGTH)        // cm in a Kakarot kit, metres here
 * .property("rotation_rate", CgValueType.FLOAT).quantity(CgQuantity.ANGULAR_SPEED)
 * .property("opacity", CgValueType.FLOAT)                                     // NONE: never converted
 * }</pre>
 *
 * <ul>
 *   <li>{@link #NONE} is the default: an opacity, a share or a count converts nothing.</li>
 *   <li>A curve has no quantity of its own; the input it fills converts it.</li>
 * </ul>
 */
public enum CgQuantity {
    /** Untouched: opacity, a share, a count. */
    NONE("none"),
    /** Times the host's metres per asset length. */
    LENGTH("length"),
    /** A point: scaled as a length, a vector through the axis permutation. */
    POSITION("position"),
    /** A displacement: scaled as a length, a vector through the axis permutation. */
    OFFSET("offset"),
    /** Length over time. */
    SPEED("speed"),
    /** Length over time squared. */
    ACCELERATION("acceleration"),
    /** Through the axis permutation only. */
    DIRECTION("direction"),
    /** A vector through the axis permutation, without its signs. */
    SCALE("scale"),
    /** To radians; its sense flips where the axes flip handedness. */
    ANGLE("angle"),
    /** To radians per second. */
    ANGULAR_SPEED("angular_speed"),
    /** Its convention to a matrix {@code R}, then {@code A R Aᵀ}. */
    ROTATION("rotation"),
    /** Times the host's seconds per asset time. */
    TIME("time"),
    /** Per time. */
    FREQUENCY("frequency"),
    /** Per time. */
    RATE("rate"),
    /** Per length. */
    PER_LENGTH("per_length"),
    /** Converted for the colour space its reader declares. */
    COLOUR("colour");

    private final String spec;

    CgQuantity(String spec) {
        this.spec = spec;
    }

    /** Its name in the format, as {@code modules.yaml} writes it ({@code angular_speed}). */
    public String specName() {
        return spec;
    }

    /** The quantity the format names {@code name}, or null if none does. */
    public static CgQuantity bySpecName(String name) {
        for (CgQuantity q : values()) {
            if (q.spec.equals(name)) return q;
        }
        return null;
    }
}
