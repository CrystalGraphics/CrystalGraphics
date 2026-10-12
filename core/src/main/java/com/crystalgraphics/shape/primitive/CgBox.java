package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;

/**
 * A box by its half extents about the origin, turned by its pose: slams, walls, rectangular zones. Unrotated it is an
 * axis-aligned box; a {@link CgAABB} is the type for a box that never turns.
 *
 * <pre>{@code
 * static final CgShape SLAM = CgBox.of(2, 0.5, 3);           // 4 wide, 1 high, 6 long
 * SLAM.bounds(pose.at(x, y, z).facing(yaw, 0), box);         // its box in the world, turned with the look
 * }</pre>
 */
public final class CgBox extends CgShape {

    private double halfX, halfY, halfZ;

    private CgBox(double halfX, double halfY, double halfZ) {
        this.halfX = halfX;
        this.halfY = halfY;
        this.halfZ = halfZ;
    }

    /** @throws IllegalArgumentException for a negative or non-finite half extent */
    public static CgBox of(double halfX, double halfY, double halfZ) {
        return new CgBox(CgShapeArgs.length("halfX", halfX), CgShapeArgs.length("halfY", halfY),
                CgShapeArgs.length("halfZ", halfZ));
    }

    public double halfX() {
        return halfX;
    }

    public double halfY() {
        return halfY;
    }

    public double halfZ() {
        return halfZ;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        return out.set(dx < 0 ? -halfX : halfX, dy < 0 ? -halfY : halfY, dz < 0 ? -halfZ : halfZ);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public CgAABB bounds(CgAABB out) {
        return out.set(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);
    }

    @Override
    public CgAABB bounds(CgPose pose, CgAABB out) {
        double ex = Math.abs(pose.m00()) * halfX + Math.abs(pose.m01()) * halfY + Math.abs(pose.m02()) * halfZ;
        double ey = Math.abs(pose.m10()) * halfX + Math.abs(pose.m11()) * halfY + Math.abs(pose.m12()) * halfZ;
        double ez = Math.abs(pose.m20()) * halfX + Math.abs(pose.m21()) * halfY + Math.abs(pose.m22()) * halfZ;
        return out.setCenterHalf(pose.x, pose.y, pose.z, ex, ey, ez);
    }

    @Override
    public CgBox copy() {
        return new CgBox(halfX, halfY, halfZ);
    }

    void resize(double halfX, double halfY, double halfZ) {
        this.halfX = halfX;
        this.halfY = halfY;
        this.halfZ = halfZ;
    }

    @Override
    public String toString() {
        return "CgBox[" + halfX + ", " + halfY + ", " + halfZ + "]";
    }
}
