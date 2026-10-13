package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Vector3d;

/**
 * A convex shape where a query has put it, relative to the query's base point: its kind and numbers for the closed
 * forms, its support for GJK, and its {@link #core} and {@link #radius} for round shapes (Jolt's convex radius).
 * Reused; {@code set} overwrites everything.
 */
final class CgPlaced implements CgSupport {

    static final int POINT = 0, SPHERE = 1, BOX = 2, CAPSULE = 3, CYLINDER = 4, GENERIC = 5;

    int kind;
    /** Position, relative to the base point. */
    double px, py, pz;
    /** Rotation, world = m * local + p; identity for an axis-aligned box. */
    double m00, m01, m02, m10, m11, m12, m20, m21, m22;
    /** A sphere's, capsule's or cylinder's radius. */
    double radius;
    /** A box's half extents; a cylinder's half height is {@code hy}. */
    double hx, hy, hz;
    /** A capsule's segment, relative to the base point. */
    double ax, ay, az, bx, by, bz;
    CgShape shape;

    /** The shape less its radius: a sphere's centre, a capsule's segment; the whole shape otherwise. */
    final CgSupport core = this::coreSupport;

    private final Vector3d local = new Vector3d();

    /**
     * A convex shape, or one that is convex in all but name (a shell or ring with no hole). Not for a compound or a
     * non-convex shape: those are taken apart before they get here.
     */
    CgPlaced set(CgShape s, CgPose pose, double baseX, double baseY, double baseZ) {
        px = pose.x - baseX;
        py = pose.y - baseY;
        pz = pose.z - baseZ;
        m00 = pose.m00();
        m01 = pose.m01();
        m02 = pose.m02();
        m10 = pose.m10();
        m11 = pose.m11();
        m12 = pose.m12();
        m20 = pose.m20();
        m21 = pose.m21();
        m22 = pose.m22();
        shape = null;
        if (s instanceof CgSphere) {
            kind = SPHERE;
            radius = ((CgSphere) s).radius();
        } else if (s instanceof CgBox) {
            CgBox b = (CgBox) s;
            kind = BOX;
            hx = b.halfX();
            hy = b.halfY();
            hz = b.halfZ();
        } else if (s instanceof CgCapsule) {
            CgCapsule c = (CgCapsule) s;
            kind = CAPSULE;
            radius = c.radius();
            c.a(local);
            ax = m00 * local.x + m01 * local.y + m02 * local.z + px;
            ay = m10 * local.x + m11 * local.y + m12 * local.z + py;
            az = m20 * local.x + m21 * local.y + m22 * local.z + pz;
            c.b(local);
            bx = m00 * local.x + m01 * local.y + m02 * local.z + px;
            by = m10 * local.x + m11 * local.y + m12 * local.z + py;
            bz = m20 * local.x + m21 * local.y + m22 * local.z + pz;
        } else if (s instanceof CgCylinder) {
            kind = CYLINDER;
            radius = ((CgCylinder) s).radius();
            hy = ((CgCylinder) s).halfHeight();
        } else if (s instanceof CgShell && ((CgShell) s).inner() == 0) {
            kind = SPHERE;
            radius = ((CgShell) s).outer();
        } else if (s instanceof CgRing && ((CgRing) s).inner() == 0) {
            kind = CYLINDER;
            radius = ((CgRing) s).outer();
            hy = ((CgRing) s).halfHeight();
        } else {
            kind = GENERIC;
            shape = s;
        }
        return this;
    }

    /** An axis-aligned box. */
    CgPlaced box(CgAABBc box, double baseX, double baseY, double baseZ) {
        kind = BOX;
        identityAt(box.centerX() - baseX, box.centerY() - baseY, box.centerZ() - baseZ);
        hx = box.halfX();
        hy = box.halfY();
        hz = box.halfZ();
        return this;
    }

    /** A ball, already relative to the base point. */
    CgPlaced sphere(double x, double y, double z, double r) {
        kind = SPHERE;
        identityAt(x, y, z);
        radius = r;
        return this;
    }

    /** A point, already relative to the base point. */
    CgPlaced point(double x, double y, double z) {
        kind = POINT;
        identityAt(x, y, z);
        radius = 0;
        return this;
    }

