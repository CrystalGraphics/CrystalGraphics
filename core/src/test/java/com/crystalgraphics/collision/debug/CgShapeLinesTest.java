package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.CollisionFakes.Table;
import com.crystalgraphics.collision.CollisionFakes.Yard;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The wireframes lie on their shapes where their poses put them, and survive the wire. */
public class CgShapeLinesTest {

    private static final double EPS = 1e-9;
    private static final CgCollisionWorld YARD = CgCollisionWorld.of(new Yard(), new Table());

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
    public void everyCornerOfAnOutlineIsACornerOfItsFaces() {
        CgPose pose = new CgPose().at(3, 70, -8).facing(1.1, -0.4);
        CgShape[] shapes = {CgSphere.of(2), CgCapsule.between(0, 0, 0, 1, 2, 0.5, 0.4), CgCylinder.of(1, 2),
                CgCone.of(4, Math.toRadians(35)), CgCone.of(4, Math.toRadians(15)), CgCone.of(3, Math.toRadians(120)),
                CgShell.of(1, 2), CgRing.of(1, 2, 0.5), WEDGE};
        for (CgShape shape : shapes) {
            List<double[]> corners = corners(shape, pose);
            for (double[] end : ends(shape, pose)) {
                boolean met = false;
                for (double[] c : corners) met |= distance(end, c[0], c[1], c[2]) < 1e-9;
                assertTrue(shape + ": its edges meet its fill at " + Arrays.toString(end), met);
            }
        }
    }

    @Test
    public void everyClosedShapesFacesWindOutward() {
        double pi = Math.PI, capsuleLength = Math.sqrt(1 + 4 + 0.25);
        Object[][] volumes = {
                {CgSphere.of(2), 4 / 3.0 * pi * 8},
                {CgBox.of(1, 2, 3), 48.0},
                {CgCapsule.between(0, 0, 0, 1, 2, 0.5, 0.4), pi * 0.16 * capsuleLength + 4 / 3.0 * pi * 0.064},
                {CgCylinder.of(1, 2), pi * 4},
                {CgCone.of(4, Math.toRadians(35)), 2 * pi / 3 * 64 * (1 - Math.cos(Math.toRadians(35)))},
                {CgCone.of(3, Math.toRadians(120)), 2 * pi / 3 * 27 * (1 - Math.cos(Math.toRadians(120)))},
                {CgShell.of(1, 2), 4 / 3.0 * pi * 7},
                {CgRing.of(1, 2, 0.5), pi * 3},
                {WEDGE, 2.0},
                {CgCompound.builder().add(CgSphere.of(1), 0, 0, 2).add(CgBox.of(1, 1, 1)).build(), 4 / 3.0 * pi + 8},
        };
        CgPose pose = new CgPose().at(3, 70, -8).facing(1.1, -0.4);
        for (Object[] row : volumes) {
            CgShape shape = (CgShape) row[0];
            double expected = (Double) row[1];
            List<double[]> c = corners(shape, pose);
            double signed = 0;
            for (int i = 0; i < c.size(); i += 3) {
                double[] a = c.get(i), b = c.get(i + 1), d = c.get(i + 2);
                double ax = a[0] - pose.x, ay = a[1] - pose.y, az = a[2] - pose.z;
                double bx = b[0] - pose.x, by = b[1] - pose.y, bz = b[2] - pose.z;
                double dx = d[0] - pose.x, dy = d[1] - pose.y, dz = d[2] - pose.z;
                signed += (ax * (by * dz - bz * dy) + ay * (bz * dx - bx * dz) + az * (bx * dy - by * dx)) / 6;
            }
            assertEquals(shape + " encloses its volume, faces out", expected, signed, expected * 0.05);
        }
    }

    @Test
    public void aShapeInsideAnotherDrawsBetweenItsBackAndItsFront() {
        CgCollisionDebug.Batch faces = new CgCollisionDebug.Batch(3);
        CgShapeLines lines = new CgShapeLines();
        lines.faces(CgSphere.of(3), new CgPose(), faces);
        lines.faces(CgBox.of(1, 1, 1), new CgPose().facing(0.6, 0.3), faces);
        int backs = faces.sortFrom(0.4, 1, -12);
        List<String> runs = new ArrayList<>();
        for (int k = 0; k < faces.count; k++) {
            String run = (faces.pieceOf[faces.order[k]] == 0 ? "sphere " : "box ") + (k < backs ? "back" : "front");
            if (runs.isEmpty() || !runs.get(runs.size() - 1).equals(run)) runs.add(run);
        }
        assertEquals(Arrays.asList("sphere back", "box back", "box front", "sphere front"), runs);
    }

