package com.crystalgraphics.shape.query;

import org.joml.Intersectiond;
import org.joml.Vector3d;

import static com.crystalgraphics.shape.query.CgPlaced.BOX;
import static com.crystalgraphics.shape.query.CgPlaced.CAPSULE;
import static com.crystalgraphics.shape.query.CgPlaced.CYLINDER;
import static com.crystalgraphics.shape.query.CgPlaced.POINT;
import static com.crystalgraphics.shape.query.CgPlaced.SPHERE;

/**
 * The pairs cheaper in closed form than through GJK, as Jolt's collision dispatch has them: round shapes against each
 * other and against boxes and cylinders, and box against box. A point is a sphere of radius 0 here.
 *
 * <p>Box against box is Ericson's separating-axis test (<i>Real-Time Collision Detection</i> 4.4.1), as Jolt Physics'
 * {@code Jolt/Geometry/OrientedBox.cpp} (MIT, Jorrit Rouwe) has it.
 */
final class CgClosedForms {

    /** {@link #overlap}'s answer for a pair it has no closed form for. */
    static final int UNKNOWN = -1;
    private static final double OBB_EPSILON = 1e-12;

    private final Vector3d onA = new Vector3d(), onB = new Vector3d(), local = new Vector3d();

    /** 1 when they overlap, 0 when not, {@link #UNKNOWN} for GJK to answer. */
    int overlap(CgPlaced a, CgPlaced b) {
        if (rank(a.kind) > rank(b.kind)) {
            CgPlaced t = a;
            a = b;
            b = t;
        }
        if (round(a.kind)) {
            if (round(b.kind) || b.kind == BOX || b.kind == CAPSULE || b.kind == CYLINDER) {
                double r = a.radius() + b.radius();
                return gapSq(a, b) <= r * r ? 1 : 0;
            }
        } else if (a.kind == BOX && b.kind == BOX) {
            return boxes(a, b) ? 1 : 0;
        } else if (a.kind == CAPSULE && b.kind == CAPSULE) {
            double r = a.radius() + b.radius();
            return capsulesSq(a, b) <= r * r ? 1 : 0;
        }
        return UNKNOWN;
    }

    /** The distance between them, 0 when they overlap, or NaN for GJK to answer. */
    double distance(CgPlaced a, CgPlaced b) {
        if (rank(a.kind) > rank(b.kind)) {
            CgPlaced t = a;
            a = b;
            b = t;
        }
        double sq;
        if (round(a.kind) && (round(b.kind) || b.kind == BOX || b.kind == CAPSULE || b.kind == CYLINDER)) {
            sq = gapSq(a, b);
        } else if (a.kind == CAPSULE && b.kind == CAPSULE) {
            sq = capsulesSq(a, b);
        } else {
            return Double.NaN;
        }
        return Math.max(0, Math.sqrt(sq) - a.radius() - b.radius());
    }

    private static boolean round(int kind) {
        return kind == POINT || kind == SPHERE;
    }

    /** Points and spheres first, so a pair is looked up one way round. */
    private static int rank(int kind) {
        return kind == POINT ? SPHERE : kind;
    }

    /** From a's centre to b's core (b less its radius, a box or cylinder whole), squared. */
    private double gapSq(CgPlaced a, CgPlaced b) {
        double cx = a.px, cy = a.py, cz = a.pz;
        switch (b.kind) {
            case BOX: {
                b.toLocal(cx, cy, cz, local);
                double dx = Math.max(Math.abs(local.x) - b.hx, 0), dy = Math.max(Math.abs(local.y) - b.hy, 0),
                        dz = Math.max(Math.abs(local.z) - b.hz, 0);
                return dx * dx + dy * dy + dz * dz;
            }
            case CAPSULE:
                return segmentSq(b, cx, cy, cz);
            case CYLINDER: {
                b.toLocal(cx, cy, cz, local);
                // the nearest point lies in the plane through the axis and the centre
                double dr = Math.max(Math.sqrt(local.x * local.x + local.z * local.z) - b.radius, 0);
                double dy = Math.max(Math.abs(local.y) - b.hy, 0);
                return dr * dr + dy * dy;
            }
            default: {
                double dx = b.px - cx, dy = b.py - cy, dz = b.pz - cz;
                return dx * dx + dy * dy + dz * dz;
            }
        }
    }

