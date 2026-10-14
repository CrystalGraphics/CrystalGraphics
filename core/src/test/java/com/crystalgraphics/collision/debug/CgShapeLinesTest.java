package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgConvex;
import com.crystalgraphics.shape.primitive.CgCylinder;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The wireframes lie on their shapes where their poses put them, and survive the wire. */
public class CgShapeLinesTest {

    private static final double EPS = 1e-9;

    @Test
    public void aBoxDrawsItsTwelveEdgesTurnedWithItsPose() {
        CgPose pose = new CgPose().at(10, 0, -4).facing(Math.toRadians(90), Math.toRadians(20));
        List<double[]> ends = ends(CgBox.of(1, 2, 3), pose);
        assertEquals(24, ends.size());
        Vector3d local = new Vector3d();
        for (double[] p : ends) {
            pose.toLocal(p[0], p[1], p[2], local);
            assertEquals(1, Math.abs(local.x), EPS);
            assertEquals(2, Math.abs(local.y), EPS);
            assertEquals(3, Math.abs(local.z), EPS);
        }
    }

    @Test
    public void roundShapesLieOnTheirSurfaces() {
        CgPose pose = new CgPose().at(5, 64, 5).facing(Math.toRadians(30), Math.toRadians(-45));
        for (double[] p : ends(CgSphere.of(2), pose)) assertEquals(2, distance(p, 5, 64, 5), EPS);

        CgCapsule capsule = CgCapsule.between(0, 0, 0, 0, 3, 0, 0.5);
        Vector3d a = new Vector3d(), b = new Vector3d();
        pose.toWorld(0, 0, 0, a);
        pose.toWorld(0, 3, 0, b);
        for (double[] p : ends(capsule, pose)) assertEquals(0.5, toSegment(p, a, b), EPS);
    }

    @Test
    public void aCompoundDrawsEachChildWhereItStands() {
        CgCompound compound = CgCompound.builder().add(CgSphere.of(0.5), 0, 0, 5).add(CgSphere.of(1)).build();
        CgPose pose = new CgPose().at(1, 2, 3).facing(Math.toRadians(90), 0);
        Vector3d child = pose.toWorld(0, 0, 5, new Vector3d());
        int near = 0, far = 0;
        for (double[] p : ends(compound, pose)) {
            if (Math.abs(distance(p, child.x, child.y, child.z) - 0.5) < EPS) near++;
            else if (Math.abs(distance(p, 1, 2, 3) - 1) < EPS) far++;
            else throw new AssertionError("a point on neither child: " + p[0] + ", " + p[1] + ", " + p[2]);
        }
        assertTrue(near > 0 && far > 0);
    }

    @Test
    public void aHitboxShowsEachPartAsItsTickTestedIt() {
        CgHitbox hitbox = CgHitbox.of(CgShell.front(1)).at(3, 70, -2).radius(0, 10, 10);
        CgCollisionWorld world = CgCollisionWorld.empty();
        for (int i = 0; i < 5; i++) hitbox.tick(world, 1);
        List<CgShape> shapes = new ArrayList<>();
        hitbox.parts((name, shape, pose) -> {
            shapes.add(shape);
            assertEquals(3, pose.x, EPS);
            assertEquals(70, pose.y, EPS);
            assertEquals(-2, pose.z, EPS);
        });
        assertEquals(1, shapes.size());
        assertEquals(5, ((CgShell) shapes.get(0)).outer(), EPS);
        hitbox.stop();
        shapes.clear();
        hitbox.parts((name, shape, pose) -> shapes.add(shape));
        assertEquals("a stopped hitbox shows the last it tested", 1, shapes.size());
    }

