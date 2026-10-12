package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgConvex;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.shape.query.convex.CgGjk;
import com.sun.management.ThreadMXBean;
import org.joml.Vector3d;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * The narrow phase against answers worked out independently: membership tests written here, analytic depths and times
 * of impact, bisection on overlap, and its own closed forms held to GJK.
 */
public class CgShapesTest {

    /** A world far from the origin, where a float would have lost the centimetres. */
    private static final double FAR = 3.0e7;

    private static final CgShape SPHERE = CgSphere.of(1.2);
    private static final CgShape BOX = CgBox.of(1, 0.5, 0.8);
    private static final CgShape CAPSULE = CgCapsule.between(-0.8, 0.2, -0.3, 0.9, -0.1, 0.6, 0.45);
    private static final CgShape CYLINDER = CgCylinder.of(0.9, 0.6);
    private static final CgShape CONE = CgCone.of(2, Math.toRadians(35));
    /** A box by its corners: GJK with no closed form. */
    private static final CgShape HULL = CgConvex.hull(new double[]{-1, -0.5, -0.8, 1, -0.5, -0.8, -1, 0.5, -0.8,
            1, 0.5, -0.8, -1, -0.5, 0.8, 1, -0.5, 0.8, -1, 0.5, 0.8, 1, 0.5, 0.8});
    /** An ellipsoid by its support. */
    private static final CgShape ELLIPSOID = CgConvex.of((dx, dy, dz, out) -> {
        double ax = 1.3, ay = 0.6, az = 0.9;
        double len = Math.sqrt(ax * ax * dx * dx + ay * ay * dy * dy + az * az * dz * dz);
        return len == 0 ? out.set(0, 0, az) : out.set(ax * ax * dx / len, ay * ay * dy / len, az * az * dz / len);
    });
    private static final CgShape SHELL = CgShell.of(1.1, 1.6);
    private static final CgShape RING = CgRing.of(0.8, 1.6, 0.4);
    private static final CgShape WIDE_CONE = CgCone.of(1.8, Math.toRadians(130));
    private static final CgShape COMPOUND = CgCompound.builder()
            .add(CgCapsule.of(0.6, 0.2), 0, 0, 0.6)
            .add(CgBox.of(0.4, 0.25, 0.25), new CgPose().at(0, 0, 1.4).facing(Math.toRadians(25), Math.toRadians(10)))
            .build();

    private static final CgShape[] CONVEX = {SPHERE, BOX, CAPSULE, CYLINDER, CONE, HULL, ELLIPSOID, COMPOUND};
    private static final CgShape[] ALL = {SPHERE, BOX, CAPSULE, CYLINDER, CONE, HULL, ELLIPSOID, COMPOUND, SHELL, RING,
            WIDE_CONE};

    private final Random random = new Random(20261012);
    private final Vector3d scratch = new Vector3d();

    // ---- membership, written from each shape's definition

    private static boolean inside(CgShape s, double x, double y, double z, double eps) {
        if (s instanceof CgSphere) return len(x, y, z) <= ((CgSphere) s).radius() + eps;
        if (s instanceof CgBox) {
            CgBox b = (CgBox) s;
            return Math.abs(x) <= b.halfX() + eps && Math.abs(y) <= b.halfY() + eps && Math.abs(z) <= b.halfZ() + eps;
        }
        if (s instanceof CgCapsule) {
            CgCapsule c = (CgCapsule) s;
            Vector3d a = c.a(new Vector3d()), b = c.b(new Vector3d());
            return segmentDistance(x, y, z, a, b) <= c.radius() + eps;
        }
        if (s instanceof CgCylinder) {
            CgCylinder c = (CgCylinder) s;
            return len(x, 0, z) <= c.radius() + eps && Math.abs(y) <= c.halfHeight() + eps;
        }
        if (s instanceof CgCone) {
            CgCone c = (CgCone) s;
            double l = len(x, y, z);
            if (l <= eps) return true;
            if (l > c.range() + eps) return false;
            double angle = Math.acos(Math.max(-1, Math.min(1, z / l)));
            // within eps of the lateral surface counts
            return angle <= c.halfAngle() || l * Math.sin(Math.min(Math.PI / 2, angle - c.halfAngle())) <= eps;
        }
        if (s instanceof CgShell) {
            CgShell sh = (CgShell) s;
            double l = len(x, y, z);
            return l >= sh.inner() - eps && l <= sh.outer() + eps;
        }
        if (s instanceof CgRing) {
            CgRing r = (CgRing) s;
            double radial = len(x, 0, z);
            return radial >= r.inner() - eps && radial <= r.outer() + eps && Math.abs(y) <= r.halfHeight() + eps;
        }
        if (s == HULL) return inside(BOX, x, y, z, eps);
        if (s == ELLIPSOID) {
            double ax = 1.3 + eps, ay = 0.6 + eps, az = 0.9 + eps;
            return x * x / (ax * ax) + y * y / (ay * ay) + z * z / (az * az) <= 1;
        }
        if (s instanceof CgCompound) {
            CgCompound c = (CgCompound) s;
            CgPose p = new CgPose();
            Vector3d l = new Vector3d();
            for (int i = 0; i < c.size(); i++) {
                c.pose(i, p).toLocal(x, y, z, l);
                if (inside(c.shape(i), l.x, l.y, l.z, eps)) return true;
            }
            return false;
        }
        throw new AssertionError("no membership for " + s);
    }

