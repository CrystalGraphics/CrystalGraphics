package com.crystalgraphics.shape.query;

import org.joml.Vector3d;

/**
 * How deep two convex sets overlap: the shortest move that separates them, and where each reaches into the other.
 * GJK finds whether they meet; EPA grows a hull inside their Minkowski difference until its face nearest the origin
 * lies on the surface. A port of Jolt Physics' {@code Jolt/Geometry/EPAPenetrationDepth.h} (MIT, Jorrit Rouwe), in
 * doubles.
 *
 * <pre>{@code
 * private final CgGjk gjk = new CgGjk();
 * private final CgEpa epa = new CgEpa(gjk);                   // shares the GJK it starts from
 *
 * if (epa.penetration(a, b, 1e-10, 1e-6, axis.set(1, 0, 0), onA, onB)) {
 *     double depth = axis.length();                           // axis points from a to b
 * }
 * }</pre>
 *
 * A round shape is cheaper as its core and a radius (a sphere's centre, a capsule's segment): only cores that overlap
 * need EPA, and a shallow contact comes straight from GJK's closest points.
 *
 * <pre>{@code
 * epa.penetration(ballCentre, 0.5, ball, box, 0, box, 1e-10, 1e-6, axis.set(1, 0, 0), onA, onB);
 * }</pre>
 *
 * <ul>
 *   <li>False when the sets do not meet, and when they only touch: a hull that cannot be grown around the origin has
 *       no depth worth a normal.</li>
 *   <li>Not thread safe; one per thread, with its own {@link CgGjk}.</li>
 * </ul>
 */
final class CgEpa {

    /** Once the hull has this many points without holding the origin, the overlap is too shallow to measure. */
    private static final int MAX_POINTS_TO_INCLUDE_ORIGIN = 32;
    /** Jolt's {@code IsNearZero()}, 1e-12 in floats. */
    private static final double NEAR_ZERO_SQ = 1e-20;
    private static final double COS_120 = -0.5, SIN_120 = Math.sqrt(3) / 2;

    private final CgGjk gjk;
    private final CgEpaHull hull = new CgEpaHull();
    private final Vector3d[] y = CgGjk.vectors(CgEpaHull.MAX_POINTS), p = CgGjk.vectors(CgEpaHull.MAX_POINTS),
            q = CgGjk.vectors(CgEpaHull.MAX_POINTS);
    private int count;
    private final Vector3d w2a = new Vector3d(), w2b = new Vector3d();

    CgEpa(CgGjk gjk) {
        this.gjk = gjk;
    }

    /**
     * Whether {@code a} and {@code b} overlap, and if so by how much.
     *
     * @param collisionTolerance   how near the sets may come and still count as meeting
     * @param penetrationTolerance when to stop refining, relative to the squared depth
     * @param ioV                  a non-zero vector to start from; on true the penetration from a to b, its length the
     *                             depth
     * @param outA                 on true, a's surface point farthest along the penetration
     * @param outB                 on true, b's surface point farthest against it; {@code outA - outB} is the
     *                             penetration
     */
    boolean penetration(CgSupport a, CgSupport b, double collisionTolerance, double penetrationTolerance,
                               Vector3d ioV, Vector3d outA, Vector3d outB) {
        return penetration(a, 0, a, b, 0, b, collisionTolerance, penetrationTolerance, ioV, outA, outB);
    }

    /**
     * {@link #penetration(CgSupport, CgSupport, double, double, Vector3d, Vector3d, Vector3d)} for shapes given as a
     * core grown by a radius, and whole.
     */
    boolean penetration(CgSupport aCore, double radiusA, CgSupport a, CgSupport bCore, double radiusB,
                               CgSupport b, double collisionTolerance, double penetrationTolerance, Vector3d ioV,
                               Vector3d outA, Vector3d outB) {
        double sumRadius = radiusA + radiusB;
        double distSq = gjk.closestPoints(aCore, bCore, collisionTolerance, sumRadius * sumRadius, ioV, outA, outB);
        if (distSq > sumRadius * sumRadius) return false;
        if (distSq > 0) {
            // the cores are apart and only the radii meet: the closest points, moved out to the surfaces
            double len = Math.sqrt(distSq);
            outA.add(ioV.x * radiusA / len, ioV.y * radiusA / len, ioV.z * radiusA / len);
            outB.sub(ioV.x * radiusB / len, ioV.y * radiusB / len, ioV.z * radiusB / len);
            ioV.mul((sumRadius - len) / len);
            return true;
        }
        return expand(a, b, penetrationTolerance, ioV, outA, outB);
    }

