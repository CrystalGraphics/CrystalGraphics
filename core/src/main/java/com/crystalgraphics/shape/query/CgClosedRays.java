package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;

import static com.crystalgraphics.shape.query.CgPlaced.BOX;
import static com.crystalgraphics.shape.query.CgPlaced.CAPSULE;
import static com.crystalgraphics.shape.query.CgPlaced.CYLINDER;
import static com.crystalgraphics.shape.query.CgPlaced.SPHERE;

/**
 * Rays in closed form: against spheres, boxes, capsules and cylinders, and against the shell and ring a support
 * function cannot describe. After Jolt Physics' {@code Jolt/Geometry/RaySphere.h}, {@code RayCapsule.h},
 * {@code RayCylinder.h} and {@code RayAABox.h} (MIT, Jorrit Rouwe), in doubles, with each surface's normal.
 *
 * <p>Every ray is relative to the query's base point, its direction unit length, and answers the distance to the
 * first hit: 0 with the normal {@code -dir} when it starts inside, {@link Double#POSITIVE_INFINITY} for a miss.
 */
final class CgClosedRays {

    private final Vector3d o = new Vector3d(), d = new Vector3d(), n = new Vector3d();
    private final CgAABB box = new CgAABB();
    /** {@link #sphereSpan}'s and the cylinder spans' answer. */
    private double enter, exit;
    /** Whether {@link #cylinderSpan} entered through a cap. */
    private boolean enterCap;

    /** The distance, or NaN for a kind with no closed form. */
    double ray(CgPlaced s, double ox, double oy, double oz, double dx, double dy, double dz, double max,
               Vector3d outNormal) {
        switch (s.kind) {
            case SPHERE:
                return sphere(s.px, s.py, s.pz, s.radius, ox, oy, oz, dx, dy, dz, max, outNormal);
            case BOX:
                return box(s, ox, oy, oz, dx, dy, dz, max, outNormal);
            case CAPSULE:
                return capsule(s, ox, oy, oz, dx, dy, dz, max, outNormal);
            case CYLINDER: {
                s.toLocal(ox, oy, oz, o);
                localDirection(s, dx, dy, dz, d);
                if (!cylinderSpan(o, d, s.radius, s.hy) || exit < 0 || enter > max) return Double.POSITIVE_INFINITY;
                if (enter <= 0) return inside(dx, dy, dz, outNormal);
                double hx = o.x + d.x * enter, hy = o.y + d.y * enter, hz = o.z + d.z * enter;
                if (enterCap) n.set(0, Math.signum(hy), 0);
                else n.set(hx, 0, hz).normalize();
                rotate(s, n, outNormal);
                return enter;
            }
            default:
                return Double.NaN;
        }
    }

    private double sphere(double cx, double cy, double cz, double r, double ox, double oy, double oz, double dx,
                          double dy, double dz, double max, Vector3d outNormal) {
        if (!sphereSpan(ox - cx, oy - cy, oz - cz, dx, dy, dz, r) || exit < 0 || enter > max) {
            return Double.POSITIVE_INFINITY;
        }
        if (enter <= 0) return inside(dx, dy, dz, outNormal);
        outNormal.set(ox + dx * enter - cx, oy + dy * enter - cy, oz + dz * enter - cz).div(r);
        return enter;
    }

    private double box(CgPlaced s, double ox, double oy, double oz, double dx, double dy, double dz, double max,
                       Vector3d outNormal) {
        s.toLocal(ox, oy, oz, o);
        localDirection(s, dx, dy, dz, d);
        double t = box.set(-s.hx, -s.hy, -s.hz, s.hx, s.hy, s.hz).rayHit(o.x, o.y, o.z, 1 / d.x, 1 / d.y, 1 / d.z, max);
        if (t == Double.POSITIVE_INFINITY) return t;
        if (t == 0 && box.contains(o.x, o.y, o.z)) return inside(dx, dy, dz, outNormal);
        // the face hit is the one the point is farthest out on, relative to its half extent
        double hx = (o.x + d.x * t) / Math.max(s.hx, Double.MIN_NORMAL);
        double hy = (o.y + d.y * t) / Math.max(s.hy, Double.MIN_NORMAL);
        double hz = (o.z + d.z * t) / Math.max(s.hz, Double.MIN_NORMAL);
        double ax = Math.abs(hx), ay = Math.abs(hy), az = Math.abs(hz);
        if (ax >= ay && ax >= az) n.set(Math.signum(hx), 0, 0);
        else if (ay >= az) n.set(0, Math.signum(hy), 0);
        else n.set(0, 0, Math.signum(hz));
        rotate(s, n, outNormal);
        return t;
    }

