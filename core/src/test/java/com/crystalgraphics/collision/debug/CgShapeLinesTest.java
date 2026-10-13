package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.collision.CgHitbox;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CgHit;
import com.crystalgraphics.collision.CgQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCapsule;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.shape.primitive.CgSphere;
import org.joml.Vector3d;
import org.junit.Test;

import java.util.ArrayList;
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
        CgCollisionWorld world = CgCollisionWorld.shapes();
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
        CgQueryLines lines = new CgQueryLines(1 << 12);
        CgCollisionWorld yard = CgCollisionWorld.of(new Yard(), new Table());
        CgShape ball = CgSphere.of(0.5);
        int[] heard = {0};
        CgQuery.watch((shape, pose, box, dx, dy, dz, length, level, nearest) -> {
            heard[0]++;
            lines.add(shape, pose, box, dx, dy, dz, length, level, nearest, 0);
        });
        try {
            assertTrue(yard.cast(ball).at(0.5, 5, 0.5).along(0, -1, 0, 10).blocksOnly().first(new CgHit()));
            yard.ray().at(0.5, 5, 0.5).along(0, -1, 0, 10).blocksOnly().unwatched().any();
            CgCollisionWorld.shapes().cast(ball).at(0, 5, 0).along(0, -1, 0, 10).any();
        } finally {
            CgQuery.watch(null);
        }
        assertEquals("not the unwatched ray, nor the shape world's cast", 1, heard[0]);
        List<Integer> colours = new ArrayList<>();
        lines.drawTo(0, 100, (argb, ax, ay, az, bx, by, bz) -> colours.add(argb));
        assertTrue("its path, then where it stopped", colours.contains(CgQueryLines.PATH)
                && colours.contains(CgQueryLines.HIT));
        colours.clear();
        lines.drawTo(101, 100, (argb, ax, ay, az, bx, by, bz) -> colours.add(argb));
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
        assertEquals(2, CgLinePack.read(pack.finish(),
                (ax, ay, az, bx, by, bz) -> read.add(new double[]{ax, ay, az, bx, by, bz})));
        double[] first = {101.25, 64.5, -310, 90, 70, -300.001};
        for (int i = 0; i < 6; i++) assertEquals(first[i], read.get(0)[i], CgLinePack.UNIT / 2);
        assertEquals(-300 + CgLinePack.REACH, read.get(1)[5], CgLinePack.UNIT / 2);
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