    private static double segmentSq(CgPlaced c, double x, double y, double z) {
        double ux = c.bx - c.ax, uy = c.by - c.ay, uz = c.bz - c.az;
        double lenSq = ux * ux + uy * uy + uz * uz;
        double t = lenSq == 0 ? 0 : ((x - c.ax) * ux + (y - c.ay) * uy + (z - c.az) * uz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double dx = c.ax + ux * t - x, dy = c.ay + uy * t - y, dz = c.az + uz * t - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private double capsulesSq(CgPlaced a, CgPlaced b) {
        return Intersectiond.findClosestPointsLineSegments(a.ax, a.ay, a.az, a.bx, a.by, a.bz, b.ax, b.ay, b.az, b.bx,
                b.by, b.bz, onA, onB);
    }

    private static boolean boxes(CgPlaced a, CgPlaced b) {
        if (a.axisAligned() && b.axisAligned()) {
            return Math.abs(a.px - b.px) <= a.hx + b.hx && Math.abs(a.py - b.py) <= a.hy + b.hy
                    && Math.abs(a.pz - b.pz) <= a.hz + b.hz;
        }
        // b in a's frame: r[i][j] = a's axis i . b's axis j, the axes being the matrices' columns
        double r00 = a.m00 * b.m00 + a.m10 * b.m10 + a.m20 * b.m20, r01 = a.m00 * b.m01 + a.m10 * b.m11 + a.m20 * b.m21,
                r02 = a.m00 * b.m02 + a.m10 * b.m12 + a.m20 * b.m22;
        double r10 = a.m01 * b.m00 + a.m11 * b.m10 + a.m21 * b.m20, r11 = a.m01 * b.m01 + a.m11 * b.m11 + a.m21 * b.m21,
                r12 = a.m01 * b.m02 + a.m11 * b.m12 + a.m21 * b.m22;
        double r20 = a.m02 * b.m00 + a.m12 * b.m10 + a.m22 * b.m20, r21 = a.m02 * b.m01 + a.m12 * b.m11 + a.m22 * b.m21,
                r22 = a.m02 * b.m02 + a.m12 * b.m12 + a.m22 * b.m22;
        double dx = b.px - a.px, dy = b.py - a.py, dz = b.pz - a.pz;
        double t0 = dx * a.m00 + dy * a.m10 + dz * a.m20, t1 = dx * a.m01 + dy * a.m11 + dz * a.m21,
                t2 = dx * a.m02 + dy * a.m12 + dz * a.m22;
        // the epsilon keeps a near-zero cross product of parallel edges from separating them
        double q00 = Math.abs(r00) + OBB_EPSILON, q01 = Math.abs(r01) + OBB_EPSILON, q02 = Math.abs(r02) + OBB_EPSILON;
        double q10 = Math.abs(r10) + OBB_EPSILON, q11 = Math.abs(r11) + OBB_EPSILON, q12 = Math.abs(r12) + OBB_EPSILON;
        double q20 = Math.abs(r20) + OBB_EPSILON, q21 = Math.abs(r21) + OBB_EPSILON, q22 = Math.abs(r22) + OBB_EPSILON;
        double a0 = a.hx, a1 = a.hy, a2 = a.hz, b0 = b.hx, b1 = b.hy, b2 = b.hz;

        // a's faces, b's faces, then the nine edge pairs
        if (Math.abs(t0) > a0 + b0 * q00 + b1 * q01 + b2 * q02) return false;
        if (Math.abs(t1) > a1 + b0 * q10 + b1 * q11 + b2 * q12) return false;
        if (Math.abs(t2) > a2 + b0 * q20 + b1 * q21 + b2 * q22) return false;
        if (Math.abs(t0 * r00 + t1 * r10 + t2 * r20) > a0 * q00 + a1 * q10 + a2 * q20 + b0) return false;
        if (Math.abs(t0 * r01 + t1 * r11 + t2 * r21) > a0 * q01 + a1 * q11 + a2 * q21 + b1) return false;
        if (Math.abs(t0 * r02 + t1 * r12 + t2 * r22) > a0 * q02 + a1 * q12 + a2 * q22 + b2) return false;
        if (Math.abs(t2 * r10 - t1 * r20) > a1 * q20 + a2 * q10 + b1 * q02 + b2 * q01) return false;
        if (Math.abs(t2 * r11 - t1 * r21) > a1 * q21 + a2 * q11 + b0 * q02 + b2 * q00) return false;
        if (Math.abs(t2 * r12 - t1 * r22) > a1 * q22 + a2 * q12 + b0 * q01 + b1 * q00) return false;
        if (Math.abs(t0 * r20 - t2 * r00) > a0 * q20 + a2 * q00 + b1 * q12 + b2 * q11) return false;
        if (Math.abs(t0 * r21 - t2 * r01) > a0 * q21 + a2 * q01 + b0 * q12 + b2 * q10) return false;
        if (Math.abs(t0 * r22 - t2 * r02) > a0 * q22 + a2 * q02 + b0 * q11 + b1 * q10) return false;
        if (Math.abs(t1 * r00 - t0 * r10) > a0 * q10 + a1 * q00 + b1 * q22 + b2 * q21) return false;
        if (Math.abs(t1 * r01 - t0 * r11) > a0 * q11 + a1 * q01 + b0 * q22 + b2 * q20) return false;
        return Math.abs(t1 * r02 - t0 * r12) <= a0 * q12 + a1 * q02 + b0 * q21 + b1 * q20;
    }
}
