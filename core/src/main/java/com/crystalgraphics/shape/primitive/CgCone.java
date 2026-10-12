package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

/**
 * A spherical sector: everything within {@code range} of the apex and within {@code halfAngle} of local +Z. Breath,
 * cleaves, shotgun blasts, sight cones; the apex is the origin, so the pose is where it comes from.
 *
 * <pre>{@code
 * static final CgShape CLEAVE = CgCone.of(8, 35);            // 8 blocks long, 35 degrees either side of the look
 * CLEAVE.bounds(pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch), box);
 * }</pre>
 *
 * <ul>
 *   <li>The far end is a cap of the sphere, not a flat disc: a target at the edge of the angle is as far away as one
 *       straight ahead.</li>
 *   <li>Up to 90 degrees it is convex; wider (up to 180, a whole ball) it is not, and is tested as a ball and an
 *       angle.</li>
 * </ul>
 */
public final class CgCone extends CgShape {

    private final double range, halfAngle, sin, cos;

    private CgCone(double range, double halfAngle) {
        this.range = range;
        this.halfAngle = halfAngle;
        this.sin = Math.sin(halfAngle);
        this.cos = Math.cos(halfAngle);
    }

    /** @throws IllegalArgumentException for a negative range, or an angle outside 0 to 180 degrees */
    public static CgCone of(double range, double halfAngleDegrees) {
        CgShapeArgs.length("range", range);
        if (!(halfAngleDegrees >= 0 && halfAngleDegrees <= 180)) {
            throw new IllegalArgumentException("halfAngleDegrees must be 0 to 180: " + halfAngleDegrees);
        }
        return new CgCone(range, Math.toRadians(halfAngleDegrees));
    }

    public double range() {
        return range;
    }

    /** In radians. */
    public double halfAngle() {
        return halfAngle;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return out.set(0, 0, range);
        if (dz >= cos * len) return out.set(dx * range / len, dy * range / len, dz * range / len);
        // outside the cap's directions: the rim point in the plane of d and the axis, or the apex, whichever is farther
        double across = Math.sqrt(dx * dx + dy * dy);
        double ux = across == 0 ? 1 : dx / across, uy = across == 0 ? 0 : dy / across;
        if (sin * across + cos * dz <= 0) return out.set(0, 0, 0);
        return out.set(range * sin * ux, range * sin * uy, range * cos);
    }

    @Override
    public boolean isConvex() {
        return halfAngle <= Math.PI / 2;
    }

    @Override
    public String toString() {
        return "CgCone[range " + range + ", half angle " + Math.toDegrees(halfAngle) + "]";
    }
}
