package com.crystalgraphics.collision.hitbox;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

/**
 * A convex shape and everything it passes moving along a segment, by its support: the shape's support, plus the motion
 * wherever the direction leans along it (the Minkowski sum with the segment). What a hitbox tests between two ticks so
 * nothing slips past it. One hitbox's, changed for every test.
 */
final class CgSwept extends CgShape {

    private CgShape shape;
    /** The motion, in the shape's own space. */
    private double mx, my, mz;

    CgSwept set(CgShape shape, double mx, double my, double mz) {
        this.shape = shape;
        this.mx = mx;
        this.my = my;
        this.mz = mz;
        return this;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        shape.support(dx, dy, dz, out);
        if (dx * mx + dy * my + dz * mz > 0) out.add(mx, my, mz);
        return out;
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public CgSwept copy() {
        return new CgSwept().set(shape, mx, my, mz);
    }

    @Override
    public String toString() {
        return "CgSwept[" + shape + " along " + mx + ", " + my + ", " + mz + "]";
    }
}
