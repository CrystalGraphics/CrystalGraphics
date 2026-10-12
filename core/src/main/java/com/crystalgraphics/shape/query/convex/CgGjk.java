package com.crystalgraphics.shape.query.convex;

import org.joml.Vector3d;

/**
 * Convex against convex by their support functions: whether two sets meet, their closest points, and where a moving
 * set first touches another. A port of Jolt Physics' {@code Jolt/Geometry/GJKClosestPoint.h} (MIT, Jorrit Rouwe,
 * https://github.com/jrouwe/JoltPhysics), after Gino van den Bergen's <i>A Fast and Robust GJK Implementation for
 * Collision Detection of Convex Objects</i> and <i>Ray Casting against General Convex Objects</i>, in doubles.
 *
 * <pre>{@code
 * private final CgGjk gjk = new CgGjk();                      // one per thread, reused
 *
 * boolean meet = gjk.intersects(a, b, 1e-10, axis.set(1, 0, 0));
 * double distanceSq = gjk.closestPoints(a, b, 1e-10, Double.MAX_VALUE, axis.set(1, 0, 0), onA, onB);
 * double t = gjk.castShape(a, mx, my, mz, 1e-10, b, 0, 0, 1, onA, onB, axis);   // POSITIVE_INFINITY: no touch
 * }</pre>
 *
 * <ul>
 *   <li>Not thread safe: it keeps its simplex between calls, which {@link CgEpa} starts from.</li>
 *   <li>Work near the origin: put both sets relative to a base point (CgShapes uses the first shape's position), so
 *       a world position far out costs no precision.</li>
 *   <li>Each loop stops after {@link #MAX_ITERATIONS}, answering what it has; Jolt's has no cap.</li>
 * </ul>
 */
public final class CgGjk {

    /** A cap Jolt does not have: a server tick must never spin on a degenerate pair. */
    public static final int MAX_ITERATIONS = 128;

    private static final double EPSILON = CgClosestPoint.EPSILON;

    private final Vector3d[] y = vectors(4), p = vectors(4), q = vectors(4);
    private int count;

    private final Vector3d sp = new Vector3d(), sq = new Vector3d(), closest = new Vector3d(), scratch = new Vector3d();
    private final Vector3d prevV = new Vector3d();
    private final double[] bary = new double[3];
    private int closestSet;
    private double closestLenSq;

    /**
     * The point of the simplex {@code y} nearest the origin, if nearer than {@code prevLenSq}: into {@code outV},
     * {@link #closestSet} and {@link #closestLenSq}. False leaves them alone.
     */
    private boolean closest(boolean lastPointPartOfClosestFeature, double prevLenSq, Vector3d outV) {
        int set;
        switch (count) {
            case 1:
                set = 0b0001;
                closest.set(y[0]);
                break;
            case 2:
                set = CgClosestPoint.closestOnLine(y[0], y[1], bary, closest);
                break;
            case 3:
                set = CgClosestPoint.closestOnTriangle(y[0], y[1], y[2], lastPointPartOfClosestFeature, closest);
                break;
            case 4:
                set = CgClosestPoint.closestOnTetrahedron(y[0], y[1], y[2], y[3], lastPointPartOfClosestFeature,
                        closest, scratch);
                break;
            default:
                throw new IllegalStateException("simplex of " + count);
        }
        double lenSq = closest.lengthSquared();
        // order matters: a NaN length fails here
        if (lenSq < prevLenSq) {
            outV.set(closest);
            closestLenSq = lenSq;
            closestSet = set;
            return true;
        }
        return false;
    }

    private double maxYLengthSq() {
        double m = y[0].lengthSquared();
        for (int i = 1; i < count; i++) m = Math.max(m, y[i].lengthSquared());
        return m;
    }

