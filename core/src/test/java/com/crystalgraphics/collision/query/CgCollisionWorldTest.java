package com.crystalgraphics.collision.query;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgCone;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntConsumer;

import static com.crystalgraphics.platform.service.CgEntityQuery.ALIVE;
import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_ITEM;
import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_MONSTER;
import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_OTHER;
import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_PLAYER;
import static com.crystalgraphics.platform.service.CgEntityQuery.KIND_VEHICLE;
import static com.crystalgraphics.platform.service.CgEntityQuery.LIVING;
import static com.crystalgraphics.platform.service.CgEntityQuery.PICKABLE;
import static com.crystalgraphics.platform.service.CgEntityQuery.PLAYER;
import static com.crystalgraphics.platform.service.CgEntityQuery.SPECTATOR;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * World queries over a fake yard: ground under y = 0, a stair, a slab, a fence post and water along z = 5, a wall at
 * x = 24, column x = 100 unloaded; and entities in a ring round (0, 0.9, -20).
 */
public class CgCollisionWorldTest {

    private static final double CX = 0, CY = 0.9, CZ = -20;
    private static final CgShape BLAST = CgSphere.of(5);

    /** Ground below y = 0. Along z = 5: a stair at x 0, a slab at 2, a fence at 4, water at 6. A wall at x = 24. */
    private static final class Yard implements CgWorldQuery {
        @Override public int collisionBoxes(int x, int y, int z, float[] out) {
            if (y < 0) return box(out, 0, 0, 0, 0, 1, 1, 1);
            if (x == 24 && y == 0 && z >= -5 && z <= 5) return box(out, 0, 0, 0, 0, 1, 1, 1);
            if (z != 5 || y != 0) return 0;
            if (x == 0) {
                box(out, 0, 0, 0, 0, 1, 0.5f, 1);
                box(out, 1, 0, 0.5f, 0.5f, 1, 1, 1);
                return 2;
            }
            if (x == 2) return box(out, 0, 0, 0, 0, 1, 0.5f, 1);
            if (x == 4) return box(out, 0, 0.375f, 0, 0.375f, 0.625f, 1.5f, 0.625f);
            return 0;
        }
        private static int box(float[] out, int i, float a, float b, float c, float d, float e, float f) {
            out[i * 6] = a;
            out[i * 6 + 1] = b;
            out[i * 6 + 2] = c;
            out[i * 6 + 3] = d;
            out[i * 6 + 4] = e;
            out[i * 6 + 5] = f;
            return 1;
        }
        @Override public float fluidHeight(int x, int y, int z) {
            return x == 6 && y == 0 && z == 5 ? 8f / 9f : Float.NaN;
        }
        @Override public int fluidKind(int x, int y, int z) {
            return Float.isNaN(fluidHeight(x, y, z)) ? FLUID_NONE : FLUID_WATER;
        }
        @Override public boolean loaded(int x, int z) { return x != 100; }
        @Override public float collisionTop(int x, int y, int z) { return Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return Float.NaN; }
        @Override public int surface(int x, int y, int z) { return SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return Integer.MIN_VALUE; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public int light(int x, int y, int z) { return 0; }
        @Override public int mapColor(int x, int y, int z) { return 0; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
        @Override public int seaLevel() { return 63; }
        @Override public int levelEpoch() { return 1; }
    }

    /** Entities by a table: feet position, half width, height, flags, kind, parent and root vehicle. */
    private static final class Table implements CgEntityQuery {
        final List<double[]> rows = new ArrayList<>();
        final List<int[]> facts = new ArrayList<>();
        int[] ids = new int[0];

        Table add(int id, double x, double y, double z, double half, double height, int flags, int kind, int parent,
                  int root) {
            while (rows.size() <= id) {
                rows.add(null);
                facts.add(null);
            }
            rows.set(id, new double[]{x, y, z, half, height});
            facts.set(id, new int[]{flags, kind, parent, root});
            int[] grown = new int[ids.length + 1];
            System.arraycopy(ids, 0, grown, 0, ids.length);
            grown[ids.length] = id;
            ids = grown;
            return this;
        }

        Table mob(int id, double x, double z, int kind) {
            return add(id, x, 0, z, 0.3, 1.8, ALIVE | LIVING | PICKABLE, kind, id, id);
        }

        private double[] row(int id) {
            return id >= 0 && id < rows.size() ? rows.get(id) : null;
        }

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            double[] r = row(id);
            if (r == null) return false;
            out[X] = r[0];
            out[Y] = r[1];
            out[Z] = r[2];
            out[WIDTH] = r[3] * 2;
            out[HEIGHT] = r[4];
            out[EYE_HEIGHT] = r[4] * 0.9;
            return true;
        }
        @Override public int flags(int id) { return row(id) == null ? 0 : facts.get(id)[0]; }
        @Override public int kind(int id) { return row(id) == null ? KIND_OTHER : facts.get(id)[1]; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) {
            double[] r = row(id);
            if (r == null) return false;
            out.set(r[0] - r[3], r[1], r[2] - r[3], r[0] + r[3], r[1] + r[4], r[2] + r[3]);
            return true;
        }
        @Override public int parent(int id) { return row(id) == null ? -1 : facts.get(id)[2]; }
        @Override public int rootVehicle(int id) { return row(id) == null ? -1 : facts.get(id)[3]; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
            for (int id : ids) {
                double[] r = rows.get(id);
                if (r[0] + r[3] >= minX && r[0] - r[3] <= maxX && r[1] + r[4] >= minY && r[1] <= maxY
                        && r[2] + r[3] >= minZ && r[2] - r[3] <= maxZ) visitor.accept(id);
            }
        }
    }

