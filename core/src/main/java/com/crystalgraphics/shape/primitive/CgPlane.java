package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

/**
 * A plane through the origin, solid on the side away from its normal: a floor, a wall, a ramp. It ends {@code radius}
 * from the origin, a disc, and is that deep behind it, so it is convex and every exact test takes it.
 *
 * <pre>{@code
 * static final CgShape FLOOR = CgPlane.of(0, 1, 0);           // solid below, 1000 blocks round and deep
 * static final CgShape RAMP = CgPlane.of(0.4, 1, 0, 5);       // tilted, and pieces fall off 5 blocks out
 * }</pre>
 *
 * <ul>
 *   <li>The default {@link #DEFAULT_RADIUS} is Jolt's: past any query, small enough that support points stay exact in
 *       doubles.</li>
 *   <li>The normal need not be unit length; a zero one is refused.</li>
 * </ul>
 *
 * <p>Ported from Jolt Physics (MIT, © 2024 Jorrit Rouwe), {@code Jolt/Physics/Collision/Shape/PlaneShape.cpp}: the
 * normal, the half extent and the solid behind it. Jolt's face is a square turned about the normal by an arbitrary
 * basis; this one is a disc, which has no turn, and Jolt's half extent is its radius.</p>
 */
public final class CgPlane extends CgShape {

    /** Jolt's {@code cDefaultHalfExtent}. */
    public static final double DEFAULT_RADIUS = 1000;

    private final double nx, ny, nz, radius;

    private CgPlane(double nx, double ny, double nz, double radius) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.radius = radius;
    }

    /** @throws IllegalArgumentException for a zero or non-finite normal */
    public static CgPlane of(double nx, double ny, double nz) {
        return of(nx, ny, nz, DEFAULT_RADIUS);
    }

    /** @throws IllegalArgumentException for a zero or non-finite normal, or a radius not finite and positive */
    public static CgPlane of(double nx, double ny, double nz, double radius) {
        CgShapeArgs.finite("nx", nx);
        CgShapeArgs.finite("ny", ny);
        CgShapeArgs.finite("nz", nz);
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 0)) throw new IllegalArgumentException("a plane needs a normal");
        if (!(CgShapeArgs.length("radius", radius) > 0)) throw new IllegalArgumentException("a plane of radius 0");
        return new CgPlane(nx / length, ny / length, nz / length, radius);
    }

    /** The unit normal's x, out of the solid side. */
    public double nx() {
        return nx;
    }

    public double ny() {
        return ny;
    }

    public double nz() {
        return nz;
    }

    /** How far the face reaches from the origin, and how deep the solid is behind it. */
    public double radius() {
        return radius;
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        // a cylinder: the face's rim toward d's part along the face, then the back face when d points behind
        double along = dx * nx + dy * ny + dz * nz;
        double tx = dx - nx * along, ty = dy - ny * along, tz = dz - nz * along;
        double t = Math.sqrt(tx * tx + ty * ty + tz * tz);
        double k = t > 0 ? radius / t : 0, back = along < 0 ? -radius : 0;
        return out.set(tx * k + nx * back, ty * k + ny * back, tz * k + nz * back);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public String toString() {
        return "CgPlane[" + nx + ", " + ny + ", " + nz + ", radius " + radius + "]";
    }
}
