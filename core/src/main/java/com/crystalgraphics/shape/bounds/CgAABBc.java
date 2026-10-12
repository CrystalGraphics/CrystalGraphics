package com.crystalgraphics.shape.bounds;

import com.crystalgraphics.shape.CgPose;
import org.joml.Matrix4fc;
import org.joml.Vector3d;

/**
 * A read-only view of an axis-aligned box: what a mesh hands out as its bounds, so nothing can change them behind it.
 * {@link CgAABB} is the mutable box; this is its {@code Vector3fc}, in JOML's sense.
 *
 * <pre>{@code
 * CgAABBc box = mesh.bounds();
 * if (box != null && box.intersects(area)) ...
 * double r = box.boundingRadius();
 * }</pre>
 *
 * <ul>
 *   <li>An empty box ({@link #isEmpty()}, any min above its max) contains and intersects nothing, and a union with it
 *       is the other box.</li>
 *   <li>Coordinates are doubles: world positions are, and a local box loses nothing.</li>
 * </ul>
 */
public interface CgAABBc {

    /** -1 to 1 on every axis, read-only. */
    CgAABBc UNIT = new CgAABB.Fixed(-1, -1, -1, 1, 1, 1);

    double minX();

    double minY();

    double minZ();

    double maxX();

    double maxY();

    double maxZ();

    default double centerX() {
        return (minX() + maxX()) * 0.5;
    }

    default double centerY() {
        return (minY() + maxY()) * 0.5;
    }

    default double centerZ() {
        return (minZ() + maxZ()) * 0.5;
    }

    default double halfX() {
        return (maxX() - minX()) * 0.5;
    }

    default double halfY() {
        return (maxY() - minY()) * 0.5;
    }

    default double halfZ() {
        return (maxZ() - minZ()) * 0.5;
    }

    default boolean isEmpty() {
        return minX() > maxX() || minY() > maxY() || minZ() > maxZ();
    }

