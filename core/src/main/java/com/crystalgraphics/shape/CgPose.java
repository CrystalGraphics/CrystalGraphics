package com.crystalgraphics.shape;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * Where a shape is placed: a position in doubles and a rotation. Shapes are defined about their own origin, local +Z
 * their forward and +Y their up; a pose puts that origin somewhere and turns it. Mutable and reused.
 *
 * <pre>{@code
 * private final CgPose pose = new CgPose();
 *
 * pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch);      // radians: yaw 0 faces +z, pitch down positive
 * pose.at(x, y, z).rotation(quaternion);             // any rotation
 * shape.bounds(pose, box);
 * }</pre>
 *
 * A player's look, which {@code CgEntityQuery.pose} reports in Minecraft's degrees:
 *
 * <pre>{@code
 * double yaw = Math.toRadians(look[CgEntityQuery.YAW]), pitch = Math.toRadians(look[CgEntityQuery.PITCH]);
 * pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch);
 * }</pre>
 *
 * <ul>
 *   <li>Angles are radians everywhere in {@code shape}; {@link #facing} follows Minecraft's directions, not its
 *       units.</li>
 *   <li>A rotation is normalized as it is set; position calls leave it alone, and the other way round.</li>
 * </ul>
 */
public final class CgPose {

    public double x, y, z;
    /** The rotation as a quaternion, and the same as a matrix. */
    private double qx, qy, qz, qw = 1;
    private double m00 = 1, m01, m02, m10, m11 = 1, m12, m20, m21, m22 = 1;

    /** At the origin, unrotated. */
    public CgPose() {
    }

    public CgPose(CgPose other) {
        set(other);
    }

    public CgPose at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** Turns local +Z to look along a yaw and pitch in radians, as Minecraft's point, with no roll. */
    public CgPose facing(double yaw, double pitch) {
        double halfYaw = -yaw * 0.5, halfPitch = pitch * 0.5;
        double sy = Math.sin(halfYaw), cy = Math.cos(halfYaw), sp = Math.sin(halfPitch), cp = Math.cos(halfPitch);
        // about Y by -yaw, then about X by pitch
        return rotation(cy * sp, cp * sy, -sy * sp, cy * cp);
    }

    public CgPose rotation(Quaternionfc q) {
        return rotation(q.x(), q.y(), q.z(), q.w());
    }

    public CgPose rotation(Quaterniondc q) {
        return rotation(q.x(), q.y(), q.z(), q.w());
    }

    /** A rotation as a quaternion's parts; normalized here. A zero quaternion is no rotation. */
    public CgPose rotation(double x, double y, double z, double w) {
        double len = Math.sqrt(x * x + y * y + z * z + w * w);
        if (len == 0) {
            x = y = z = 0;
            w = 1;
        } else {
            x /= len;
            y /= len;
            z /= len;
            w /= len;
        }
        qx = x;
        qy = y;
        qz = z;
        qw = w;
        double xx = x * x, yy = y * y, zz = z * z, xy = x * y, xz = x * z, yz = y * z, wx = w * x, wy = w * y,
                wz = w * z;
        m00 = 1 - 2 * (yy + zz);
        m01 = 2 * (xy - wz);
        m02 = 2 * (xz + wy);
        m10 = 2 * (xy + wz);
        m11 = 1 - 2 * (xx + zz);
        m12 = 2 * (yz - wx);
        m20 = 2 * (xz - wy);
        m21 = 2 * (yz + wx);
        m22 = 1 - 2 * (xx + yy);
        return this;
    }

    /** Back to the origin, unrotated. */
    public CgPose identity() {
        return at(0, 0, 0).rotation(0, 0, 0, 1);
    }

    public CgPose set(CgPose other) {
        x = other.x;
        y = other.y;
        z = other.z;
        qx = other.qx;
        qy = other.qy;
        qz = other.qz;
        qw = other.qw;
        m00 = other.m00;
        m01 = other.m01;
        m02 = other.m02;
        m10 = other.m10;
        m11 = other.m11;
        m12 = other.m12;
        m20 = other.m20;
        m21 = other.m21;
        m22 = other.m22;
        return this;
    }

    /** The rotation matrix, row then column: world = m * local + position. */
    public double m00() {
        return m00;
    }

    public double m01() {
        return m01;
    }

    public double m02() {
        return m02;
    }

    public double m10() {
        return m10;
    }

    public double m11() {
        return m11;
    }

    public double m12() {
        return m12;
    }

    public double m20() {
        return m20;
    }

    public double m21() {
        return m21;
    }

    public double m22() {
        return m22;
    }

    public Quaterniond rotation(Quaterniond out) {
        return out.set(qx, qy, qz, qw);
    }

    /** A point of the shape's own space, in the world. */
    public Vector3d toWorld(double lx, double ly, double lz, Vector3d out) {
        return out.set(m00 * lx + m01 * ly + m02 * lz + x, m10 * lx + m11 * ly + m12 * lz + y,
                m20 * lx + m21 * ly + m22 * lz + z);
    }

    /** A world point, in the shape's own space. */
    public Vector3d toLocal(double wx, double wy, double wz, Vector3d out) {
        double dx = wx - x, dy = wy - y, dz = wz - z;
        return unrotate(dx, dy, dz, out);
    }

    /** A direction of the shape's own space, in the world: rotated, not moved. */
    public Vector3d rotate(double dx, double dy, double dz, Vector3d out) {
        return out.set(m00 * dx + m01 * dy + m02 * dz, m10 * dx + m11 * dy + m12 * dz, m20 * dx + m21 * dy + m22 * dz);
    }

    /** A world direction, in the shape's own space. */
    public Vector3d unrotate(double dx, double dy, double dz, Vector3d out) {
        return out.set(m00 * dx + m10 * dy + m20 * dz, m01 * dx + m11 * dy + m21 * dz, m02 * dx + m12 * dy + m22 * dz);
    }

    @Override
    public String toString() {
        return "CgPose[" + x + ", " + y + ", " + z + " q(" + qx + ", " + qy + ", " + qz + ", " + qw + ")]";
    }
}
