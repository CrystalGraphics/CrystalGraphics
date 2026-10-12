package com.crystalgraphics.shape.primitive;

import org.joml.Vector3d;

/** The argument checks every shape factory shares. */
final class CgShapeArgs {

    private CgShapeArgs() {
    }

    /** A length: finite and not negative. */
    static double length(String name, double value) {
        if (!(value >= 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and not negative: " + value);
        }
        return value;
    }

    static double finite(String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite: " + value);
        }
        return value;
    }

    /** {@code (dx, dy, dz)} scaled to {@code length}; a zero direction answers {@code (0, 0, length)}. */
    static Vector3d toLength(double dx, double dy, double dz, double length, Vector3d out) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return out.set(0, 0, length);
        double k = length / len;
        return out.set(dx * k, dy * k, dz * k);
    }
}
