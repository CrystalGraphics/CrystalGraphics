package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import org.joml.Vector3d;

import static com.crystalgraphics.shape.query.CgPlaced.BOX;
import static com.crystalgraphics.shape.query.CgPlaced.CAPSULE;
import static com.crystalgraphics.shape.query.CgPlaced.CYLINDER;
import static com.crystalgraphics.shape.query.CgPlaced.POINT;
import static com.crystalgraphics.shape.query.CgPlaced.SPHERE;

/**
 * Overlap of the shapes a support function cannot describe — a shell, a ring, a cone wider than 90 degrees — with a
 * convex one: each is a convex test and a second condition on how far the convex shape reaches.
 *
 * <ul>
 *   <li>Shell: the shape meets the outer ball and reaches at least the inner radius from the centre. Exact, as the
 *       distances over a connected shape form an interval.</li>
 *   <li>Ring: the shape meets the outer cylinder and reaches at least the inner radius from the axis.</li>
 *   <li>Wide cone: the shape meets the ball and is not wholly inside the open cone behind the apex.</li>
 * </ul>
 *
 * How far a box, sphere, point or capsule reaches is exact; anything else climbs its support function from a few
 * starts, which stops at the largest of the maxima it finds.
 */
final class CgNonConvex {

    private static final int MAX_STEPS = 32;

    private final CgNarrowPhase phase;
    private final CgPlaced probe = new CgPlaced(), frame = new CgPlaced();
    private final Vector3d p = new Vector3d(), q = new Vector3d(), l = new Vector3d();

    CgNonConvex(CgNarrowPhase phase) {
        this.phase = phase;
    }

    /** Whether a non-convex {@code shape} at {@code pose} meets the convex {@code x}. */
    boolean overlaps(CgShape shape, CgPose pose, CgPlaced x) {
        double cx = pose.x - phase.baseX, cy = pose.y - phase.baseY, cz = pose.z - phase.baseZ;
        if (shape instanceof CgShell) {
            CgShell s = (CgShell) shape;
            if (!phase.convexOverlap(probe.sphere(cx, cy, cz, s.outer()), x)) return false;
            return reachSq(x, cx, cy, cz) >= s.inner() * s.inner();
        }
        if (shape instanceof CgRing) {
            CgRing r = (CgRing) shape;
            probe.cylinder(pose, phase.baseX, phase.baseY, phase.baseZ, r.outer(), r.halfHeight());
            if (!phase.convexOverlap(probe, x)) return false;
            return radialReachSq(x, probe) >= r.inner() * r.inner();
        }
        if (shape instanceof CgCone) {
            CgCone c = (CgCone) shape;
            if (!phase.convexOverlap(probe.sphere(cx, cy, cz, c.range()), x)) return false;
            double back = Math.PI - c.halfAngle();
            return back <= 0 || !insideBackCone(x, frame.frame(pose, phase.baseX, phase.baseY, phase.baseZ), back);
        }
        throw new IllegalArgumentException("no overlap test for the non-convex " + shape);
    }

    /** The farthest {@code x} reaches from {@code c}, squared. */
    private double reachSq(CgPlaced x, double cx, double cy, double cz) {
        switch (x.kind) {
            case POINT:
                return lenSq(x.px - cx, x.py - cy, x.pz - cz);
            case SPHERE: {
                double r = Math.sqrt(lenSq(x.px - cx, x.py - cy, x.pz - cz)) + x.radius;
                return r * r;
            }
            case BOX: {
                // the farthest corner, one axis at a time
                x.toLocal(cx, cy, cz, l);
                double ex = Math.abs(l.x) + x.hx, ey = Math.abs(l.y) + x.hy, ez = Math.abs(l.z) + x.hz;
                return ex * ex + ey * ey + ez * ez;
            }
            case CAPSULE: {
                double r = Math.sqrt(Math.max(lenSq(x.ax - cx, x.ay - cy, x.az - cz),
                        lenSq(x.bx - cx, x.by - cy, x.bz - cz))) + x.radius;
                return r * r;
            }
            case CYLINDER: {
                x.toLocal(cx, cy, cz, l);
                double across = Math.sqrt(l.x * l.x + l.z * l.z) + x.radius, up = Math.abs(l.y) + x.hy;
                return across * across + up * up;
            }
            default: {
                double best = 0;
                // away from c first, which is a box's farthest corner at once; then the axes
                for (int start = 0; start < 7; start++) {
                    double sign = start <= 3 ? 1 : -1;
                    if (start == 0) x.support(x.px - cx, x.py - cy, x.pz - cz, p);
                    else x.support(start % 3 == 1 ? sign : 0, start % 3 == 2 ? sign : 0, start % 3 == 0 ? sign : 0, p);
                    double d = lenSq(p.x - cx, p.y - cy, p.z - cz);
                    // |p - c| only grows: the support along p - c is at least as far along it
                    for (int step = 0; step < MAX_STEPS; step++) {
                        x.support(p.x - cx, p.y - cy, p.z - cz, q);
                        double next = lenSq(q.x - cx, q.y - cy, q.z - cz);
                        if (next <= d) break;
                        d = next;
                        p.set(q);
                    }
                    best = Math.max(best, d);
                }
                return best;
            }
        }
    }

