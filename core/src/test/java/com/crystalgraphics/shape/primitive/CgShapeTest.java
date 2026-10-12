package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Every shape against a membership test written here, independently of its code: the support is the farthest of the
 * shape's own points and lies on the shape, and its box under any pose holds every point and is reached by one.
 */
public class CgShapeTest {

    private static final double EPS = 1e-7;
    private static final int SAMPLES = 40_000, DIRECTIONS = 300, POSES = 40;

    private static final class Case {
        final String name;
        final CgShape shape;
        final Predicate<Vector3d> inside;
        final double extent;

        Case(String name, CgShape shape, double extent, Predicate<Vector3d> inside) {
            this.name = name;
            this.shape = shape;
            this.inside = inside;
            this.extent = extent;
        }
    }

    private static List<Case> cases() {
        List<Case> c = new ArrayList<>();
        c.add(new Case("sphere", CgSphere.of(2), 3, p -> p.length() <= 2 + EPS));
        c.add(new Case("box", CgBox.of(1, 2, 3), 4, p -> Math.abs(p.x) <= 1 + EPS && Math.abs(p.y) <= 2 + EPS
                && Math.abs(p.z) <= 3 + EPS));
        c.add(new Case("capsule", CgCapsule.of(2, 0.75), 3.5, p -> segmentDistance(p, 0, 0, -2, 0, 0, 2) <= 0.75 + EPS));
        c.add(new Case("capsule between", CgCapsule.between(-1, 0.5, 0, 2, 1, 1.5, 0.5), 3.5,
                p -> segmentDistance(p, -1, 0.5, 0, 2, 1, 1.5) <= 0.5 + EPS));
        c.add(new Case("cylinder", CgCylinder.of(1.5, 2), 3,
                p -> p.x * p.x + p.z * p.z <= 1.5 * 1.5 + EPS && Math.abs(p.y) <= 2 + EPS));
        c.add(new Case("cone 35", CgCone.of(3, 35), 3.5, p -> inCone(p, 3, 35)));
        c.add(new Case("cone 90", CgCone.of(3, 90), 3.5, p -> inCone(p, 3, 90)));
        c.add(new Case("cone 140", CgCone.of(3, 140), 3.5, p -> inCone(p, 3, 140)));
        c.add(new Case("shell", CgShell.of(1.5, 2.5), 3, p -> p.length() >= 1.5 - EPS && p.length() <= 2.5 + EPS));
        c.add(new Case("ring", CgRing.of(1, 2.5, 0.5), 3, p -> {
            double r2 = p.x * p.x + p.z * p.z;
            return r2 >= 1 - EPS && r2 <= 2.5 * 2.5 + EPS && Math.abs(p.y) <= 0.5 + EPS;
        }));
        CgPose head = new CgPose().at(0, 0, 1.5).facing(30, 20);
        CgCompound hammer = CgCompound.builder().add(CgCapsule.of(1, 0.4), 0, 0, 0).add(CgBox.of(0.5, 0.3, 0.4), head)
                .build();
        c.add(new Case("compound", hammer, 2.5, p -> segmentDistance(p, 0, 0, -1, 0, 0, 1) <= 0.4 + EPS
                || inBox(head.toLocal(p.x, p.y, p.z, new Vector3d()), 0.5, 0.3, 0.4)));
        c.add(new Case("hull", CgConvex.hull(new double[]{-1, -2, -3, 1, -2, -3, -1, 2, -3, 1, 2, -3, -1, -2, 3, 1, -2, 3,
                -1, 2, 3, 1, 2, 3}), 4, p -> inBox(p, 1, 2, 3)));
        CgSphere ball = CgSphere.of(1.25);
        c.add(new Case("support function", CgConvex.of(ball::support), 2, p -> p.length() <= 1.25 + EPS));
        return c;
    }

    @Test
    public void supportIsTheFarthestPointAndOnTheShape() {
        Random random = new Random(7);
        Vector3d s = new Vector3d();
        for (Case c : cases()) {
            List<Vector3d> points = sampleInside(c, random);
            for (int i = 0; i < DIRECTIONS; i++) {
                double dx = random.nextGaussian(), dy = random.nextGaussian(), dz = random.nextGaussian();
                c.shape.support(dx, dy, dz, s);
                assertTrue(c.name + ": support " + s + " is not on the shape", c.inside.test(s));
                double best = s.x * dx + s.y * dy + s.z * dz;
                for (Vector3d p : points) {
                    double dot = p.x * dx + p.y * dy + p.z * dz;
                    if (dot > best + EPS) throw new AssertionError(c.name + ": " + p + " is farther along the direction than support " + s);
                }
            }
        }
    }