    private double capsule(CgPlaced s, double ox, double oy, double oz, double dx, double dy, double dz, double max,
                           Vector3d outNormal) {
        double ux = s.bx - s.ax, uy = s.by - s.ay, uz = s.bz - s.az;
        double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double t;
        if (len == 0) {
            t = sphereT(s.ax, s.ay, s.az, s.radius, ox, oy, oz, dx, dy, dz);
        } else {
            ux /= len;
            uy /= len;
            uz /= len;
            // the infinite cylinder about the segment, seen across its axis
            double wx = ox - s.ax, wy = oy - s.ay, wz = oz - s.az;
            double wu = wx * ux + wy * uy + wz * uz, du = dx * ux + dy * uy + dz * uz;
            double px = wx - wu * ux, py = wy - wu * uy, pz = wz - wu * uz;
            double qx = dx - du * ux, qy = dy - du * uy, qz = dz - du * uz;
            t = Double.POSITIVE_INFINITY;
            if (span(qx * qx + qy * qy + qz * qz, px * qx + py * qy + pz * qz, px * px + py * py + pz * pz
                    - s.radius * s.radius) && exit >= 0) {
                double c = Math.max(enter, 0);
                double along = wu + du * c;
                if (along >= 0 && along <= len) t = c;
            }
            if (t == Double.POSITIVE_INFINITY) {
                t = Math.min(sphereT(s.ax, s.ay, s.az, s.radius, ox, oy, oz, dx, dy, dz),
                        sphereT(s.bx, s.by, s.bz, s.radius, ox, oy, oz, dx, dy, dz));
            }
        }
        if (t > max) return Double.POSITIVE_INFINITY;
        if (t == 0 && insideCapsule(s, ox, oy, oz)) return inside(dx, dy, dz, outNormal);
        double hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
        double vx = s.bx - s.ax, vy = s.by - s.ay, vz = s.bz - s.az, lenSq = vx * vx + vy * vy + vz * vz;
        double k = lenSq == 0 ? 0 : Math.max(0, Math.min(1, ((hx - s.ax) * vx + (hy - s.ay) * vy + (hz - s.az) * vz)
                / lenSq));
        outNormal.set(hx - s.ax - vx * k, hy - s.ay - vy * k, hz - s.az - vz * k).normalize();
        return t;
    }

    private static boolean insideCapsule(CgPlaced s, double x, double y, double z) {
        double vx = s.bx - s.ax, vy = s.by - s.ay, vz = s.bz - s.az, lenSq = vx * vx + vy * vy + vz * vz;
        double k = lenSq == 0 ? 0 : Math.max(0, Math.min(1, ((x - s.ax) * vx + (y - s.ay) * vy + (z - s.az) * vz)
                / lenSq));
        double ex = x - s.ax - vx * k, ey = y - s.ay - vy * k, ez = z - s.az - vz * k;
        return ex * ex + ey * ey + ez * ez <= s.radius * s.radius;
    }

    /**
     * A ray against the space between two spheres: the first point of the outer ball not inside the inner one.
     */
    double shell(double cx, double cy, double cz, double inner, double outer, double ox, double oy, double oz,
                 double dx, double dy, double dz, double max, Vector3d outNormal) {
        double rx = ox - cx, ry = oy - cy, rz = oz - cz;
        if (!sphereSpan(rx, ry, rz, dx, dy, dz, outer) || exit < 0 || enter > max) return Double.POSITIVE_INFINITY;
        double t = Math.max(enter, 0), outerExit = exit;
        if (sphereSpan(rx, ry, rz, dx, dy, dz, inner) && t > enter && t < exit) {
            // in the hole: the shell is next where the ray leaves it
            t = exit;
            if (t > max || t > outerExit) return Double.POSITIVE_INFINITY;
            outNormal.set(cx - ox - dx * t, cy - oy - dy * t, cz - oz - dz * t).div(inner);
            return t;
        }
        if (t == 0) return inside(dx, dy, dz, outNormal);
        outNormal.set(rx + dx * t, ry + dy * t, rz + dz * t).div(outer);
        return t;
    }