    @Test
    public void aHostWorldsQueriesAreShownUntilTheirHoldRunsOut() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgCollisionWorld yard = CgCollisionWorld.of(new Yard(), new Table());
        CgShape ball = CgSphere.of(0.5);
        int[] heard = {0};
        CgCollisionQuery.watch((shape, pose, box, dx, dy, dz, length, level, nearest) -> {
            heard[0]++;
            lines.query(shape, pose, box, dx, dy, dz, length, level, nearest, 0);
        });
        try {
            assertTrue(yard.cast(ball).at(0.5, 5, 0.5).along(0, -1, 0, 10).blocksOnly().first(new CgHit()));
            CgHitbox.of(CgSphere.of(1)).at(0.5, 1, 0.5).tick(yard, 1);
            CgCollisionWorld.empty().cast(ball).at(0, 5, 0).along(0, -1, 0, 10).any();
        } finally {
            CgCollisionQuery.watch(null);
        }
        assertEquals("not the hitbox's query, nor the empty world's cast", 1, heard[0]);
        List<Integer> colours = new ArrayList<>();
        lines.drawTo(0, (argb, ax, ay, az, bx, by, bz) -> colours.add(argb));
        assertTrue("its path, then where it stopped", colours.contains(CgRecentLines.PATH)
                && colours.contains(CgRecentLines.HIT));
        colours.clear();
        lines.drawTo(CgRecentLines.HOLD_NANOS, (argb, ax, ay, az, bx, by, bz) -> colours.add(argb));
        assertTrue("gone after its hold", colours.isEmpty());
    }

    @Test
    public void aPackCarriesItsSegmentsToAUnitAndStopsAtItsLimit() {
        CgLinePack pack = new CgLinePack();
        pack.begin(100, 64, -300, 2);
        pack.line(101.25, 64.5, -310, 90, 70, -300.001);
        pack.line(100, 64, -300, 100, 64, -300 + CgLinePack.REACH + 50);
        pack.line(0, 0, 0, 1, 1, 1);
        assertTrue(pack.full());
        List<double[]> read = new ArrayList<>();
        assertEquals(2, CgLinePack.read(pack.finish(), 0,
                (argb, ax, ay, az, bx, by, bz) -> read.add(new double[]{ax, ay, az, bx, by, bz})));
        double[] first = {101.25, 64.5, -310, 90, 70, -300.001};
        for (int i = 0; i < 6; i++) assertEquals(first[i], read.get(0)[i], CgLinePack.UNIT / 2);
        assertEquals(-300 + CgLinePack.REACH, read.get(1)[5], CgLinePack.UNIT / 2);
    }

    @Test
    public void aPackCarriesEachHitboxsShowColourAndLeavesTheRestToTheReader() {
        CgLinePack pack = new CgLinePack();
        pack.begin(0, 0, 0, 16);
        pack.line(0, 0, 0, 1, 0, 0);
        pack.colour(0xFFFF0000);
        pack.line(0, 0, 0, 0, 1, 0);
        pack.line(0, 0, 0, 0, 0, 1);
        pack.colour(0);
        pack.line(1, 1, 1, 2, 2, 2);
        List<Integer> colours = new ArrayList<>();
        assertEquals(4, CgLinePack.read(pack.finish(), CgCollisionShow.SERVER,
                (argb, ax, ay, az, bx, by, bz) -> colours.add(argb)));
        assertEquals(Arrays.asList(CgCollisionShow.SERVER, 0xFFFF0000, 0xFFFF0000, CgCollisionShow.SERVER), colours);
    }

    @Test
    public void aBoxsFacesAreItsSixSidesTurnedWithItsPose() {
        CgPose pose = new CgPose().at(10, 0, -4).facing(Math.toRadians(90), Math.toRadians(20));
        List<double[]> corners = corners(CgBox.of(1, 2, 3), pose);
        assertEquals("two triangles a side", 12 * 3, corners.size());
        Vector3d local = new Vector3d();
        double area = 0;
        for (int i = 0; i < corners.size(); i += 3) {
            double[] a = corners.get(i), b = corners.get(i + 1), c = corners.get(i + 2);
            for (double[] p : new double[][]{a, b, c}) {
                pose.toLocal(p[0], p[1], p[2], local);
                assertEquals(1, Math.abs(local.x), EPS);
                assertEquals(2, Math.abs(local.y), EPS);
                assertEquals(3, Math.abs(local.z), EPS);
            }
            area += area(a, b, c);
        }
        assertEquals("the whole surface, once", 2 * (4 * 6 + 2 * 6 + 2 * 4), area, 1e-6);
    }

    @Test
    public void everyShapesFacesLieOnItAndNoneIsLeftBare() {
        CgPose pose = new CgPose().at(3, 70, -8).facing(1.1, -0.4);
        CgShape hull = CgConvex.hull(new double[]{-1, 0, 0, 1, 0, 0, -1, 0, 2, 1, 0, 2, -1, 1, 0, 1, 1, 0});
        CgShape[] shapes = {CgSphere.of(2), CgCapsule.of(1.5, 0.5), CgCylinder.of(1, 2), CgCone.of(4, 0.6),
                CgShell.of(1, 2), CgRing.of(1, 2, 0.5), hull,
                CgCompound.builder().add(CgSphere.of(1), 0, 0, 2).add(CgBox.of(1, 1, 1)).build()};
        Vector3d out = new Vector3d();
        for (CgShape shape : shapes) {
            List<double[]> corners = corners(shape, pose);
            assertTrue(shape + " has faces", corners.size() >= 12 && corners.size() % 3 == 0);
            double reach = 0;
            for (int axis = 0; axis < 3; axis++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    reach = Math.max(reach, shape.support(axis == 0 ? sign : 0, axis == 1 ? sign : 0,
                            axis == 2 ? sign : 0, out).length());
                }
            }
            for (double[] p : corners) {
                assertTrue(shape + " keeps its faces within its reach",
                        distance(p, pose.x, pose.y, pose.z) <= reach * Math.sqrt(3) + 1e-6);
            }
        }
    }

    @Test
    public void aHitboxIsShownWithItsFacesAndGoesWithItsHoldThoughStopped() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgCollisionWorld yard = CgCollisionWorld.of(new Yard(), new Table());
        CgHitbox blow = CgHitbox.of(CgBox.of(0.75, 0.75, 1.5)).at(0.5, 1, 0.5).activeFor(1).showColour(0xFF4040);
        blow.tick(yard, 1);
        assertTrue("a blow of one tick stops in it", blow.stopped());
        lines.hitbox(blow, yard.blocks(), 0);
        int[] drawn = {0, 0};
        CgRecentLines.Out count = new CgRecentLines.Out() {
            @Override
            public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
                assertEquals(0xFFFF4040, argb);
                drawn[0]++;
            }

            @Override
            public void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz,
                    double cx, double cy, double cz) {
                assertEquals(0xFFFF4040, argb);
                drawn[1]++;
            }
        };
        lines.drawTo(CgRecentLines.HOLD_NANOS - 1, count);
        assertEquals("its edges", 12, drawn[0]);
        assertEquals("its faces", 12, drawn[1]);
        drawn[0] = drawn[1] = 0;
        lines.drawTo(CgRecentLines.HOLD_NANOS, count);
        assertEquals("gone after its hold, not lingering", 0, drawn[0] + drawn[1]);
    }

    @Test
    public void aPackCarriesFacesBesideSegmentsEachInItsColour() {
        CgLinePack pack = new CgLinePack();
        pack.begin(100, 64, -300, 16);
        pack.colour(0xFFFF0000);
        pack.line(100, 64, -300, 101, 64, -300);
        pack.triangle(100, 64, -300, 101.5, 64, -300, 100, 66.25, -297);
        pack.triangle(100, 64, -300, 100, 66.25, -297, 99, 64, -300);
        pack.colour(0);
        pack.line(100, 65, -300, 100, 66, -300);
        List<String> read = new ArrayList<>();
        double[] last = new double[9];
        assertEquals(4, CgLinePack.read(pack.finish(), CgCollisionShow.SERVER, new CgRecentLines.Out() {
            @Override
            public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
                read.add("line " + Integer.toHexString(argb));
            }

            @Override
            public void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz,
                    double cx, double cy, double cz) {
                read.add("face " + Integer.toHexString(argb));
                System.arraycopy(new double[]{ax, ay, az, bx, by, bz, cx, cy, cz}, 0, last, 0, 9);
            }
        }));
        assertEquals(Arrays.asList("line ffff0000", "face ffff0000", "face ffff0000",
                "line " + Integer.toHexString(CgCollisionShow.SERVER)), read);
        double[] second = {100, 64, -300, 100, 66.25, -297, 99, 64, -300};
        for (int i = 0; i < 9; i++) assertEquals(second[i], last[i], CgLinePack.UNIT / 2);
    }

    private static List<double[]> corners(CgShape shape, CgPose pose) {
        List<double[]> corners = new ArrayList<>();
        new CgShapeLines().faces(shape, pose, new CgShapeLines.Sink() {
            @Override
            public void line(double ax, double ay, double az, double bx, double by, double bz) {
                throw new AssertionError("faces write no lines");
            }

            @Override
            public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx,
                    double cy, double cz) {
                corners.add(new double[]{ax, ay, az});
                corners.add(new double[]{bx, by, bz});
                corners.add(new double[]{cx, cy, cz});
            }
        });
        return corners;
    }

    private static double area(double[] a, double[] b, double[] c) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
        return Math.sqrt(x * x + y * y + z * z) / 2;
    }

    private static List<double[]> ends(CgShape shape, CgPose pose) {
        List<double[]> ends = new ArrayList<>();
        new CgShapeLines().write(shape, pose, (ax, ay, az, bx, by, bz) -> {
            ends.add(new double[]{ax, ay, az});
            ends.add(new double[]{bx, by, bz});
        });
        return ends;
    }

    private static double distance(double[] p, double x, double y, double z) {
        double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double toSegment(double[] p, Vector3d a, Vector3d b) {
        double ux = b.x - a.x, uy = b.y - a.y, uz = b.z - a.z;
        double t = ((p[0] - a.x) * ux + (p[1] - a.y) * uy + (p[2] - a.z) * uz) / (ux * ux + uy * uy + uz * uz);
        t = Math.max(0, Math.min(1, t));
        return distance(p, a.x + ux * t, a.y + uy * t, a.z + uz * t);
    }
}