    private void keepY(int set) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((set & (1 << i)) != 0) y[n++].set(y[i]);
        }
        count = n;
    }

    private void keepP(int set) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((set & (1 << i)) != 0) p[n++].set(p[i]);
        }
        count = n;
    }

    private void keepPQ(int set) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((set & (1 << i)) != 0) {
                p[n].set(p[i]);
                q[n++].set(q[i]);
            }
        }
        count = n;
    }

    private void keepYPQ(int set) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((set & (1 << i)) != 0) {
                y[n].set(y[i]);
                p[n].set(p[i]);
                q[n++].set(q[i]);
            }
        }
        count = n;
    }

    private void pointsAB(Vector3d outA, Vector3d outB) {
        switch (count) {
            case 1:
                outA.set(p[0]);
                outB.set(q[0]);
                break;
            case 2:
                CgClosestPoint.barycentric(y[0], y[1], bary);
                weigh(p, 2, outA);
                weigh(q, 2, outB);
                break;
            case 3:
                CgClosestPoint.barycentric(y[0], y[1], y[2], bary);
                weigh(p, 3, outA);
                weigh(q, 3, outB);
                break;
            default:
                // a full simplex: the sets overlap and there are no closest points
                break;
        }
    }

    private void weigh(Vector3d[] points, int n, Vector3d out) {
        double x = 0, yy = 0, z = 0;
        for (int i = 0; i < n; i++) {
            x += bary[i] * points[i].x;
            yy += bary[i] * points[i].y;
            z += bary[i] * points[i].z;
        }
        out.set(x, yy, z);
    }

    /**
     * Whether {@code a} and {@code b} meet. {@code ioV} starts the search (any vector; zero when unknown); on false it
     * is a separating axis from a to b, of no particular length, and on true zero.
     *
     * @param tolerance how near the sets may come and still count as meeting
     */
    public boolean intersects(CgSupport a, CgSupport b, double tolerance, Vector3d ioV) {
        double toleranceSq = tolerance * tolerance;
        count = 0;
        double prevLenSq = Double.MAX_VALUE;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            a.support(ioV.x, ioV.y, ioV.z, sp);
            b.support(-ioV.x, -ioV.y, -ioV.z, sq);
            double wx = sp.x - sq.x, wy = sp.y - sq.y, wz = sp.z - sq.z;
            // the Minkowski difference's support is behind v: v separates them
            if (ioV.x * wx + ioV.y * wy + ioV.z * wz < 0) return false;

            y[count++].set(wx, wy, wz);
            if (!closest(true, prevLenSq, ioV)) return false;
            double lenSq = closestLenSq;
            if (closestSet == 0b1111 || lenSq <= toleranceSq || lenSq <= EPSILON * maxYLengthSq()) {
                ioV.zero();
                return true;
            }
            // the next axis is -v; set before the convergence test, which answers -v as the separating axis
            ioV.negate();
            if (prevLenSq - lenSq <= EPSILON * prevLenSq) return false;
            prevLenSq = lenSq;
            keepY(closestSet);
        }
        return false;
    }

    /**
     * The squared distance between {@code a} and {@code b}, with their closest points; 0 when they meet (the points
     * are then meaningless), and {@link Double#MAX_VALUE} once they are found farther apart than
     * {@code sqrt(maxDistanceSq)}.
     *
     * @param ioV a non-zero vector to start from; on return the separating axis from a to b, its length the distance
     *            when the answer is below {@code MAX_VALUE}, or zero when they meet
     */
    public double closestPoints(CgSupport a, CgSupport b, double tolerance, double maxDistanceSq, Vector3d ioV,
                                Vector3d outA, Vector3d outB) {
        double toleranceSq = tolerance * tolerance;
        count = 0;
        double lenSq = ioV.lengthSquared();
        double prevLenSq = Double.MAX_VALUE;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            a.support(ioV.x, ioV.y, ioV.z, sp);
            b.support(-ioV.x, -ioV.y, -ioV.z, sq);
            double wx = sp.x - sq.x, wy = sp.y - sq.y, wz = sp.z - sq.z;
            double dot = ioV.x * wx + ioV.y * wy + ioV.z * wz;
            if (dot < 0 && dot * dot > lenSq * maxDistanceSq) return Double.MAX_VALUE;

            y[count].set(wx, wy, wz);
            p[count].set(sp);
            q[count++].set(sq);
            if (!closest(true, prevLenSq, ioV)) {
                count--;
                break;
            }
            lenSq = closestLenSq;
            if (closestSet == 0b1111) {
                ioV.zero();
                lenSq = 0;
                break;
            }
            keepYPQ(closestSet);
            if (lenSq <= toleranceSq || lenSq <= EPSILON * maxYLengthSq()) {
                ioV.zero();
                lenSq = 0;
                break;
            }
            ioV.negate();
            if (prevLenSq - lenSq <= EPSILON * prevLenSq) break;
            prevLenSq = lenSq;
        }
        pointsAB(outA, outB);
        return lenSq;
    }

    /**
     * The simplex {@link #closestPoints} ended on, into the first {@code n} entries of each array; answers {@code n}.
     * When it answered 0 the simplex holds the origin.
     */
    public int simplex(Vector3d[] outY, Vector3d[] outP, Vector3d[] outQ) {
        for (int i = 0; i < count; i++) {
            outY[i].set(y[i]);
            outP[i].set(p[i]);
            outQ[i].set(q[i]);
        }
        return count;
    }

    /**
     * Where {@code a}, moving by {@code lambda * (dx, dy, dz)} for lambda in {@code [0, maxLambda)}, first comes within
     * {@code radiusA + radiusB} of {@code b}: that lambda, or {@link Double#POSITIVE_INFINITY} when it does not.
     *
     * @param outA        the contact point on a, at the answer
     * @param outB        the contact point on b
     * @param outAxis     from a to b along their separation; near zero (and the direction unreliable) when they already
     *                    overlap at lambda 0
     */
    public double castShape(CgSupport a, double dx, double dy, double dz, double tolerance, CgSupport b,
                            double radiusA, double radiusB, double maxLambda, Vector3d outA, Vector3d outB,
                            Vector3d outAxis) {
        double toleranceSq = tolerance * tolerance;
        double sumRadius = radiusA + radiusB;
        count = 0;
        double lambda = 0;
        double xx = 0, xy = 0, xz = 0;
        // v = x - support of the difference b - a, with x at zero
        b.support(0, 0, 0, sq);
        a.support(0, 0, 0, sp);
        Vector3d v = outAxis.set(sp.x - sq.x, sp.y - sq.y, sp.z - sq.z);
        double lenSq = Double.MAX_VALUE;
        boolean allowRestart = false;
        prevV.zero();

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            // the difference b - a: a moves, so b's back meets a's front
            a.support(-v.x, -v.y, -v.z, sp);
            b.support(v.x, v.y, v.z, sq);
            double wx = xx - (sq.x - sp.x), wy = xy - (sq.y - sp.y), wz = xz - (sq.z - sp.z);
            // the radii are not in p and q, so the axis at the end stays good; account for them in v . w
            double vDotW = v.x * wx + v.y * wy + v.z * wz - sumRadius * v.length();
            if (vDotW > 0) {
                double vDotR = v.x * dx + v.y * dy + v.z * dz;
                // moving along the normal: passed b. The epsilon keeps the division from overflowing
                if (vDotR >= -1.0e-18) return Double.POSITIVE_INFINITY;
                double old = lambda;
                lambda -= vDotW / vDotR;
                if (lambda >= maxLambda) return Double.POSITIVE_INFINITY;
                // separated here, so not overlapping at lambda 0: stop once the radii apart
                double t = tolerance + sumRadius;
                toleranceSq = t * t;
                // Jolt takes a lambda too small to change as a hit; in doubles that happens while v is still
                // turning, short of the contact, so the simplex goes on refining instead
                if (old != lambda) {
                    xx = lambda * dx;
                    xy = lambda * dy;
                    xz = lambda * dz;
                    lenSq = Double.MAX_VALUE;
                    // the simplex was built for another x; rebuilding once keeps round-off from piling up
                    allowRestart = true;
                }
            }

            p[count].set(sp);
            q[count++].set(sq);
            for (int i = 0; i < count; i++) {
                y[i].set(xx - (q[i].x - p[i].x), xy - (q[i].y - p[i].y), xz - (q[i].z - p[i].z));
            }
            if (!closest(false, lenSq, v)) {
                if (!allowRestart) {
                    // contact points from the last simplex that was closer
                    count--;
                    break;
                }
                allowRestart = false;
                p[0].set(sp);
                q[0].set(sq);
                count = 1;
                v.set(xx - sq.x, xy - sq.y, xz - sq.z);
                lenSq = Double.MAX_VALUE;
                continue;
            }
            if (closestSet == 0b1111) break;
            lenSq = closestLenSq;
            keepPQ(closestSet);
            if (lenSq <= toleranceSq) break;
            prevV.set(v);
        }

        for (int i = 0; i < count; i++) {
            y[i].set(xx - (q[i].x - p[i].x), xy - (q[i].y - p[i].y), xz - (q[i].z - p[i].z));
        }
        double vLen = v.length();
        double nx = vLen > 0 ? v.x / vLen : 0, ny = vLen > 0 ? v.y / vLen : 0, nz = vLen > 0 ? v.z / vLen : 0;
        // when lambda > 0 the points coincide: b's has fewer terms, so it is taken for both
        switch (count) {
            case 0:
                outA.set(xx, xy, xz);
                outB.set(xx, xy, xz);
                break;
            case 1:
                outB.set(q[0]).add(radiusB * nx, radiusB * ny, radiusB * nz);
                if (lambda > 0) outA.set(outB);
                else outA.set(p[0]).sub(radiusA * nx, radiusA * ny, radiusA * nz);
                break;
            case 2:
                CgClosestPoint.barycentric(y[0], y[1], bary);
                weigh(q, 2, outB);
                outB.add(radiusB * nx, radiusB * ny, radiusB * nz);
                if (lambda > 0) {
                    outA.set(outB);
                } else {
                    weigh(p, 2, outA);
                    outA.sub(radiusA * nx, radiusA * ny, radiusA * nz);
                }
                break;
            default:
                // a full simplex has no proper contact point: take the triangle of the iteration before
                CgClosestPoint.barycentric(y[0], y[1], y[2], bary);
                weigh(q, 3, outB);
                outB.add(radiusB * nx, radiusB * ny, radiusB * nz);
                if (lambda > 0) {
                    outA.set(outB);
                } else {
                    weigh(p, 3, outA);
                    outA.sub(radiusA * nx, radiusA * ny, radiusA * nz);
                }
                break;
        }
        // with radii v is the axis; without, v has shrunk to nothing and the previous one stands in
        if (sumRadius > 0) outAxis.set(-v.x, -v.y, -v.z);
        else outAxis.set(-prevV.x, -prevV.y, -prevV.z);
        return lambda;
    }

    static Vector3d[] vectors(int n) {
        Vector3d[] out = new Vector3d[n];
        for (int i = 0; i < n; i++) out[i] = new Vector3d();
        return out;
    }
}