    /**
     * A ray against the space between two upright cylinders turned and placed like {@code frame}: the first point
     * inside the outer one and outside the inner column.
     */
    double ring(CgPlaced frame, double inner, double outer, double halfHeight, double ox, double oy, double oz,
                double dx, double dy, double dz, double max, Vector3d outNormal) {
        frame.toLocal(ox, oy, oz, o);
        localDirection(frame, dx, dy, dz, d);
        if (!cylinderSpan(o, d, outer, halfHeight) || exit < 0 || enter > max) return Double.POSITIVE_INFINITY;
        double t = Math.max(enter, 0), outerEnter = enter, outerExit = exit;
        boolean cap = enterCap;
        if (span(d.x * d.x + d.z * d.z, o.x * d.x + o.z * d.z, o.x * o.x + o.z * o.z - inner * inner)
                && t > enter && t < exit) {
            t = exit;
            if (t > max || t > outerExit) return Double.POSITIVE_INFINITY;
            n.set(-(o.x + d.x * t), 0, -(o.z + d.z * t)).normalize();
            rotate(frame, n, outNormal);
            return t;
        }
        if (outerEnter <= 0) return inside(dx, dy, dz, outNormal);
        if (cap) n.set(0, Math.signum(o.y + d.y * t), 0);
        else n.set(o.x + d.x * t, 0, o.z + d.z * t).normalize();
        rotate(frame, n, outNormal);
        return t;
    }

    /** {@link #sphere}'s distance alone, for a capsule's ends. */
    private double sphereT(double cx, double cy, double cz, double r, double ox, double oy, double oz, double dx,
                           double dy, double dz) {
        if (!sphereSpan(ox - cx, oy - cy, oz - cz, dx, dy, dz, r) || exit < 0) return Double.POSITIVE_INFINITY;
        return Math.max(enter, 0);
    }

    /** Where a unit ray from {@code r} (relative to the centre) is inside a ball, into enter and exit. */
    private boolean sphereSpan(double rx, double ry, double rz, double dx, double dy, double dz, double radius) {
        return span(1, rx * dx + ry * dy + rz * dz, rx * rx + ry * ry + rz * rz - radius * radius);
    }

    /**
     * The roots of {@code a t^2 + 2 b t + c} into enter and exit, false with none. A ray parallel to the quadric's axis
     * ({@code a} zero) is inside for all t or none. The stable form, as Jolt's {@code FindRoot}.
     */
    private boolean span(double a, double b, double c) {
        if (a <= 1e-24) {
            if (c > 0) return false;
            enter = Double.NEGATIVE_INFINITY;
            exit = Double.POSITIVE_INFINITY;
            return true;
        }
        double disc = b * b - a * c;
        if (disc < 0) return false;
        double q = -(b + Math.copySign(Math.sqrt(disc), b));
        double t0 = q / a, t1 = q == 0 ? t0 : c / q;
        enter = Math.min(t0, t1);
        exit = Math.max(t0, t1);
        return true;
    }

    /** A local ray against an upright cylinder about Y: its side's span cut by its caps' slab. */
    private boolean cylinderSpan(Vector3d o, Vector3d d, double radius, double halfHeight) {
        if (!span(d.x * d.x + d.z * d.z, o.x * d.x + o.z * d.z, o.x * o.x + o.z * o.z - radius * radius)) {
            return false;
        }
        double sideEnter = enter, sideExit = exit, capEnter, capExit;
        if (d.y == 0) {
            if (Math.abs(o.y) > halfHeight) return false;
            capEnter = Double.NEGATIVE_INFINITY;
            capExit = Double.POSITIVE_INFINITY;
        } else {
            double t0 = (-halfHeight - o.y) / d.y, t1 = (halfHeight - o.y) / d.y;
            capEnter = Math.min(t0, t1);
            capExit = Math.max(t0, t1);
        }
        enterCap = capEnter > sideEnter;
        enter = Math.max(sideEnter, capEnter);
        exit = Math.min(sideExit, capExit);
        return enter <= exit;
    }

    private static void localDirection(CgPlaced s, double dx, double dy, double dz, Vector3d out) {
        out.set(s.m00 * dx + s.m10 * dy + s.m20 * dz, s.m01 * dx + s.m11 * dy + s.m21 * dz,
                s.m02 * dx + s.m12 * dy + s.m22 * dz);
    }

    private static void rotate(CgPlaced s, Vector3d local, Vector3d out) {
        out.set(s.m00 * local.x + s.m01 * local.y + s.m02 * local.z, s.m10 * local.x + s.m11 * local.y
                + s.m12 * local.z, s.m20 * local.x + s.m21 * local.y + s.m22 * local.z);
    }

    private static double inside(double dx, double dy, double dz, Vector3d outNormal) {
        outNormal.set(-dx, -dy, -dz);
        return 0;
    }
}