    @Test
    public void boundsUnderAnyPoseHoldEveryPointAndAreReached() {
        Random random = new Random(11);
        CgPose pose = new CgPose();
        CgAABB box = new CgAABB();
        Vector3d w = new Vector3d(), local = new Vector3d();
        for (Case c : cases()) {
            List<Vector3d> points = sampleInside(c, random);
            for (int i = 0; i < POSES; i++) {
                pose.at(random.nextGaussian() * 100, random.nextGaussian() * 100, random.nextGaussian() * 100);
                if (i > 0) pose.rotation(random.nextGaussian(), random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
                c.shape.bounds(pose, box);
                CgAABB grown = new CgAABB(box).grow(1e-6);
                for (Vector3d p : points) {
                    pose.toWorld(p.x, p.y, p.z, w);
                    if (!grown.contains(w.x, w.y, w.z)) throw new AssertionError(c.name + ": " + w + " outside " + box);
                }
                // each face is reached by a point of the shape: the support along that world axis
                double[][] axes = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                double[] faces = {box.maxX, box.minX, box.maxY, box.minY, box.maxZ, box.minZ};
                for (int a = 0; a < 6; a++) {
                    pose.unrotate(axes[a][0], axes[a][1], axes[a][2], local);
                    c.shape.support(local.x, local.y, local.z, local);
                    assertTrue(c.name, c.inside.test(local));
                    pose.toWorld(local.x, local.y, local.z, w);
                    double reached = a < 2 ? w.x : a < 4 ? w.y : w.z;
                    assertEquals(c.name + " face " + a, faces[a], reached, 1e-6);
                }
            }
            CgAABB own = c.shape.bounds(new CgAABB()), placed = c.shape.bounds(new CgPose(), new CgAABB());
            assertEquals(c.name, placed.minX, own.minX, 1e-12);
            assertEquals(c.name, placed.maxY, own.maxY, 1e-12);
            assertEquals(c.name, placed.maxZ, own.maxZ, 1e-12);
        }
    }

    @Test
    public void convexity() {
        assertTrue(CgCone.of(5, 90).isConvex());
        assertFalse(CgCone.of(5, 91).isConvex());
        assertFalse(CgShell.of(1, 2).isConvex());
        assertTrue(CgShell.of(0, 2).isConvex());
        assertFalse(CgRing.of(1, 2, 1).isConvex());
        assertFalse(CgCompound.builder().add(CgSphere.of(1)).build().isConvex());
    }

    @Test
    public void frontsKeepTheirWidth() {
        CgShell shell = CgShell.front(2);
        assertTrue(shell.isFront());
        assertEquals(2, shell.width(), 0);
        CgRing ring = CgRing.front(1.2, 0.6);
        assertTrue(ring.isFront());
        assertEquals(1.2, ring.width(), 0);
        assertFalse(CgShell.of(1, 3).isFront());
    }

    @Test
    public void badArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CgSphere.of(-1));
        assertThrows(IllegalArgumentException.class, () -> CgSphere.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CgBox.of(1, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> CgCone.of(3, 181));
        assertThrows(IllegalArgumentException.class, () -> CgShell.of(3, 2));
        assertThrows(IllegalArgumentException.class, () -> CgConvex.hull(new double[]{1, 2}));
        assertThrows(IllegalStateException.class, () -> CgCompound.builder().build());
    }

    @Test
    public void compoundCopiesItsPoses() {
        CgPose pose = new CgPose().at(5, 0, 0);
        CgCompound compound = CgCompound.builder().add(CgSphere.of(1), pose).build();
        pose.at(-50, 0, 0);
        CgAABB box = compound.bounds(new CgAABB());
        assertEquals(4, box.minX, 1e-12);
        assertEquals(6, box.maxX, 1e-12);
    }

    private static List<Vector3d> sampleInside(Case c, Random random) {
        List<Vector3d> points = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            Vector3d p = new Vector3d((random.nextDouble() * 2 - 1) * c.extent, (random.nextDouble() * 2 - 1) * c.extent,
                    (random.nextDouble() * 2 - 1) * c.extent);
            if (c.inside.test(p)) points.add(p);
        }
        assertTrue(c.name + ": too few samples inside", points.size() > 200);
        return points;
    }

    private static boolean inBox(Vector3d p, double hx, double hy, double hz) {
        return Math.abs(p.x) <= hx + EPS && Math.abs(p.y) <= hy + EPS && Math.abs(p.z) <= hz + EPS;
    }

    private static boolean inCone(Vector3d p, double range, double halfAngleDegrees) {
        double len = p.length();
        if (len > range + EPS) return false;
        if (len < EPS) return true;
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, p.z / len))));
        return angle <= halfAngleDegrees + 1e-5;
    }

    private static double segmentDistance(Vector3d p, double ax, double ay, double az, double bx, double by, double bz) {
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double t = ((p.x - ax) * ux + (p.y - ay) * uy + (p.z - az) * uz) / (ux * ux + uy * uy + uz * uz);
        t = Math.max(0, Math.min(1, t));
        double dx = p.x - (ax + ux * t), dy = p.y - (ay + uy * t), dz = p.z - (az + uz * t);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