    /**
     * Round (0, 0.9, -20): monsters 1 (+x, 2 out), 2 (-z, 3), 4 (+z, 5); a boat 3 (-x, 4); a dead mob 9; a dropped
     * item 10; a spectator 11; a dragon 30, not pickable itself, with parts 31 and 32; the caster 40 riding 41 at the
     * centre.
     * Beside the yard's blocks: 50 past the stair, 51 before it; 60 behind the wall.
     */
    private static Table ring() {
        Table t = new Table()
                .mob(1, 2, -20, KIND_MONSTER)
                .mob(2, 0, -23, KIND_MONSTER)
                .add(3, -4, 0, -20, 0.3, 1.8, ALIVE | PICKABLE, KIND_VEHICLE, 3, 3)
                .mob(4, 0, -15, KIND_MONSTER)
                .add(9, 0, 0, -17.95, 0.3, 1.8, LIVING | PICKABLE, KIND_MONSTER, 9, 9)
                .add(10, -2, 0, -20, 0.125, 0.25, ALIVE, KIND_ITEM, 10, 10)
                .add(11, 0, 0, -22.2, 0.3, 1.8, ALIVE | LIVING | PICKABLE | PLAYER | SPECTATOR, KIND_PLAYER, 11, 11)
                .add(30, 3, 0, -17, 1.5, 3, ALIVE | LIVING, KIND_MONSTER, 30, 30)
                .add(31, 2.5, 0, -17.5, 0.5, 1, ALIVE | PICKABLE, KIND_OTHER, 30, 31)
                .add(32, 3.5, 0, -16.5, 0.5, 1, ALIVE | PICKABLE, KIND_OTHER, 30, 32)
                .add(40, 0, 0.5, -20, 0.3, 1.8, ALIVE | LIVING | PICKABLE | PLAYER, KIND_PLAYER, 40, 41)
                .add(41, 0, 0, -20, 0.7, 1.6, ALIVE | LIVING | PICKABLE, KIND_OTHER, 41, 41)
                .mob(50, 0.5, 8, KIND_MONSTER)
                .mob(51, 0.5, 4, KIND_MONSTER)
                .mob(60, 25.5, 0.5, KIND_MONSTER);
        return t;
    }

    private final CgCollisionWorld world = CgCollisionWorld.of(new Yard(), ring());
    private final CgHits hits = new CgHits();
    private final CgHit hit = new CgHit();

    private int[] ids(CgQuery q) {
        int n = q.into(hits);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = hits.get(i).isBlock() ? -1 : hits.entity(i);
        return out;
    }

    // ---- overlaps

    @Test
    public void aBlastFindsWhatAProjectileWouldNearestFirst() {
        assertArrayEquals(new int[]{1, 2, 31, 3, 4}, ids(world.overlap(BLAST).at(CX, CY, CZ).source(40)));
        assertEquals(1.7, hits.distance(0), 1e-9);
        assertEquals("the dragon's part, its dragon the parent", 30, hits.get(2).parent());
        assertEquals(2 * Math.sqrt(2), hits.distance(2), 1e-9);
        CgHit first = hits.get(0);
        assertEquals("the normal points at the target", 1, first.normalX(), 1e-9);
        assertEquals(1.7, first.x(), 1e-9);
        assertEquals(1f, first.falloff(2, 6), 0f);
        assertEquals(1 - (2.7 - 2) / 4, hits.get(1).falloff(2, 6), 1e-6);
    }