    /** A point carrying a pose's rotation: the frame a ring or a wide cone is tested in. */
    CgPlaced frame(CgPose pose, double baseX, double baseY, double baseZ) {
        kind = POINT;
        px = pose.x - baseX;
        py = pose.y - baseY;
        pz = pose.z - baseZ;
        m00 = pose.m00();
        m01 = pose.m01();
        m02 = pose.m02();
        m10 = pose.m10();
        m11 = pose.m11();
        m12 = pose.m12();
        m20 = pose.m20();
        m21 = pose.m21();
        m22 = pose.m22();
        radius = 0;
        shape = null;
        return this;
    }

    /** An upright cylinder at a pose: a ring's outer wall. */
    CgPlaced cylinder(CgPose pose, double baseX, double baseY, double baseZ, double r, double halfHeight) {
        frame(pose, baseX, baseY, baseZ);
        kind = CYLINDER;
        radius = r;
        hy = halfHeight;
        return this;
    }

    private void identityAt(double x, double y, double z) {
        px = x;
        py = y;
        pz = z;
        m00 = m11 = m22 = 1;
        m01 = m02 = m10 = m12 = m20 = m21 = 0;
        shape = null;
    }

    /** The radius {@link #core} leaves out. */
    double radius() {
        return kind == SPHERE || kind == CAPSULE ? radius : 0;
    }

    /** Moves it, as a cast moves its mover. */
    void translate(double dx, double dy, double dz) {
        px += dx;
        py += dy;
        pz += dz;
        ax += dx;
        ay += dy;
        az += dz;
        bx += dx;
        by += dy;
        bz += dz;
    }

    /** Whether each local axis is a world axis, up to sign. */
    boolean axisAligned() {
        return m01 == 0 && m02 == 0 && m10 == 0 && m12 == 0 && m20 == 0 && m21 == 0;
    }

    /** A point relative to the base point, in the shape's own space. */
    Vector3d toLocal(double x, double y, double z, Vector3d out) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return out.set(m00 * dx + m10 * dy + m20 * dz, m01 * dx + m11 * dy + m21 * dz, m02 * dx + m12 * dy + m22 * dz);
    }

    /** A point of the shape's own space, relative to the base point. */
    Vector3d toWorld(double x, double y, double z, Vector3d out) {
        return out.set(m00 * x + m01 * y + m02 * z + px, m10 * x + m11 * y + m12 * z + py,
                m20 * x + m21 * y + m22 * z + pz);
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        switch (kind) {
            case SPHERE: {
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len == 0) return out.set(px, py, pz);
                double k = radius / len;
                return out.set(px + dx * k, py + dy * k, pz + dz * k);
            }
            case CAPSULE: {
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double k = len == 0 ? 0 : radius / len;
                if (ax * dx + ay * dy + az * dz >= bx * dx + by * dy + bz * dz) {
                    return out.set(ax + dx * k, ay + dy * k, az + dz * k);
                }
                return out.set(bx + dx * k, by + dy * k, bz + dz * k);
            }
            case POINT:
                return out.set(px, py, pz);
            default: {
                double lx = m00 * dx + m10 * dy + m20 * dz, ly = m01 * dx + m11 * dy + m21 * dz,
                        lz = m02 * dx + m12 * dy + m22 * dz;
                double sx, sy, sz;
                if (kind == BOX) {
                    sx = lx < 0 ? -hx : hx;
                    sy = ly < 0 ? -hy : hy;
                    sz = lz < 0 ? -hz : hz;
                } else if (kind == CYLINDER) {
                    double horizontal = Math.sqrt(lx * lx + lz * lz);
                    double k = horizontal == 0 ? 0 : radius / horizontal;
                    sx = lx * k;
                    sy = ly < 0 ? -hy : hy;
                    sz = lz * k;
                } else {
                    shape.support(lx, ly, lz, local);
                    sx = local.x;
                    sy = local.y;
                    sz = local.z;
                }
                return toWorld(sx, sy, sz, out);
            }
        }
    }

    private Vector3d coreSupport(double dx, double dy, double dz, Vector3d out) {
        if (kind == SPHERE) return out.set(px, py, pz);
        if (kind == CAPSULE) {
            if (ax * dx + ay * dy + az * dz >= bx * dx + by * dy + bz * dz) return out.set(ax, ay, az);
            return out.set(bx, by, bz);
        }
        return support(dx, dy, dz, out);
    }
}
