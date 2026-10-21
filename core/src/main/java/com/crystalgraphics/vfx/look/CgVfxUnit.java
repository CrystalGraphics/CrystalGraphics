package com.crystalgraphics.vfx.look;

import com.crystalgraphics.object.value.CgQuantity;

/**
 * What a number measures: the one unit system every look is authored in. Tools show it, and
 * {@link CgVfxLook#scaled} multiplies every {@link #LENGTH} and {@link #SPEED}.
 *
 * <pre>{@code
 * public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.8f);   // blocks
 * public static final CgVfxParam.Scalar LIFE = SCHEMA.time("life", 1.2f);          // seconds
 * }</pre>
 */
public enum CgVfxUnit {
    /** A count, a share, a multiple: unchanged by scaling. */
    NONE(""),
    /** Blocks. */
    LENGTH("blocks"),
    /** Seconds. */
    TIME("s"),
    /** Blocks a second. */
    SPEED("blocks/s"),
    /** Radians. */
    ANGLE("rad"),
    /** Times a second, or radians a second. */
    RATE("/s");

    private final String symbol;

    CgVfxUnit(String symbol) {
        this.symbol = symbol;
    }

    /** What a tool writes after a value: {@code "blocks"}, {@code "s"}; empty for {@link #NONE}. */
    public String symbol() {
        return symbol;
    }

    /** What it measures in the object model: the quantity its parameter's property declares. */
    public CgQuantity quantity() {
        switch (this) {
            case LENGTH: return CgQuantity.LENGTH;
            case TIME: return CgQuantity.TIME;
            case SPEED: return CgQuantity.SPEED;
            case ANGLE: return CgQuantity.ANGLE;
            case RATE: return CgQuantity.RATE;
            default: return CgQuantity.NONE;
        }
    }

    /** Whether {@link CgVfxLook#scaled} multiplies it: lengths and speeds. */
    public boolean scales() {
        return this == LENGTH || this == SPEED;
    }
}