    @Test
    public void filtersNarrowAndWidenTheTargets() {
        assertArrayEquals("the boat is not living", new int[]{1, 2, 31, 4},
                ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).living()));
        assertArrayEquals(new int[]{1, 2, 31, 4},
                ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).kinds(1 << KIND_MONSTER)));
        assertArrayEquals(new int[]{1, 10, 2, 31, 3, 4}, ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).items()));
        assertArrayEquals("the dragon itself is nearer than its parts", new int[]{1, 9, 11, 30, 2, 3, 4},
                ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).anything().kinds(~(1 << KIND_ITEM))));
        assertArrayEquals(new int[]{31, 3, 4}, ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).except(1).except(2)));
        assertArrayEquals("excepting the dragon excepts its parts", new int[]{1, 2, 3, 4},
                ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).except(30)));
        assertArrayEquals(new int[]{1, 2}, ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).max(2)));
        assertArrayEquals(new int[]{1, 2, 31, 3},
                ids(world.overlap(BLAST).at(CX, CY, CZ).source(40).where(id -> id != 4)));
        assertEquals("without a source, the rider and the mount", 7, world.overlap(BLAST).at(CX, CY, CZ).into(hits));
        assertEquals("both hold the blast's centre", 0, hits.distance(1), 0);
        assertArrayEquals("aim assist grows every box", new int[]{1, 2, 31, 3, 4},
                ids(world.overlap(CgSphere.of(4.6)).at(CX, CY, CZ).source(40).inflate(0.2)));
        assertArrayEquals(new int[]{1, 2, 31, 3},
                ids(world.overlap(CgSphere.of(4.6)).at(CX, CY, CZ).source(40)));
    }

    @Test
    public void aConeFindsOnlyWhatItFaces() {
        CgShape cleave = CgCone.of(8, Math.toRadians(30));
        assertArrayEquals(new int[]{4}, ids(world.overlap(cleave).at(CX, CY, CZ).facing(0, 0).source(40)));
        assertArrayEquals("yaw 90 faces -x", new int[]{3},
                ids(world.overlap(cleave).at(CX, CY, CZ).facing(Math.PI / 2, 0).source(40)));
        assertArrayEquals(new int[]{1}, ids(world.overlap(cleave).at(CX, CY, CZ).facing(-Math.PI / 2, 0).source(40)));
    }

    @Test
    public void aBoxIsItselfAQueryShape() {
        assertArrayEquals(new int[]{1}, ids(world.overlap(CgAABB.of(1.5, 0, -20.5, 2.5, 2, -19.5))));
        assertTrue(world.overlap(CgAABB.of(1.5, 0, -20.5, 2.5, 2, -19.5)).any());
        assertEquals(0, world.overlap(CgAABB.of(10, 0, -20.5, 11, 2, -19.5)).count());
    }

    @Test
    public void blocksAreHitByTheirRealShapes() {
        CgShape small = CgSphere.of(0.2);
        assertArrayEquals("the stair, by both its boxes", new int[]{-1},
                ids(world.overlap(small).at(0.5, 0.6, 5.75).blocksOnly()));
        assertEquals(0, world.overlap(small).at(0.5, 0.8, 5.2).blocksOnly().count());
        assertArrayEquals("the fence reaches into the cell above it", new int[]{-1},
                ids(world.overlap(small).at(4.5, 1.4, 5.5).blocksOnly()));
        assertEquals(4, hits.get(0).blockX());
        assertEquals(0, hits.get(0).blockY());
        assertEquals("a block is hit once, by its nearest box", 1,
                world.overlap(CgSphere.of(0.3)).at(0.5, 0.6, 5.5).blocksOnly().count());
        assertEquals("an unloaded column is left out", 0,
                world.overlap(CgSphere.of(1)).at(100.5, 3, 0.5).blocksOnly().count());
    }

    // ---- rays and casts

    @Test
    public void aRayStopsOnAStairsRealStep() {
        assertTrue(world.ray().at(0.5, 0.75, 3).along(0, 0, 1, 10).blocksOnly().first(hit));
        assertEquals(5.5, hit.z(), 1e-9);
        assertEquals(2.5, hit.distance(), 1e-9);
        assertEquals(0.25, hit.fraction(), 1e-9);
        assertEquals(1, hit.normalZ(), 0);
        assertEquals(5, hit.blockZ());
        assertTrue(world.ray().at(0.5, 0.25, 3).along(0, 0, 1, 10).blocksOnly().first(hit));
        assertEquals("under the step, the low half", 5.0, hit.z(), 1e-9);
        assertTrue(world.ray().at(0.5, 0.75, 7).along(0, 0, -1, 10).blocksOnly().first(hit));
        assertEquals(6.0, hit.z(), 1e-9);
        assertEquals(-1, hit.normalZ(), 0);
        assertTrue(world.ray().at(2.5, 3, 5.5).along(0, -2, 0, 10).blocksOnly().first(hit));
        assertEquals("a slab's top", 0.5, hit.y(), 1e-9);
        assertEquals(-1, hit.normalY(), 0);
    }

    @Test
    public void aRayStopsAtTheFirstBlockAndNothingBeyondIt() {
        assertArrayEquals(new int[]{51, -1}, ids(world.ray().at(0.5, 0.75, 3).along(0, 0, 1, 10).blocksToo()));
        assertEquals(0.7, hits.distance(0), 1e-9);
        assertEquals(1, hits.get(0).normalZ(), 0);
        assertArrayEquals("entities alone pass the stair", new int[]{51, 50},
                ids(world.ray().at(0.5, 0.75, 3).along(0, 0, 1, 10)));
    }

    @Test
    public void unloadedCellsAndFluidsStopARayWhenAsked() {
        assertTrue(world.ray().at(98.5, 0.5, 0.5).along(1, 0, 0, 10).blocksOnly().first(hit));
        assertTrue(hit.unloaded());
        assertEquals(1.5, hit.distance(), 1e-9);
        assertTrue(world.ray().at(6.5, 3, 5.5).along(0, -1, 0, 10).fluids().first(hit));
        assertEquals(CgWorldQuery.FLUID_WATER, hit.fluid());
        assertEquals(8.0 / 9.0, hit.y(), 1e-6);
        assertTrue(world.ray().at(6.5, 3, 5.5).along(0, -1, 0, 10).blocksOnly().first(hit));
        assertEquals("without fluids, the ground", 0.0, hit.y(), 1e-9);
        assertFalse(world.ray().at(6.5, 3, 5.5).along(0, -1, 0, 2).blocksOnly().any());
    }

    @Test
    public void aCastMeetsTheFirstThingItsShapeTouches() {
        CgShape bolt = CgSphere.of(0.2);
        assertArrayEquals(new int[]{51, -1}, ids(world.cast(bolt).at(0.5, 0.75, 3).along(0, 0, 1, 10).blocksToo()));
        assertEquals(0.5, hits.distance(0), 1e-6);
        assertEquals("the step, by the bolt's edge", 2.3, hits.distance(1), 1e-6);
        assertEquals(1, hits.get(1).normalZ(), 1e-6);
        assertTrue(world.cast(CgSphere.of(0.25)).at(2.5, 1.2, 5.5).along(1, 0, 0, 3).blocksOnly().first(hit));
        assertEquals("the fence's top half, from the cell above", 4.125 - 2.5, hit.distance(), 1e-6);
        assertEquals(4, hit.blockX());
    }

    @Test
    public void misuseFailsAtTheCall() {
        try {
            world.overlap(BLAST).along(1, 0, 0, 3);
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            world.ray().along(0, 0, 0, 3);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            world.ray().at(0, 0, 0).count();
            fail();
        } catch (IllegalStateException expected) {
        }
        try {
            world.overlap(CgAABB.of(0, 0, 0, 1, 1, 1)).at(3, 3, 3);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    // ---- sight

    @Test
    public void lineOfSightAndExposureLookPastBlocks() {
        assertTrue(world.lineOfSight(15.5, 3, 0.5, 60));
        assertFalse("from behind the wall's top, low", world.lineOfSight(23.5, 0.5, 0.5, 60));
        // Minecraft's grid over a 0.6 x 1.8 box: 3 x 5 x 3 points; the wall hides the rows at y 0, 0.39 and 0.78
        assertEquals(18f / 45f, world.exposure(15.5, 0.5, 0.5, 60), 0f);
        assertEquals(1f, world.exposure(25.5, 10, 0.5, 60), 0f);
        assertEquals(0f, world.exposure(15.5, 0.5, 0.5, 999), 0f);
        assertArrayEquals(new int[]{60}, ids(world.overlap(CgSphere.of(3)).at(23.5, 0.5, 0.5)));
        assertArrayEquals("its centre is behind the wall", new int[0],
                ids(world.overlap(CgSphere.of(3)).at(23.5, 0.5, 0.5).visible()));
        world.overlap(CgSphere.of(3)).at(15.5, 0.5, 0.5).exposure().into(hits);
        assertTrue(hits.isEmpty());
        world.overlap(CgSphere.of(11)).at(15.5, 0.5, 0.5).exposure().into(hits);
        assertEquals(60, hits.entity(0));
        assertEquals(0.4f, hits.get(0).exposure(), 1e-6f);
    }

    // ---- shapes, nesting

    @Test
    public void aShapeWorldTestsTheShapesNotTheirBoxes() {
        CgShapeWorld arena = CgCollisionWorld.shapes();
        CgPose pose = new CgPose();
        arena.add(1, CgSphere.of(1), pose.at(1.5, 0, 0));
        arena.add(2, CgSphere.of(1), pose.at(1.6, 1.6, 0));
        arena.add(3, CgSphere.of(0.5), pose.at(-3, 0, 0));
        assertArrayEquals("2's box overlaps, its sphere does not", new int[]{1},
                ids(arena.overlap(CgSphere.of(1)).at(0, 0, 0)));
        assertEquals(0.5, hits.distance(0), 1e-9);
        assertTrue(arena.ray().at(-10, 0, 0).along(1, 0, 0, 20).first(hit));
        assertEquals(3, hit.entity());
        assertEquals(6.5, hit.distance(), 1e-9);
        assertEquals("toward the target", 1, hit.normalX(), 1e-9);
        assertTrue(arena.cast(CgSphere.of(0.5)).at(-10, 0, 0).along(1, 0, 0, 20).first(hit));
        assertEquals(6, hit.distance(), 1e-6);
        arena.move(3, pose.at(-3, 5, 0));
        assertTrue(arena.ray().at(-10, 0, 0).along(1, 0, 0, 20).first(hit));
        assertEquals(1, hit.entity());
        assertTrue(arena.remove(1));
        assertFalse(arena.remove(1));
        assertEquals(2, arena.size());
    }

    @Test
    public void queriesNestInsideAVisitorFourDeep() {
        int[] inner = new int[1];
        world.overlap(BLAST).at(CX, CY, CZ).source(40).forEach(h -> inner[0] += world.overlap(CgSphere.of(0.5))
                .at(h.x(), h.y(), h.z()).anything().count());
        assertTrue(inner[0] >= 5);
        try {
            world.overlap(BLAST).at(CX, CY, CZ).forEach(a -> world.overlap(BLAST).at(CX, CY, CZ).forEach(
                    b -> world.overlap(BLAST).at(CX, CY, CZ).forEach(c -> world.overlap(BLAST).at(CX, CY, CZ)
                            .forEach(d -> world.overlap(BLAST)))));
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals("the world is usable after", 5, world.overlap(BLAST).at(CX, CY, CZ).source(40).count());
    }

    // ---- cost

    @Test
    public void aBlastAmongFiftyEntitiesIsFastAndAllocatesNothing() {
        Table crowd = new Table();
        Random random = new Random(7);
        for (int id = 0; id < 50; id++) {
            double a = random.nextDouble() * Math.PI * 2, r = random.nextDouble() * 9;
            crowd.mob(id, Math.cos(a) * r, Math.sin(a) * r, KIND_MONSTER);
        }
        CgCollisionWorld w = CgCollisionWorld.of(new Yard(), crowd);
        CgShape blast = CgSphere.of(6);
        CgHits out = new CgHits();
        int expected = w.overlap(blast).at(0, 0.9, 0).into(out);
        assertTrue(expected > 10 && expected < 50);
        for (int i = 0; i < 200_000; i++) w.overlap(blast).at(0, 0.9, 0).into(out);
        for (int i = 0; i < 20_000; i++) w.ray().at(0.5, 0.75, 3).along(0, 0, 1, 10).blocksToo().into(out);

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long quiet = Long.MAX_VALUE, best = Long.MAX_VALUE;
        int n = 100_000;
        for (int window = 0; window < 3; window++) {
            long before = threads.getThreadAllocatedBytes(thread);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) w.overlap(blast).at(0, 0.9, 0).into(out);
            best = Math.min(best, System.nanoTime() - t0);
            quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - before);
        }
        long rayBytes = threads.getThreadAllocatedBytes(thread);
        long r0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) w.ray().at(0.5, 0.75, 3).along(0, 0, 1, 10).blocksToo().into(out);
        long rayNs = (System.nanoTime() - r0) / 20_000;
        rayBytes = threads.getThreadAllocatedBytes(thread) - rayBytes;
        System.out.println("[collision] a 6-block blast among 50 entities (" + expected + " hit): " + best / n
                + " ns, " + quiet + " B over " + n + "; a 10-block ray through the yard's cells: " + rayNs + " ns, "
                + rayBytes + " B over 20,000");
        assertEquals("bytes over " + n + " blasts", 0, quiet);
        assertEquals("bytes over 20,000 rays", 0, rayBytes);
        assertTrue("a blast took " + best / n + " ns", best / n < 5_000);
    }
}