    private boolean expand(CgSupport a, CgSupport b, double tolerance, Vector3d outV, Vector3d outA, Vector3d outB) {
        count = gjk.simplex(y, p, q);
        if (count == 2) {
            double ax = y[1].x - y[0].x, ay = y[1].y - y[0].y, az = y[1].z - y[0].z;
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len > 0) {
                ax /= len;
                ay /= len;
                az /= len;
                // a perpendicular, then turned 120 degrees about the axis twice (Rodrigues, the axis term zero)
                double d1x, d1y, d1z;
                if (Math.abs(ax) > Math.abs(ay)) {
                    double l = Math.sqrt(ax * ax + az * az);
                    d1x = az / l;
                    d1y = 0;
                    d1z = -ax / l;
                } else {
                    double l = Math.sqrt(ay * ay + az * az);
                    d1x = 0;
                    d1y = az / l;
                    d1z = -ay / l;
                }
                double d2x = d1x * COS_120 + (ay * d1z - az * d1y) * SIN_120;
                double d2y = d1y * COS_120 + (az * d1x - ax * d1z) * SIN_120;
                double d2z = d1z * COS_120 + (ax * d1y - ay * d1x) * SIN_120;
                double d3x = d2x * COS_120 + (ay * d2z - az * d2y) * SIN_120;
                double d3y = d2y * COS_120 + (az * d2x - ax * d2z) * SIN_120;
                double d3z = d2z * COS_120 + (ax * d2y - ay * d2x) * SIN_120;
                add(a, b, d1x, d1y, d1z);
                add(a, b, d2x, d2y, d2z);
                add(a, b, d3x, d3y, d3z);
            } else {
                count = 1;
            }
        }
        if (count <= 1) {
            // a lone vertex is at the origin and of no use: a tetrahedron around it instead
            count = 0;
            add(a, b, 0, 1, 0);
            add(a, b, -1, -1, -1);
            add(a, b, 1, -1, -1);
            add(a, b, 0, -1, 1);
        }

        hull.initialize(y, 0, 1, 2);
        for (int i = 3; i < count; i++) {
            CgEpaHull.Triangle t = hull.findFacingTriangle(y[i]);
            // a failure leaves the hull unlinked: the shapes touch in a point or a plane
            if (t != null && !hull.addPoint(t, i, Double.MAX_VALUE)) return false;
        }

        // grow until the origin is inside
        for (;;) {
            CgEpaHull.Triangle t = hull.peekClosest();
            if (t.removed) {
                hull.popClosest();
                if (!hull.hasNextTriangle()) return false;
                hull.freeTriangle(t);
                continue;
            }
            if (t.closestLenSq >= 0) break;
            hull.popClosest();
            int index = add(a, b, t.normal.x, t.normal.y, t.normal.z);
            if (!t.isFacing(y[index]) || !hull.addPoint(t, index, Double.MAX_VALUE)) return false;
            hull.freeTriangle(t);
            if (count >= MAX_POINTS_TO_INCLUDE_ORIGIN) return false;
        }

        double closestDistSq = Double.MAX_VALUE;
        CgEpaHull.Triangle last = null;
        boolean flip = false;
        do {
            CgEpaHull.Triangle t = hull.popClosest();
            if (t.removed) {
                hull.freeTriangle(t);
                continue;
            }
            if (t.closestLenSq >= closestDistSq) break;
            if (last != null) hull.freeTriangle(last);
            last = t;

            // along the normal rather than toward the closest point: the same direction, with less round-off
            int index = add(a, b, t.normal.x, t.normal.y, t.normal.z);
            Vector3d w = y[index];
            double dot = t.normal.dot(w);
            // a separating axis after all
            if (dot < 0) return false;
            double distSq = dot * dot / t.normal.lengthSquared();
            if (distSq - t.closestLenSq < t.closestLenSq * tolerance) break;
            closestDistSq = Math.min(closestDistSq, distSq);
            // round-off says the point is not in front: as precise as it gets
            if (!t.isFacing(w)) break;
            if (!hull.addPoint(t, index, closestDistSq)) break;

            boolean defect = false;
            for (int i = 0; i < hull.addedCount; i++) {
                if (hull.added[i].isFacingOrigin()) {
                    defect = true;
                    break;
                }
            }
            if (defect) {
                // the origin may now be on the wrong side of t: if the overlap is smaller the other way, flip
                a.support(-t.normal.x, -t.normal.y, -t.normal.z, w2a);
                b.support(t.normal.x, t.normal.y, t.normal.z, w2b);
                double dot2 = -(t.normal.x * (w2a.x - w2b.x) + t.normal.y * (w2a.y - w2b.y)
                        + t.normal.z * (w2a.z - w2b.z));
                if (dot2 < dot) flip = true;
                break;
            }
        } while (hull.hasNextTriangle() && count < CgEpaHull.MAX_POINTS);

        // no triangle: the hull was flat, so no depth
        if (last == null) return false;
        double scale = last.centroid.dot(last.normal) / last.normal.lengthSquared();
        outV.set(last.normal).mul(scale);
        if (outV.lengthSquared() <= NEAR_ZERO_SQ) return false;
        if (flip) outV.negate();

        int i0 = last.start[0], i1 = last.start[1], i2 = last.start[2];
        double l0 = last.lambda0, l1 = last.lambda1;
        if (last.lambdaRelativeTo0) {
            barycentric(p[i0], p[i1], p[i2], l0, l1, outA);
            barycentric(q[i0], q[i1], q[i2], l0, l1, outB);
        } else {
            barycentric(p[i1], p[i0], p[i2], l0, l1, outA);
            barycentric(q[i1], q[i0], q[i2], l0, l1, outB);
        }
        return true;
    }

    /** {@code r + l0 (s - r) + l1 (t - r)}. */
    private static void barycentric(Vector3d r, Vector3d s, Vector3d t, double l0, double l1, Vector3d out) {
        out.set(r.x + l0 * (s.x - r.x) + l1 * (t.x - r.x), r.y + l0 * (s.y - r.y) + l1 * (t.y - r.y),
                r.z + l0 * (s.z - r.z) + l1 * (t.z - r.z));
    }

    /** A support point of a - b along the direction; answers its index. */
    private int add(CgSupport a, CgSupport b, double dx, double dy, double dz) {
        int i = count++;
        a.support(dx, dy, dz, p[i]);
        b.support(-dx, -dy, -dz, q[i]);
        y[i].set(p[i]).sub(q[i]);
        return i;
    }
}
