package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;

/**
 * A shape {@link CgResizable} can size: each sizes a copy of itself, so a resize per tick is one virtual call rather
 * than a test of the shape's type.
 */
abstract class CgSizedShape extends CgShape {

    /** A shape equal to this one that shares nothing with it, for {@link CgResizable} to size. */
    abstract CgSizedShape copy();

    /** Whether {@link #sweptRadius} applies. */
    boolean hasRadius() {
        return true;
    }

    /** {@code into}, a copy of this, as everything this covers at every radius from {@code lo} to {@code hi}. */
    abstract void sweptRadius(CgSizedShape into, double lo, double hi);

    /** {@code into}, a copy of this, as everything this covers at every scale from {@code lo} to {@code hi}. */
    abstract void sweptScale(CgSizedShape into, double lo, double hi);

    /** A shell's or ring's inner edge at outer radius {@code r}: a front keeps its width, any other its proportion. */
    static double innerAt(double inner, double outer, boolean front, double r) {
        if (front) return Math.max(0, r - (outer - inner));
        return outer == 0 ? 0 : inner * r / outer;
    }
}
