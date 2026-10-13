package com.crystalgraphics.shape.primitive;

import org.joml.Vector3d;

/**
 * A segment with a radius: every point within {@code radius} of it. Sword swings, limbs, a fixed beam.
 *
 * <pre>{@code
 * static final CgShape BLADE = CgCapsule.of(0.5, 0.3);              // along local +Z, 1 long, 0.3 thick
 * CgShape beam = CgCapsule.between(0, 0, 0, 0, 0, 12, 0.6);          // from the origin 12 blocks forward
 * }</pre>
 *
 * <ul>
 *   <li>{@link #of} centres the segment on the origin; {@link #between} puts its ends where you say, in the shape's
 *       own space, so a pose at the muzzle places a beam starting there.</li>
 *   <li>A moving beam is a sphere cast along the aim, not a capsule rebuilt each tick.</li>
 * </ul>
 */
public final class CgCapsule extends CgSizedShape {

    private double ax, ay, az, bx, by, bz, radius;

    private CgCapsule(double ax, double ay, double az, double bx, double by, double bz, double radius) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.radius = radius;
    }

    /** A segment from {@code -halfLength} to {@code halfLength} on local Z. */
    public static CgCapsule of(double halfLength, double radius) {
        CgShapeArgs.length("halfLength", halfLength);
        return new CgCapsule(0, 0, -halfLength, 0, 0, halfLength, CgShapeArgs.length("radius", radius));
    }

    /** A segment between two points of its own space. */
    public static CgCapsule between(double ax, double ay, double az, double bx, double by, double bz, double radius) {
        return new CgCapsule(CgShapeArgs.finite("ax", ax), CgShapeArgs.finite("ay", ay), CgShapeArgs.finite("az", az),
                CgShapeArgs.finite("bx", bx), CgShapeArgs.finite("by", by), CgShapeArgs.finite("bz", bz),
                CgShapeArgs.length("radius", radius));
    }

    public double radius() {
        return radius;
    }

    public Vector3d a(Vector3d out) {
        return out.set(ax, ay, az);
    }

    public Vector3d b(Vector3d out) {
        return out.set(bx, by, bz);
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        CgShapeArgs.toLength(dx, dy, dz, radius, out);
        if (ax * dx + ay * dy + az * dz >= bx * dx + by * dy + bz * dz) return out.add(ax, ay, az);
        return out.add(bx, by, bz);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    double ax() {
        return ax;
    }

    double ay() {
        return ay;
    }

    double az() {
        return az;
    }

    double bx() {
        return bx;
    }

    double by() {
        return by;
    }

    double bz() {
        return bz;
    }

    @Override
    CgCapsule copy() {
        return new CgCapsule(ax, ay, az, bx, by, bz, radius);
    }

    @Override
    void sweptRadius(CgSizedShape into, double lo, double hi) {
        ((CgCapsule) into).resize(ax(), ay(), az(), bx(), by(), bz(), hi);
    }

    @Override
    void sweptScale(CgSizedShape into, double lo, double hi) {
        ((CgCapsule) into).resize(ax() * hi, ay() * hi, az() * hi, bx() * hi, by() * hi, bz() * hi, radius() * hi);
    }

    void resize(double ax, double ay, double az, double bx, double by, double bz, double radius) {
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.radius = radius;
    }

    @Override
    public String toString() {
        return "CgCapsule[" + ax + ", " + ay + ", " + az + " .. " + bx + ", " + by + ", " + bz + " r " + radius + "]";
    }
}