    /** Whether the point is inside or on the surface. */
    default boolean contains(double x, double y, double z) {
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    /** Whether {@code other} lies wholly inside this box. An empty {@code other} is in nothing. */
    default boolean contains(CgAABBc other) {
        return !other.isEmpty() && other.minX() >= minX() && other.maxX() <= maxX() && other.minY() >= minY()
                && other.maxY() <= maxY() && other.minZ() >= minZ() && other.maxZ() <= maxZ();
    }

    /** Whether the two boxes share a point; touching faces count. */
    default boolean intersects(CgAABBc other) {
        return minX() <= other.maxX() && maxX() >= other.minX() && minY() <= other.maxY() && maxY() >= other.minY()
                && minZ() <= other.maxZ() && maxZ() >= other.minZ();
    }

    /** The point of the box nearest to {@code (x, y, z)}: the point itself when inside. */
    default Vector3d closestPoint(double x, double y, double z, Vector3d out) {
        return out.set(Math.min(Math.max(x, minX()), maxX()), Math.min(Math.max(y, minY()), maxY()),
                Math.min(Math.max(z, minZ()), maxZ()));
    }

    /** The squared distance from the point to the box; 0 inside. */
    default double distanceSquared(double x, double y, double z) {
        double dx = Math.max(Math.max(minX() - x, x - maxX()), 0);
        double dy = Math.max(Math.max(minY() - y, y - maxY()), 0);
        double dz = Math.max(Math.max(minZ() - z, z - maxZ()), 0);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Where a ray enters the box, as a multiple of its direction: 0 when it starts inside, and
     * {@code Double.POSITIVE_INFINITY} when it misses or enters past {@code max}. Takes the inverse direction, computed
     * once per ray (a zero component is an infinite inverse, which the slabs handle).
     *
     * <pre>{@code
     * double t = box.rayHit(ox, oy, oz, 1 / dx, 1 / dy, 1 / dz, 64);
     * if (t != Double.POSITIVE_INFINITY) hitAt(ox + dx * t, oy + dy * t, oz + dz * t);
     * }</pre>
     */
    default double rayHit(double ox, double oy, double oz, double invDx, double invDy, double invDz, double max) {
        // Jolt RayAABox: the slab test, with NaN (0 * inf, a ray in a slab's plane) losing every min and max
        double t1 = (minX() - ox) * invDx, t2 = (maxX() - ox) * invDx;
        double near = Math.min(t1, t2), far = Math.max(t1, t2);
        t1 = (minY() - oy) * invDy;
        t2 = (maxY() - oy) * invDy;
        near = CgAABB.nanLosesMax(near, Math.min(t1, t2));
        far = CgAABB.nanLosesMin(far, Math.max(t1, t2));
        t1 = (minZ() - oz) * invDz;
        t2 = (maxZ() - oz) * invDz;
        near = CgAABB.nanLosesMax(near, Math.min(t1, t2));
        far = CgAABB.nanLosesMin(far, Math.max(t1, t2));
        if (near > far || far < 0 || near > max || Double.isNaN(near) || Double.isNaN(far)) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(near, 0);
    }

    /** The radius of the sphere about the centre that holds the box; 0 for an empty one. */
    default double boundingRadius() {
        if (isEmpty()) return 0;
        double hx = halfX(), hy = halfY(), hz = halfZ();
        return Math.sqrt(hx * hx + hy * hy + hz * hz);
    }

    /**
     * The box holding this one after {@code m}, written into {@code out} (which may be this box). Arvo's method: the
     * centre transformed, the half extents by the absolute rotation and scale. Empty stays empty.
     */
    default CgAABB transform(Matrix4fc m, CgAABB out) {
        if (isEmpty()) return out.empty();
        double cx = centerX(), cy = centerY(), cz = centerZ(), hx = halfX(), hy = halfY(), hz = halfZ();
        double nx = m.m00() * cx + m.m10() * cy + m.m20() * cz + m.m30();
        double ny = m.m01() * cx + m.m11() * cy + m.m21() * cz + m.m31();
        double nz = m.m02() * cx + m.m12() * cy + m.m22() * cz + m.m32();
        double ex = Math.abs(m.m00()) * hx + Math.abs(m.m10()) * hy + Math.abs(m.m20()) * hz;
        double ey = Math.abs(m.m01()) * hx + Math.abs(m.m11()) * hy + Math.abs(m.m21()) * hz;
        double ez = Math.abs(m.m02()) * hx + Math.abs(m.m12()) * hy + Math.abs(m.m22()) * hz;
        return out.set(nx - ex, ny - ey, nz - ez, nx + ex, ny + ey, nz + ez);
    }

    /** As {@link #transform(Matrix4fc, CgAABB)}, placing an own-space box by a pose. */
    default CgAABB transform(CgPose pose, CgAABB out) {
        if (isEmpty()) return out.empty();
        double cx = centerX(), cy = centerY(), cz = centerZ(), hx = halfX(), hy = halfY(), hz = halfZ();
        double nx = pose.m00() * cx + pose.m01() * cy + pose.m02() * cz + pose.x;
        double ny = pose.m10() * cx + pose.m11() * cy + pose.m12() * cz + pose.y;
        double nz = pose.m20() * cx + pose.m21() * cy + pose.m22() * cz + pose.z;
        double ex = Math.abs(pose.m00()) * hx + Math.abs(pose.m01()) * hy + Math.abs(pose.m02()) * hz;
        double ey = Math.abs(pose.m10()) * hx + Math.abs(pose.m11()) * hy + Math.abs(pose.m12()) * hz;
        double ez = Math.abs(pose.m20()) * hx + Math.abs(pose.m21()) * hy + Math.abs(pose.m22()) * hz;
        return out.set(nx - ex, ny - ey, nz - ez, nx + ex, ny + ey, nz + ez);
    }
}
