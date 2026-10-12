package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

/**
 * An upright cylinder about local Y, centred on the origin: pillars, columns of fire, circles on the ground.
 *
 * <pre>{@code
 * static final CgShape PILLAR = CgCylinder.of(1.5, 4);       // 3 across, 8 high
 * PILLAR.bounds(pose.at(x, y + 4, z), box);                  // standing on y
 * }</pre>
 */
public final class CgCylinder extends CgSizedShape {

    private double radius, halfHeight;

    private CgCylinder(double radius, double halfHeight) {
        this.radius = radius;
        this.halfHeight = halfHeight;
    }

    public static CgCylinder of(double radius, double halfHeight) {
        return new CgCylinder(CgShapeArgs.length("radius", radius), CgShapeArgs.length("halfHeight", halfHeight));
    }

    public double radius() {
        return radius;
    }

    public double halfHeight() {
        return halfHeight;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        return cylinderSupport(radius, halfHeight, dx, dy, dz, out);
    }

    /** The support of an upright cylinder, which a ring's hull shares. */
    static Vector3d cylinderSupport(double radius, double halfHeight, double dx, double dy, double dz, Vector3d out) {
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double y = dy < 0 ? -halfHeight : halfHeight;
        if (horizontal == 0) return out.set(0, y, 0);
        double k = radius / horizontal;
        return out.set(dx * k, y, dz * k);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public CgCylinder copy() {
        return new CgCylinder(radius, halfHeight);
    }

    @Override
    void sweptRadius(CgSizedShape into, double lo, double hi) {
        ((CgCylinder) into).resize(hi, halfHeight());
    }

    @Override
    void sweptScale(CgSizedShape into, double lo, double hi) {
        ((CgCylinder) into).resize(radius() * hi, halfHeight() * hi);
    }

    void resize(double radius, double halfHeight) {
        this.radius = radius;
        this.halfHeight = halfHeight;
    }

    @Override
    public String toString() {
        return "CgCylinder[r " + radius + ", half height " + halfHeight + "]";
    }
}
