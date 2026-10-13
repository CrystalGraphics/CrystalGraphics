package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;

/**
 * A ball about the origin: blasts, auras, pickups, a bolt's head.
 *
 * <pre>{@code
 * static final CgShape BLAST = CgSphere.of(6);
 * BLAST.bounds(pose.at(x, y, z), box);                       // x - 6 .. x + 6 on every axis
 * }</pre>
 */
public final class CgSphere extends CgSizedShape {

    private double radius;

    private CgSphere(double radius) {
        this.radius = radius;
    }

    /** @throws IllegalArgumentException for a negative or non-finite radius */
    public static CgSphere of(double radius) {
        return new CgSphere(CgShapeArgs.length("radius", radius));
    }

    public double radius() {
        return radius;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        return CgShapeArgs.toLength(dx, dy, dz, radius, out);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public CgAABB bounds(CgAABB out) {
        return out.set(-radius, -radius, -radius, radius, radius, radius);
    }

    @Override
    public CgAABB bounds(CgPose pose, CgAABB out) {
        return out.setCenterHalf(pose.x, pose.y, pose.z, radius, radius, radius);
    }

    @Override
    CgSphere copy() {
        return new CgSphere(radius);
    }

    @Override
    void sweptRadius(CgSizedShape into, double lo, double hi) {
        ((CgSphere) into).resize(hi);
    }

    @Override
    void sweptScale(CgSizedShape into, double lo, double hi) {
        ((CgSphere) into).resize(radius() * hi);
    }

    void resize(double radius) {
        this.radius = radius;
    }

    @Override
    public String toString() {
        return "CgSphere[" + radius + "]";
    }
}