    @Test
    public void aPackKeepsWhereEachPieceBegins() {
        CgRecentLines server = new CgRecentLines(1 << 12);
        CgHitbox pair = CgHitbox.of(CgCompound.builder().add(CgSphere.of(0.5), 0, 0, 2).add(CgBox.of(1, 1, 1)).build())
                .at(0, 1, 0);
        pair.tick(YARD, 1);
        server.hitbox(pair, YARD.blocks(), 0, 0);
        CgLinePack pack = new CgLinePack();
        pack.begin(0, 0, 0, 1 << 12);
        server.packTo(YARD.blocks(), 0, 0, 0, 64, true, CgCollisionShow.SERVER, 0, pack);
        int[] pieces = {0};
        CgLinePack.read(pack.finish(), 0, new CgRecentLines.Out() {
            @Override
            public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
            }

            @Override
            public void piece() {
                pieces[0]++;
            }
        });
        assertEquals("a compound's two children", 2, pieces[0]);
    }

    @Test
    public void aHullsFacesAreItsOwnEachOnce() {
        assertEquals("a prism's two ends and three sides", 8 + 2 * Math.sqrt(5), surface(WEDGE), 1e-9);
        assertEquals("and its nine edges", 18, ends(WEDGE, new CgPose()).size());
        CgShape cube = CgConvex.hull(new double[]{0, 0, 0, 2, 0, 0, 0, 2, 0, 2, 2, 0, 0, 0, 2, 2, 0, 2, 0, 2, 2, 2, 2, 2,
                1, 1, 1});
        assertEquals("four corners in a plane are one face, inside points none", 24, surface(cube), 1e-9);
        assertEquals(24, ends(cube, new CgPose()).size());
    }

    private static final CgShape WEDGE = CgConvex.hull(new double[]{-1, 0, 0, 1, 0, 0, -1, 0, 2, 1, 0, 2, -1, 1, 0, 1,
            1, 0});

    private static double surface(CgShape shape) {
        List<double[]> corners = corners(shape, new CgPose());
        double area = 0;
        for (int i = 0; i < corners.size(); i += 3) area += area(corners.get(i), corners.get(i + 1), corners.get(i + 2));
        return area;
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
    public void aStoppedHitboxLingersFadingThenGoes() {
        long linger = 200_000_000L;
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgHitbox blow = blow();
        lines.hitbox(blow, YARD.blocks(), 0, linger);
        Tally drawn = new Tally();
        lines.drawTo(CgRecentLines.HOLD_NANOS - 1, drawn);
        assertEquals("its edges", 12, drawn.lines);
        assertEquals("its faces", 12, drawn.faces);
        assertEquals("whole through its hold", 0xFFFF4040, drawn.line);
        assertEquals("its faces at a quarter", 0x40FF4040, drawn.face);
        lines.drawTo(CgRecentLines.HOLD_NANOS + linger / 2, drawn.reset());
        assertEquals(24, drawn.lines + drawn.faces);
        assertEquals("half faded", 0x7F, drawn.line >>> 24, 1);
        assertEquals(0xFF4040, drawn.line & 0xFFFFFF);
        lines.drawTo(CgRecentLines.HOLD_NANOS + linger, drawn.reset());
        assertEquals("gone after its linger", 0, drawn.lines + drawn.faces);
    }

    @Test
    public void noLingerAndAStoppedHitboxGoesWithItsHold() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        lines.hitbox(blow(), YARD.blocks(), 0, 0);
        Tally drawn = new Tally();
        lines.drawTo(CgRecentLines.HOLD_NANOS, drawn);
        assertEquals(0, drawn.lines + drawn.faces);
    }

    @Test
    public void aHitboxStoppedBetweenTicksLingersToo() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgHitbox zone = CgHitbox.of(CgBox.of(1, 1, 1)).at(0.5, 1, 0.5);
        zone.tick(YARD, 1);
        CgHitboxes.watch(new CgHitboxes.Watcher() {
            @Override
            public void ticked(CgHitbox hitbox, CgCollisionWorld world) {
                throw new AssertionError("a stop is not a tick");
            }

            @Override
            public void stopped(CgHitbox hitbox, CgCollisionWorld world) {
                lines.hitbox(hitbox, world.blocks(), 0, CgRecentLines.HOLD_NANOS);
            }
        });
        try {
            zone.stop();
        } finally {
            CgHitboxes.watch(null);
        }
        Tally drawn = new Tally();
        lines.drawTo(CgRecentLines.HOLD_NANOS + 1, drawn);
        assertEquals("its last pose, past the hold", 12, drawn.lines);
    }

    @Test
    public void aHitboxLingersOnlyWhenItOrTheSettingSays() {
        CgHitbox plain = CgHitbox.of(CgSphere.of(1));
        assertEquals("only while active, by default", 0, CgCollisionShow.lingerNanos(plain));
        CgCollisionShow.LINGER.set(300);
        try {
            assertEquals(300_000_000L, CgCollisionShow.lingerNanos(plain));
            assertEquals("its own over the setting", 0, CgCollisionShow.lingerNanos(CgHitbox.of(CgSphere.of(1)).showLinger(0)));
        } finally {
            CgCollisionShow.LINGER.set(0);
        }
        assertEquals("in game ticks", 300_000_000L, CgCollisionShow.lingerNanos(plain.showLinger(6)));
        assertThrows(IllegalArgumentException.class, () -> plain.showLinger(-1));
    }

    @Test
    public void edgesOutlastFacesWhenTheRecordIsFull() {
        CgRecentLines lines = new CgRecentLines(24);
        for (int i = 0; i < 3; i++) {
            CgHitbox box = CgHitbox.of(CgBox.of(0.5, 0.5, 0.5)).at(i * 4, 1, 0);
            box.tick(YARD, 1);
            lines.hitbox(box, YARD.blocks(), 0, 0);
        }
        Tally drawn = new Tally();
        lines.drawTo(0, drawn);
        assertEquals("two boxes' edges each fill the cap apart", 24, drawn.lines);
        assertEquals(24, drawn.faces);

        CgLinePack pack = new CgLinePack();
        pack.begin(0, 0, 0, 30);
        lines.packTo(YARD.blocks(), 0, 0, 0, 64, true, CgCollisionShow.SERVER, 0, pack);
        CgLinePack.read(pack.finish(), CgCollisionShow.SERVER, drawn.reset());
        assertEquals("a full pack keeps every edge", 24, drawn.lines);
        assertEquals("and drops faces", 6, drawn.faces);
    }

    @Test
    public void aWireframeWatchersPackHoldsNoFacesAndAFadingOneItsOwnColour() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgHitbox box = CgHitbox.of(CgBox.of(0.5, 0.5, 0.5)).at(0, 1, 0).activeFor(1);
        box.tick(YARD, 1);
        lines.hitbox(box, YARD.blocks(), 0, 100);
        CgLinePack pack = new CgLinePack();
        pack.begin(0, 0, 0, 1 << 12);
        lines.packTo(YARD.blocks(), 0, 0, 0, 64, false, CgCollisionShow.SERVER, CgRecentLines.HOLD_NANOS + 50, pack);
        Tally drawn = new Tally();
        CgLinePack.read(pack.finish(), 0, drawn);
        assertEquals(12, drawn.lines);
        assertEquals(0, drawn.faces);
        assertEquals("the server's colour, in eighths of alpha", CgCollisionShow.SERVER & 0xFFFFFF,
                drawn.line & 0xFFFFFF);
        assertEquals(4 * 255 / 8, drawn.line >>> 24);
    }

    @Test
    public void aHitboxsFillIsItsOwnAndTravelsInItsFacesColour() {
        CgRecentLines lines = new CgRecentLines(1 << 12);
        CgHitbox edges = CgHitbox.of(CgBox.of(0.5, 0.5, 0.5)).at(0, 1, 0).showFill(0);
        CgHitbox half = CgHitbox.of(CgBox.of(0.5, 0.5, 0.5)).at(4, 1, 0).showFill(0.5f).showColour(0x2060FF);
        CgHitbox plain = CgHitbox.of(CgBox.of(0.5, 0.5, 0.5)).at(8, 1, 0);
        Tally drawn = new Tally();
        for (CgHitbox h : new CgHitbox[]{edges, half, plain}) h.tick(YARD, 1);
        lines.hitbox(edges, YARD.blocks(), 0, 0);
        lines.drawTo(0, drawn);
        assertEquals("edges alone", 12, drawn.lines);
        assertEquals(0, drawn.faces);
        lines.hitbox(half, YARD.blocks(), 0, 0);
        lines.drawTo(0, drawn.reset());
        assertEquals("half its colour's alpha", 0x802060FF, drawn.face);

        CgRecentLines server = new CgRecentLines(1 << 12);
        server.hitbox(plain, YARD.blocks(), 0, 0);
        CgLinePack pack = new CgLinePack();
        pack.begin(0, 0, 0, 1 << 12);
        server.packTo(YARD.blocks(), 0, 0, 0, 64, true, CgCollisionShow.SERVER, 0, pack);
        CgLinePack.read(pack.finish(), CgCollisionShow.SERVER, drawn.reset());
        assertEquals("edges in the reader's colour", CgCollisionShow.SERVER, drawn.line);
        assertEquals("faces carry their quarter", 0x40000000 | CgCollisionShow.SERVER & 0xFFFFFF, drawn.face);
        assertThrows(IllegalArgumentException.class, () -> plain.showFill(1.5f));
    }

    /** A blow of one tick, stopped in it. */
    private static CgHitbox blow() {
        CgHitbox blow = CgHitbox.of(CgBox.of(0.75, 0.75, 1.5)).at(0.5, 1, 0.5).activeFor(1).showColour(0xFF4040);
        blow.tick(YARD, 1);
        assertTrue(blow.stopped());
        return blow;
    }

    /** How many of each were drawn, and the last line's and face's colours. */
    private static final class Tally implements CgRecentLines.Out {
        int lines, faces, line, face;

        Tally reset() {
            lines = faces = line = face = 0;
            return this;
        }

        @Override
        public void line(int argb, double ax, double ay, double az, double bx, double by, double bz) {
            line = argb;
            lines++;
        }

        @Override
        public void triangle(int argb, double ax, double ay, double az, double bx, double by, double bz, double cx,
                double cy, double cz) {
            face = argb;
            faces++;
        }
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
