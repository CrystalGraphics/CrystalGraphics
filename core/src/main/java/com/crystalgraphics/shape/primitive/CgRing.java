package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

/**
 * The space between two upright cylinders about local Y: a shockwave running along the ground. A {@link #front front}
 * is {@code width} thick behind whatever radius a hitbox gives it.
 *
 * <pre>{@code
 * static final CgShape MOAT = CgRing.of(5, 7, 1);            // 5 to 7 blocks out, 2 high
 * static final CgShape WAVE = CgRing.front(1.2, 0.6);        // a ground wave's edge, at the radius a hitbox gives it
 * }</pre>
 *
 * <ul>
 *   <li>Not convex: its support is its outer cylinder's, and tests against it are its own.</li>
 *   <li>Centred on the origin vertically: a pose at the ground puts half of it below.</li>
 * </ul>
 */
public final class CgRing extends CgShape {

    private final double inner, outer, halfHeight;
    private final boolean front;

    private CgRing(double inner, double outer, double halfHeight, boolean front) {
        this.inner = inner;
        this.outer = outer;
        this.halfHeight = halfHeight;
        this.front = front;
    }

    /** @throws IllegalArgumentException for a negative size, or {@code inner} past {@code outer} */
    public static CgRing of(double inner, double outer, double halfHeight) {
        CgShapeArgs.length("inner", inner);
        CgShapeArgs.length("outer", outer);
        if (inner > outer) throw new IllegalArgumentException("inner " + inner + " is past outer " + outer);
        return new CgRing(inner, outer, CgShapeArgs.length("halfHeight", halfHeight), false);
    }

    /** A leading edge {@code width} thick: sized by a hitbox's radius, it reaches from {@code radius - width} out. */
    public static CgRing front(double width, double halfHeight) {
        return new CgRing(0, CgShapeArgs.length("width", width), CgShapeArgs.length("halfHeight", halfHeight), true);
    }

    public double inner() {
        return inner;
    }

    public double outer() {
        return outer;
    }

    public double halfHeight() {
        return halfHeight;
    }

    /** Whether this is a {@link #front}, keeping its width as its radius changes. */
    public boolean isFront() {
        return front;
    }

    public double width() {
        return outer - inner;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        return CgCylinder.cylinderSupport(outer, halfHeight, dx, dy, dz, out);
    }

    @Override
    public boolean isConvex() {
        return inner == 0;
    }

    @Override
    public String toString() {
        return front ? "CgRing[front " + outer + ", half height " + halfHeight + "]"
                : "CgRing[" + inner + " .. " + outer + ", half height " + halfHeight + "]";
    }
}