    /** The farthest {@code x} reaches from the axis of {@code ring} (its local Y), squared. */
    private double radialReachSq(CgPlaced x, CgPlaced ring) {
        switch (x.kind) {
            case POINT:
                ring.toLocal(x.px, x.py, x.pz, l);
                return l.x * l.x + l.z * l.z;
            case SPHERE: {
                ring.toLocal(x.px, x.py, x.pz, l);
                double r = Math.sqrt(l.x * l.x + l.z * l.z) + x.radius;
                return r * r;
            }
            case CAPSULE: {
                ring.toLocal(x.ax, x.ay, x.az, l);
                double a = l.x * l.x + l.z * l.z;
                ring.toLocal(x.bx, x.by, x.bz, l);
                double r = Math.sqrt(Math.max(a, l.x * l.x + l.z * l.z)) + x.radius;
                return r * r;
            }
            case BOX: {
                double best = 0;
                for (int corner = 0; corner < 8; corner++) {
                    x.toWorld((corner & 1) == 0 ? -x.hx : x.hx, (corner & 2) == 0 ? -x.hy : x.hy,
                            (corner & 4) == 0 ? -x.hz : x.hz, p);
                    ring.toLocal(p.x, p.y, p.z, l);
                    best = Math.max(best, l.x * l.x + l.z * l.z);
                }
                return best;
            }
            default: {
                double best = 0;
                ring.toLocal(x.px, x.py, x.pz, l);
                double centreX = l.x, centreZ = l.z;
                // out through the shape's own position first, then four ways round
                for (int start = 0; start < 5; start++) {
                    double lx = start == 0 ? centreX : start == 1 ? 1 : start == 3 ? -1 : 0,
                            lz = start == 0 ? centreZ : start == 2 ? 1 : start == 4 ? -1 : 0;
                    if (lx == 0 && lz == 0) continue;
                    double d = -1;
                    for (int step = 0; step < MAX_STEPS; step++) {
                        x.support(ring.m00 * lx + ring.m02 * lz, ring.m10 * lx + ring.m12 * lz,
                                ring.m20 * lx + ring.m22 * lz, p);
                        ring.toLocal(p.x, p.y, p.z, l);
                        double next = l.x * l.x + l.z * l.z;
                        if (next <= d) break;
                        d = next;
                        lx = l.x;
                        lz = l.z;
                    }
                    best = Math.max(best, d);
                }
                return best;
            }
        }
    }

    /**
     * Whether {@code x} lies wholly inside the open cone of half angle {@code back} about {@code -Z} of {@code cone}'s
     * frame, apex at its origin: {@code -p.z > |p| cos(back)} for every point.
     */
    private boolean insideBackCone(CgPlaced x, CgPlaced cone, double back) {
        double cos = Math.cos(back), sin = Math.sin(back);
        switch (x.kind) {
            case POINT:
                cone.toLocal(x.px, x.py, x.pz, l);
                return insideBack(l, cos);
            case SPHERE:
                cone.toLocal(x.px, x.py, x.pz, l);
                return ballInsideBack(l, x.radius, back);
            case CAPSULE:
                // the centres whose ball fits form a convex set, so both ends fitting is enough
                cone.toLocal(x.ax, x.ay, x.az, l);
                if (!ballInsideBack(l, x.radius, back)) return false;
                cone.toLocal(x.bx, x.by, x.bz, l);
                return ballInsideBack(l, x.radius, back);
            case BOX:
                for (int corner = 0; corner < 8; corner++) {
                    x.toWorld((corner & 1) == 0 ? -x.hx : x.hx, (corner & 2) == 0 ? -x.hy : x.hy,
                            (corner & 4) == 0 ? -x.hz : x.hz, p);
                    cone.toLocal(p.x, p.y, p.z, l);
                    if (!insideBack(l, cos)) return false;
                }
                return true;
            default:
                // inside iff behind every plane bounding the cone, normals (cos cos(phi), cos sin(phi), sin);
                // the support's azimuth names a plane it is at least as far in front of
                cone.toLocal(x.px, x.py, x.pz, l);
                double centrePhi = Math.atan2(l.y, l.x);
                // from the shape's own azimuth first, then four ways round
                for (int start = 0; start < 5; start++) {
                    double phi = start == 0 ? centrePhi : start * Math.PI / 2, h = Double.NEGATIVE_INFINITY;
                    for (int step = 0; step < MAX_STEPS; step++) {
                        double nx = cos * Math.cos(phi), ny = cos * Math.sin(phi);
                        x.support(cone.m00 * nx + cone.m01 * ny + cone.m02 * sin,
                                cone.m10 * nx + cone.m11 * ny + cone.m12 * sin,
                                cone.m20 * nx + cone.m21 * ny + cone.m22 * sin, p);
                        cone.toLocal(p.x, p.y, p.z, l);
                        double next = nx * l.x + ny * l.y + sin * l.z;
                        if (next >= 0) return false;
                        if (next <= h || l.x == 0 && l.y == 0) break;
                        h = next;
                        phi = Math.atan2(l.y, l.x);
                    }
                }
                return true;
        }
    }

    private static boolean insideBack(Vector3d v, double cos) {
        return -v.z > Math.sqrt(lenSq(v.x, v.y, v.z)) * cos;
    }

    private static boolean ballInsideBack(Vector3d c, double r, double back) {
        double len = Math.sqrt(lenSq(c.x, c.y, c.z));
        if (len <= r) return false;
        return Math.acos(Math.max(-1, Math.min(1, -c.z / len))) + Math.asin(r / len) < back;
    }

    private static double lenSq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