    private static boolean insideAt(CgShape s, CgPose pose, Vector3d world, double eps) {
        Vector3d l = pose.toLocal(world.x, world.y, world.z, new Vector3d());
        return inside(s, l.x, l.y, l.z, eps);
    }

    private static double len(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double segmentDistance(double x, double y, double z, Vector3d a, Vector3d b) {
        double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z, lenSq = ux * ux + uy * uy + uz * uz;
        double t = lenSq == 0 ? 0 : ((x - a.x) * ux + (y - a.y) * uy + (z - a.z) * uz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return len(x - a.x - ux * t, y - a.y - uy * t, z - a.z - uz * t);
    }

    /** A point of {@code s} at random, in the world. */
    private Vector3d sample(CgShape s, CgPose pose, Vector3d out) {
        CgAABB box = s.bounds(new CgAABB());
        for (int tries = 0; tries < 10_000; tries++) {
            double x = box.minX() + random.nextDouble() * (box.maxX() - box.minX());
            double y = box.minY() + random.nextDouble() * (box.maxY() - box.minY());
            double z = box.minZ() + random.nextDouble() * (box.maxZ() - box.minZ());
            if (inside(s, x, y, z, 0)) return pose.toWorld(x, y, z, out);
        }
        throw new AssertionError("no sample inside " + s);
    }

    private CgPose randomPose(double base, double spread) {
        double u1 = random.nextDouble(), u2 = random.nextDouble() * 2 * Math.PI, u3 = random.nextDouble() * 2 * Math.PI;
        double a = Math.sqrt(1 - u1), b = Math.sqrt(u1);
        return new CgPose().at(base + (random.nextDouble() - 0.5) * spread, base + (random.nextDouble() - 0.5) * spread,
                base + (random.nextDouble() - 0.5) * spread).rotation(a * Math.sin(u2), a * Math.cos(u2),
                b * Math.sin(u3), b * Math.cos(u3));
    }

    // ---- closed forms against GJK

    @Test
    public void aPlaneIsSolidBehindAndEndsAtItsRadius() {
        CgShape floor = CgPlane.of(0, 1, 0, 10);
        CgPose at = new CgPose(), box = new CgPose();
        CgShape cube = CgBox.of(0.5, 0.5, 0.5);
        assertTrue(CgShapes.overlaps(floor, at, cube, box.at(3, 0.4, -2)));
        assertFalse(CgShapes.overlaps(floor, at, cube, box.at(3, 0.6, -2)));
        assertFalse(CgShapes.overlaps(floor, at, cube, box.at(11, 0, 0)));
        assertFalse(CgShapes.overlaps(floor, at, cube, box.at(0, -11, 0)));
        CgCastHit hit = new CgCastHit();
        assertTrue(CgShapes.castRay(floor, at.at(0, 0, 0), 2, 5, 1, 0, -1, 0, 20, hit));
        assertEquals(5, hit.distance, 1e-6);
    }

    @Test
    public void closedFormsAgreeWithGjk() {
        CgShape[] closed = {SPHERE, BOX, CAPSULE, CYLINDER, CgShell.of(0, 0.7), CgRing.of(0, 1.1, 0.3)};
        CgClosedForms forms = new CgClosedForms();
        CgGjk gjk = new CgGjk();
        CgPlaced a = new CgPlaced(), b = new CgPlaced();
        Vector3d v = new Vector3d(), onA = new Vector3d(), onB = new Vector3d();
        int compared = 0;
        for (CgShape sa : closed) {
            for (CgShape sb : closed) {
                for (int i = 0; i < 3000; i++) {
                    CgPose pa = randomPose(0, 4), pb = randomPose(0, 4);
                    a.set(sa, pa, 0, 0, 0);
                    b.set(sb, pb, 0, 0, 0);
                    // the whole shapes, no radius: GJK's own answer
                    double gjkDistance = Math.sqrt(gjk.closestPoints(a, b, 1e-12, Double.MAX_VALUE,
                            v.set(1, 0.3, 0.2), onA, onB));
                    int overlap = forms.overlap(a, b);
                    if (overlap != CgClosedForms.UNKNOWN && Math.abs(gjkDistance) > 1e-6) {
                        assertEquals(sa + " / " + sb + " at " + pa + ", " + pb, gjkDistance == 0, overlap == 1);
                        compared++;
                    }
                    double distance = forms.distance(a, b);
                    if (!Double.isNaN(distance)) {
                        assertEquals(sa + " / " + sb + " distance", gjkDistance, distance, 1e-6);
                    }
                }
            }
        }
        assertTrue("compared " + compared, compared > 40_000);
    }

    // ---- every pair against sampling

    @Test
    public void aSampledCommonPointMeansOverlap() {
        Vector3d p = new Vector3d();
        int found = 0;
        for (CgShape sa : ALL) {
            for (CgShape sb : ALL) {
                if (!sa.isConvex() && !(sa instanceof CgCompound) && !sb.isConvex() && !(sb instanceof CgCompound)) {
                    continue;
                }
                for (int i = 0; i < 150; i++) {
                    CgPose pa = randomPose(FAR, 3.5), pb = randomPose(FAR, 3.5);
                    boolean claimed = CgShapes.overlaps(sa, pa, sb, pb);
                    assertEquals("symmetric: " + sa + " / " + sb, claimed, CgShapes.overlaps(sb, pb, sa, pa));
                    for (int k = 0; k < 400; k++) {
                        if (insideAt(sb, pb, sample(sa, pa, p), -1e-6)) {
                            assertTrue(sa + " / " + sb + " share " + p + " at " + pa + ", " + pb, claimed);
                            found++;
                            break;
                        }
                    }
                }
            }
        }
        assertTrue("found " + found, found > 2000);
    }

    @Test
    public void convexOverlapsHaveAWitness() {
        // each contact point on its own shape, the two a depth apart, and that depth the way out
        CgContact contact = new CgContact();
        int checked = 0;
        for (CgShape sa : CONVEX) {
            for (CgShape sb : CONVEX) {
                for (int i = 0; i < 400; i++) {
                    CgPose pa = randomPose(FAR, 3), pb = randomPose(FAR, 3);
                    boolean overlaps = CgShapes.overlaps(sa, pa, sb, pb);
                    boolean contacts = CgShapes.contact(sa, pa, sb, pb, contact);
                    if (!overlaps) {
                        assertFalse(sa + " / " + sb + ": a contact without an overlap", contacts);
                        assertTrue(CgShapes.distance(sa, pa, sb, pb) > 0);
                        continue;
                    }
                    assertEquals(0, CgShapes.distance(sa, pa, sb, pb), 0);
                    if (!contacts) continue;           // touching only
                    String at = sa + " / " + sb + " at " + pa + ", " + pb + ": " + contact;
                    assertTrue(at, insideAt(sa, pa, contact.pointA, 1e-5));
                    assertTrue(at, insideAt(sb, pb, contact.pointB, 1e-5));
                    assertEquals(at, 1, contact.normal.length(), 1e-9);
                    assertEquals(at, contact.depth, contact.pointA.distance(contact.pointB), 1e-5);
                    checked++;
                    // a compound's deepest child says nothing of how to clear the others
                    if (sa == COMPOUND || sb == COMPOUND) continue;
                    // moving b by the depth along the normal separates them; less than that does not. EPA's hull
                    // between two curved shapes stops short of the surface by about 1e-4 of the depth
                    double slack = Math.max(1e-4, contact.depth * 1e-3);
                    CgPose moved = new CgPose(pb);
                    moved.at(pb.x + contact.normal.x * (contact.depth + slack), pb.y + contact.normal.y
                            * (contact.depth + slack), pb.z + contact.normal.z * (contact.depth + slack));
                    assertFalse(at, CgShapes.overlaps(sa, pa, sb, moved));
                    if (contact.depth > 10 * slack) {
                        moved.at(pb.x + contact.normal.x * (contact.depth - slack), pb.y + contact.normal.y
                                * (contact.depth - slack), pb.z + contact.normal.z * (contact.depth - slack));
                        assertTrue(at, CgShapes.overlaps(sa, pa, sb, moved));
                    }
                }
            }
        }
        assertTrue("checked " + checked, checked > 5000);
    }

    @Test
    public void contactDepthIsTheAnalyticOne() {
        CgContact contact = new CgContact();
        for (int i = 0; i < 2000; i++) {
            CgPose pa = randomPose(FAR, 2), pb = randomPose(FAR, 2);
            double d = len(pb.x - pa.x, pb.y - pa.y, pb.z - pa.z);
            if (d < 1e-3 || d > 2.4) continue;
            assertTrue(CgShapes.contact(SPHERE, pa, SPHERE, pb, contact));
            assertEquals(2.4 - d, contact.depth, 1e-9);
            assertEquals((pb.x - pa.x) / d, contact.normal.x, 1e-9);
        }
        // a ball pressed into an axis-aligned box's face, its centre outside
        CgPose box = new CgPose().at(FAR, FAR, FAR);
        for (int i = 0; i < 2000; i++) {
            double x = (random.nextDouble() - 0.5) * 1.6, z = (random.nextDouble() - 0.5) * 1.2,
                    y = 0.5 + random.nextDouble() * 1.1;
            CgPose ball = new CgPose().at(FAR + x, FAR + y, FAR + z);
            assertTrue(CgShapes.contact(BOX, box, SPHERE, ball, contact));
            assertEquals(1.2 - (y - 0.5), contact.depth, 1e-7);
            assertEquals(1, contact.normal.y, 1e-7);
        }
        // two boxes, the shallowest axis wins
        CgPose a = new CgPose().at(FAR, FAR, FAR), b = new CgPose().at(FAR + 1.7, FAR + 0.2, FAR - 0.1);
        assertTrue(CgShapes.contact(BOX, a, BOX, b, contact));
        assertEquals(0.3, contact.depth, 1e-6);
        assertEquals(1, contact.normal.x, 1e-6);
    }

    // ---- casts and rays

    @Test
    public void castsMatchBisectionOnOverlap() {
        CgCastHit hit = new CgCastHit();
        int hits = 0;
        for (CgShape sa : CONVEX) {
            for (CgShape sb : CONVEX) {
                for (int i = 0; i < 200; i++) {
                    CgPose pa = randomPose(FAR, 2), pb = randomPose(FAR, 2);
                    pa.at(pa.x - 4, pa.y, pa.z);
                    if (CgShapes.overlaps(sa, pa, sb, pb)) continue;
                    double mx = 8 + random.nextDouble(), my = (random.nextDouble() - 0.5) * 4,
                            mz = (random.nextDouble() - 0.5) * 4;
                    double expected = firstOverlap(sa, pa, mx, my, mz, sb, pb);
                    boolean cast = CgShapes.cast(sa, pa, mx, my, mz, sb, pb, hit);
                    String at = sa + " / " + sb + " at " + pa + ", " + pb + " by " + mx + ", " + my + ", " + mz;
                    if (Double.isNaN(expected)) {
                        // a graze between two of the scan's steps can be missed by it, not by the cast
                        if (cast) assertTrue(at + ": " + hit, grazes(sa, pa, sb, pb, mx, my, mz, hit.fraction));
                        continue;
                    }
                    assertTrue(at, cast);
                    // apart only where the motion grazes: a hair of distance is then a long way along it, or the scan
                    // stepped over a thin child; either way they touch at both
                    if (Math.abs(hit.fraction - expected) > 1e-7) {
                        assertTrue(at + ": " + hit, grazes(sa, pa, sb, pb, mx, my, mz, hit.fraction)
                                && grazes(sa, pa, sb, pb, mx, my, mz, expected));
                    }
                    assertEquals(at, hit.fraction * len(mx, my, mz), hit.distance, 1e-9);
                    hits++;
                }
            }
        }
        assertTrue("hits " + hits, hits > 3000);
    }

    /** The first fraction of the motion at which they overlap, by a scan and bisection; NaN for none. */
    private static double firstOverlap(CgShape sa, CgPose pa, double mx, double my, double mz, CgShape sb, CgPose pb) {
        CgPose at = new CgPose(pa);
        double prev = 0;
        for (int step = 1; step <= 400; step++) {
            double t = step / 400.0;
            if (CgShapes.overlaps(sa, at.at(pa.x + mx * t, pa.y + my * t, pa.z + mz * t), sb, pb)) {
                double lo = prev, hi = t;
                for (int k = 0; k < 60; k++) {
                    double mid = (lo + hi) / 2;
                    at.at(pa.x + mx * mid, pa.y + my * mid, pa.z + mz * mid);
                    if (CgShapes.overlaps(sa, at, sb, pb)) hi = mid;
                    else lo = mid;
                }
                return hi;
            }
            prev = t;
        }
        return Double.NaN;
    }

    private static boolean grazes(CgShape sa, CgPose pa, CgShape sb, CgPose pb, double mx, double my, double mz,
                                  double t) {
        CgPose at = new CgPose(pa).at(pa.x + mx * t, pa.y + my * t, pa.z + mz * t);
        return CgShapes.distance(sa, at, sb, pb) < 1e-6;
    }

    @Test
    public void castsMatchTheAnalyticTimeOfImpact() {
        CgCastHit hit = new CgCastHit();
        for (int i = 0; i < 2000; i++) {
            CgPose pa = randomPose(FAR, 2), pb = randomPose(FAR, 2);
            pa.at(pa.x - 6, pa.y, pa.z);
            double mx = 12, my = (random.nextDouble() - 0.5) * 3, mz = (random.nextDouble() - 0.5) * 3;
            // |c + t m| = r, c = a - b
            double cx = pa.x - pb.x, cy = pa.y - pb.y, cz = pa.z - pb.z, r = 2.4;
            double qa = mx * mx + my * my + mz * mz, qb = cx * mx + cy * my + cz * mz,
                    qc = cx * cx + cy * cy + cz * cz - r * r;
            double disc = qb * qb - qa * qc;
            boolean cast = CgShapes.cast(SPHERE, pa, mx, my, mz, SPHERE, pb, hit);
            if (disc < 0 || (-qb - Math.sqrt(disc)) / qa > 1) {
                assertFalse(cast);
                continue;
            }
            assertTrue(cast);
            assertEquals((-qb - Math.sqrt(disc)) / qa, hit.fraction, 1e-9);
            // the point is on b's surface and the normal faces the mover
            assertEquals(1.2, hit.point.distance(pb.x, pb.y, pb.z), 1e-6);
            assertEquals(1, len(hit.normal.x, hit.normal.y, hit.normal.z), 1e-9);
            assertTrue(hit.normal.x * mx + hit.normal.y * my + hit.normal.z * mz < 0);
        }
    }

    @Test
    public void raysMatchAScanOfMembership() {
        CgCastHit hit = new CgCastHit();
        Vector3d p = new Vector3d();
        CgShape[] shapes = {SPHERE, BOX, CAPSULE, CYLINDER, CONE, HULL, ELLIPSOID, COMPOUND, SHELL, RING};
        int hits = 0;
        for (CgShape s : shapes) {
            for (int i = 0; i < 1500; i++) {
                CgPose pose = randomPose(FAR, 1);
                double ox = pose.x - 4, oy = pose.y + (random.nextDouble() - 0.5) * 3,
                        oz = pose.z + (random.nextDouble() - 0.5) * 3;
                double dx = 1, dy = (random.nextDouble() - 0.5) * 0.8, dz = (random.nextDouble() - 0.5) * 0.8;
                double l = len(dx, dy, dz);
                dx /= l;
                dy /= l;
                dz /= l;
                boolean cast = CgShapes.castRay(s, pose, ox, oy, oz, dx, dy, dz, 8, hit);
                double expected = Double.NaN;
                for (int step = 0; step <= 8000; step++) {
                    double t = step * 0.001;
                    if (insideAt(s, pose, p.set(ox + dx * t, oy + dy * t, oz + dz * t), 0)) {
                        expected = t;
                        break;
                    }
                }
                String at = s + " at " + pose + " from " + ox + ", " + oy + ", " + oz;
                if (Double.isNaN(expected)) {
                    if (cast) {
                        // a graze between two of the scan's steps
                        assertTrue(at + ": " + hit, insideAt(s, pose, hit.point, 1e-6));
                    }
                    continue;
                }
                assertTrue(at, cast);
                assertTrue(at + ": " + hit, hit.distance <= expected + 1e-9 && hit.distance > expected - 0.001 - 1e-9);
                assertTrue(at + ": " + hit, insideAt(s, pose, hit.point, 1e-6));
                assertEquals(at, 1, hit.normal.length(), 1e-6);
                assertTrue(at + ": " + hit, hit.normal.x * dx + hit.normal.y * dy + hit.normal.z * dz <= 1e-9);
                hits++;
            }
        }
        assertTrue("hits " + hits, hits > 3000);
    }

    @Test
    public void closedFormRaysAgreeWithGjkRays() {
        CgCastHit closed = new CgCastHit(), gjk = new CgCastHit();
        for (CgShape s : new CgShape[]{SPHERE, BOX, CAPSULE, CYLINDER}) {
            // the same shape behind a support function, so the ray goes to GJK
            CgShape generic = CgConvex.of(s::support);
            for (int i = 0; i < 3000; i++) {
                CgPose pose = randomPose(FAR, 1);
                double ox = pose.x - 4, oy = pose.y + (random.nextDouble() - 0.5) * 3,
                        oz = pose.z + (random.nextDouble() - 0.5) * 3;
                double dy = (random.nextDouble() - 0.5), dz = (random.nextDouble() - 0.5);
                boolean a = CgShapes.castRay(s, pose, ox, oy, oz, 1, dy, dz, 8, closed);
                boolean b = CgShapes.castRay(generic, pose, ox, oy, oz, 1, dy, dz, 8, gjk);
                if (a && b) {
                    assertEquals(s + " at " + pose, closed.distance, gjk.distance, 1e-7);
                    assertEquals(s + " normal", 1, closed.normal.dot(gjk.normal), 1e-4);
                } else if (a != b) {
                    // only a graze may differ
                    CgCastHit h = a ? closed : gjk;
                    assertTrue(s + " at " + pose + ": " + h, CgShapes.distance(CgSphere.of(0), new CgPose()
                            .at(h.point.x, h.point.y, h.point.z), s, pose) < 1e-6);
                }
            }
        }
    }

    @Test
    public void aRayStartingInsideHitsAtZeroFacingBack() {
        CgCastHit hit = new CgCastHit();
        CgPose pose = new CgPose().at(FAR, 64, FAR).facing(Math.toRadians(40), Math.toRadians(15));
        for (CgShape s : new CgShape[]{SPHERE, BOX, CAPSULE, CYLINDER, CONE, HULL}) {
            pose.toWorld(0, 0, s == CONE ? 1 : 0, scratch);
            assertTrue(s.toString(), CgShapes.castRay(s, pose, scratch.x, scratch.y, scratch.z, 0, 0, 1, 5, hit));
            assertEquals(0, hit.distance, 1e-9);
            assertEquals(-1, hit.normal.z, 1e-9);
        }
        // in a shell's hole, the hit is where the ray leaves it, facing in
        assertTrue(CgShapes.castRay(SHELL, pose, pose.x, pose.y, pose.z, 1, 0, 0, 5, hit));
        assertEquals(1.1, hit.distance, 1e-9);
        assertEquals(-1, hit.normal.x, 1e-9);
    }

    // ---- compounds and refusals

    @Test
    public void aCompoundAnswersForItsChildren() {
        CgCompound c = (CgCompound) COMPOUND;
        CgPose child = new CgPose();
        for (int i = 0; i < 2000; i++) {
            CgPose pa = randomPose(FAR, 3), pb = randomPose(FAR, 3);
            boolean any = false;
            double nearest = Double.POSITIVE_INFINITY;
            for (int k = 0; k < c.size(); k++) {
                pa.mul(c.pose(k, child), child);
                any |= CgShapes.overlaps(c.shape(k), child, CAPSULE, pb);
                nearest = Math.min(nearest, CgShapes.distance(c.shape(k), child, CAPSULE, pb));
            }
            assertEquals(any, CgShapes.overlaps(COMPOUND, pa, CAPSULE, pb));
            assertEquals(nearest, CgShapes.distance(COMPOUND, pa, CAPSULE, pb), 1e-12);
        }
    }

    @Test
    public void aBoxTargetIsTheBoxAsAShape() {
        for (CgShape s : ALL) {
            for (int i = 0; i < 300; i++) {
                CgPose pose = randomPose(FAR, 3);
                CgAABB box = CgAABB.ofCenter(FAR + (random.nextDouble() - 0.5) * 3, FAR + (random.nextDouble() - 0.5)
                        * 3, FAR + (random.nextDouble() - 0.5) * 3, 0.3 + random.nextDouble(), 0.3
                        + random.nextDouble(), 0.3 + random.nextDouble());
                CgPose boxPose = new CgPose().at(box.centerX(), box.centerY(), box.centerZ());
                CgShape asShape = CgBox.of(box.halfX(), box.halfY(), box.halfZ());
                assertEquals(s.toString(), CgShapes.overlaps(s, pose, asShape, boxPose), CgShapes.overlaps(s, pose,
                        box));
            }
        }
        assertFalse(CgShapes.overlaps(SPHERE, new CgPose(), new CgAABB()));
    }

    @Test
    public void unsupportedPairsSayWhy() {
        CgPose p = new CgPose();
        assertThrows(IllegalArgumentException.class, () -> CgShapes.overlaps(SHELL, p, RING, p));
        assertThrows(IllegalArgumentException.class, () -> CgShapes.distance(SHELL, p, SPHERE, p));
        assertThrows(IllegalArgumentException.class, () -> CgShapes.contact(SPHERE, p, RING, p, new CgContact()));
        assertThrows(IllegalArgumentException.class, () -> CgShapes.cast(WIDE_CONE, p, 1, 0, 0, SPHERE, p,
                new CgCastHit()));
        assertThrows(IllegalArgumentException.class, () -> CgShapes.castRay(WIDE_CONE, p, 0, 0, 0, 1, 0, 0, 1,
                new CgCastHit()));
        assertThrows(IllegalArgumentException.class, () -> CgShapes.castRay(SPHERE, p, 0, 0, 0, 0, 0, 0, 1,
                new CgCastHit()));
    }

    // ---- the non-convex shapes, where their tests are exact

    @Test
    public void aShellIsHitOnlyAtItsEdge() {
        CgPose shell = new CgPose().at(FAR, FAR, FAR);
        CgAABB box = new CgAABB();
        // wholly in the hole, wholly outside, and straddling each edge
        assertFalse(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR + 0.3, FAR, FAR, 0.2, 0.2, 0.2)));
        assertFalse(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR + 2, FAR, FAR, 0.2, 0.2, 0.2)));
        assertTrue(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR + 1, FAR, FAR, 0.2, 0.2, 0.2)));
        assertTrue(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR + 1.7, FAR, FAR, 0.2, 0.2, 0.2)));
        // a box larger than the hole, centred in it, reaches the shell with its corners
        assertTrue(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR, FAR, FAR, 0.7, 0.7, 0.7)));
        assertFalse(CgShapes.overlaps(SHELL, shell, box.setCenterHalf(FAR, FAR, FAR, 0.6, 0.6, 0.6)));
        // the generic climb finds a hull's corners as the box's exact answer does
        for (int i = 0; i < 2000; i++) {
            CgPose pose = randomPose(FAR, 2.5);
            assertEquals(pose.toString(), CgShapes.overlaps(SHELL, shell, BOX, pose),
                    CgShapes.overlaps(SHELL, shell, HULL, pose));
        }
    }

    @Test
    public void aRingIsHitOutsideItsColumn() {
        CgPose ring = new CgPose().at(FAR, 70, FAR);
        CgAABB box = new CgAABB();
        assertFalse(CgShapes.overlaps(RING, ring, box.setCenterHalf(FAR, 70, FAR, 0.5, 3, 0.5)));
        assertTrue(CgShapes.overlaps(RING, ring, box.setCenterHalf(FAR + 1.2, 70, FAR, 0.2, 0.2, 0.2)));
        assertFalse(CgShapes.overlaps(RING, ring, box.setCenterHalf(FAR + 1.2, 71, FAR, 0.2, 0.2, 0.2)));
        assertFalse(CgShapes.overlaps(RING, ring, box.setCenterHalf(FAR + 2, 70, FAR, 0.2, 0.2, 0.2)));
        // the climb over a hull's corners may stop short of the farthest; rarely
        int differ = 0;
        for (int i = 0; i < 2000; i++) {
            CgPose pose = randomPose(FAR, 2.5);
            pose.at(pose.x, 70 + pose.y - FAR, pose.z);
            if (CgShapes.overlaps(RING, ring, BOX, pose) != CgShapes.overlaps(RING, ring, HULL, pose)) differ++;
        }
        assertTrue("differ " + differ, differ <= 20);
    }

    @Test
    public void aWideConeMissesOnlyBehind() {
        CgPose cone = new CgPose().at(FAR, FAR, FAR);
        CgAABB box = new CgAABB();
        // 130 degrees either side leaves a 50 degree cone behind the apex
        assertTrue(CgShapes.overlaps(WIDE_CONE, cone, box.setCenterHalf(FAR + 1, FAR, FAR - 0.5, 0.1, 0.1, 0.1)));
        assertFalse(CgShapes.overlaps(WIDE_CONE, cone, box.setCenterHalf(FAR, FAR, FAR - 1.2, 0.1, 0.1, 0.1)));
        assertFalse(CgShapes.overlaps(WIDE_CONE, cone, box.setCenterHalf(FAR, FAR, FAR + 2.5, 0.1, 0.1, 0.1)));
        int differ = 0;
        for (int i = 0; i < 2000; i++) {
            CgPose pose = randomPose(FAR, 3);
            if (CgShapes.overlaps(WIDE_CONE, cone, BOX, pose) != CgShapes.overlaps(WIDE_CONE, cone, HULL, pose)) {
                differ++;
            }
        }
        assertTrue("differ " + differ, differ <= 20);
    }

    @Test
    public void farFromTheOriginLosesNothing() {
        CgContact near = new CgContact(), far = new CgContact();
        for (int i = 0; i < 500; i++) {
            CgPose pa = randomPose(0, 2), pb = randomPose(0, 2);
            CgPose fa = new CgPose(pa).at(pa.x + FAR, pa.y + FAR, pa.z + FAR);
            CgPose fb = new CgPose(pb).at(pb.x + FAR, pb.y + FAR, pb.z + FAR);
            assertEquals(CgShapes.distance(CAPSULE, pa, BOX, pb), CgShapes.distance(CAPSULE, fa, BOX, fb), 1e-8);
            boolean a = CgShapes.contact(CAPSULE, pa, HULL, pb, near), b = CgShapes.contact(CAPSULE, fa, HULL, fb, far);
            assertEquals(a, b);
            if (a) assertEquals(near.depth, far.depth, 1e-6);
        }
    }

    // ---- cost

    private CgPose[] poses(int n) {
        CgPose[] poses = new CgPose[n];
        for (int i = 0; i < n; i++) poses[i] = randomPose(FAR, 3);
        return poses;
    }

    /** Every query over every convex pair, overlapping or apart. */
    private static int queryAll(CgPose[] poses, CgContact contact, CgCastHit hit) {
        int n = 0;
        for (CgShape sa : CONVEX) {
            for (CgShape sb : CONVEX) {
                for (int i = 0; i + 1 < poses.length; i += 2) {
                    CgPose pa = poses[i], pb = poses[i + 1];
                    if (CgShapes.overlaps(sa, pa, sb, pb)) n++;
                    if (CgShapes.distance(sa, pa, sb, pb) > 0) n++;
                    if (CgShapes.contact(sa, pa, sb, pb, contact)) n++;
                    if (CgShapes.cast(sa, pa, 2, -1, 1, sb, pb, hit)) n++;
                    if (CgShapes.castRay(sb, pb, pa.x, pa.y, pa.z, 1, 0.5, -0.2, 10, hit)) n++;
                }
            }
        }
        return n;
    }

    @Test
    public void queriesAllocateNothing() {
        CgPose[] poses = poses(200);
        CgContact contact = new CgContact();
        CgCastHit hit = new CgCastHit();
        for (int i = 0; i < 5; i++) queryAll(poses, contact, hit);
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long before = threads.getThreadAllocatedBytes(thread);
        queryAll(poses, contact, hit);
        long allocated = threads.getThreadAllocatedBytes(thread) - before;
        assertTrue("allocated " + allocated + " bytes over 32,000 queries", allocated < 1024);
    }

    /** Not a gate: nanoseconds per query for the plan's budget. */
    @Test
    public void measureQueries() {
        CgPose[] poses = poses(2000);
        CgAABB box = new CgAABB();
        CgPose ball = new CgPose().at(FAR, FAR, FAR);
        int sink = 0;
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            for (int r = 0; r < 50; r++) {
                for (CgPose p : poses) {
                    box.setCenterHalf(p.x, p.y, p.z, 0.3, 0.9, 0.3);
                    if (CgShapes.overlaps(SPHERE, ball, box)) sink++;
                }
            }
            long t1 = System.nanoTime();
            for (int r = 0; r < 50; r++) {
                for (int i = 0; i + 1 < poses.length; i += 2) {
                    if (CgShapes.overlaps(CAPSULE, poses[i], HULL, poses[i + 1])) sink++;
                }
            }
            long t2 = System.nanoTime();
            for (int r = 0; r < 50; r++) {
                for (int i = 0; i + 1 < poses.length; i += 2) {
                    if (CgShapes.overlaps(CAPSULE, poses[i], BOX, poses[i + 1])) sink++;
                }
            }
            long t3 = System.nanoTime();
            System.out.printf("sphere-box %.1f ns, capsule-hull %.1f ns, capsule-box %.1f ns (both GJK) (%d)%n",
                    (t1 - t0) / (50.0 * poses.length), (t2 - t1) / (25.0 * poses.length),
                    (t3 - t2) / (25.0 * poses.length), sink);
        }
    }
}
