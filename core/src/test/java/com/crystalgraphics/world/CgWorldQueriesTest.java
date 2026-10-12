package com.crystalgraphics.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import org.junit.Test;

import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The scans and the raycast against a fake superflat world: grass top at y = -60, a slab and a top slab on it. */
public class CgWorldQueriesTest {

    /** Solid below y = -60; a bottom slab at (5, -60, 0); a top slab at (0, -55, 0); column x = 100 unloaded. */
    private static final CgWorldQuery FLAT = new CgWorldQuery() {
        @Override public float collisionTop(int x, int y, int z) {
            if (x == 100) return Float.NaN;
            if (y < -60) return 1f;
            if (x == 5 && y == -60 && z == 0) return 0.5f;
            if (x == 0 && y == -55 && z == 0) return 1f;
            return Float.NaN;
        }
        @Override public float collisionBottom(int x, int y, int z) {
            if (Float.isNaN(collisionTop(x, y, z))) return Float.NaN;
            return x == 0 && y == -55 && z == 0 ? 0.5f : 0f;
        }
        @Override public int collisionBoxes(int x, int y, int z, float[] out) { return 0; }
        @Override public int surface(int x, int y, int z) { return SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return Integer.MIN_VALUE; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int light(int x, int y, int z) { return 0; }
        @Override public int mapColor(int x, int y, int z) { return 0; }
        @Override public boolean loaded(int x, int z) { return x != 100; }
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
        @Override public int seaLevel() { return -63; }
        @Override public int levelEpoch() { return 1; }
    };

    @Test
    public void groundIsTheSurfaceUnderThePoint() {
        assertEquals(-60.0, CgWorldQueries.groundBelow(FLAT, 3.5, -50.2, 3.5, 64), 1e-9);
        assertEquals("a slab is stood on at its own height", -59.5, CgWorldQueries.groundBelow(FLAT, 5.5, -50, 0.5, 64), 1e-9);
        assertEquals("a point sunk into a block answers its top", -60.0, CgWorldQueries.groundBelow(FLAT, 3.5, -60.4, 3.5, 64), 1e-9);
        assertTrue("too deep", Double.isNaN(CgWorldQueries.groundBelow(FLAT, 3.5, -40, 3.5, 10)));
        assertTrue("unloaded", Double.isNaN(CgWorldQueries.groundBelow(FLAT, 100.5, -50, 0.5, 64)));
        assertTrue("no level", Double.isNaN(CgWorldQueries.groundBelow(CgWorldQuery.NONE, 3.5, -50, 3.5, 64)));
    }

    @Test
    public void aTopSlabIsACeilingNotAFloorFromUnderIt() {
        assertEquals(-60.0, CgWorldQueries.groundBelow(FLAT, 0.5, -55.2, 0.5, 64), 1e-9);
        assertEquals(-54.5, CgWorldQueries.ceilingAbove(FLAT, 0.5, -58, 0.5, 64), 1e-9);
        assertEquals("from on top of it, it is the floor", -54.0, CgWorldQueries.groundBelow(FLAT, 0.5, -53, 0.5, 64), 1e-9);
    }

    @Test
    public void aRayStopsAtTheGroundFacingUp() {
        CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
        assertTrue(CgWorldQueries.raycast(FLAT, 2.5, -50, 2.5, 1, -1, 0, 100, hit));
        assertEquals(-60.0, hit.y, 1e-9);
        assertEquals(12.5, hit.x, 1e-9);
        assertEquals(1f, hit.normalY, 0f);
        assertEquals(-61, hit.blockY);
        assertEquals(10.0 * Math.sqrt(2.0), hit.distance, 1e-9);
    }

    @Test
    public void aRayMeetsASlabAtItsTopAndAWallAtItsSide() {
        CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
        assertTrue(CgWorldQueries.raycast(FLAT, 5.5, -50, 0.5, 0, -1, 0, 100, hit));
        assertEquals(-59.5, hit.y, 1e-9);
        assertEquals(1f, hit.normalY, 0f);
        assertTrue("sideways into the top slab", CgWorldQueries.raycast(FLAT, -3.5, -54.25, 0.5, 1, 0, 0, 100, hit));
        assertEquals(0.0, hit.x, 1e-9);
        assertEquals(-1f, hit.normalX, 0f);
        assertFalse("under the top slab, sideways, it passes", CgWorldQueries.raycast(FLAT, -3.5, -54.75, 0.5, 1, 0, 0, 20, hit));
    }

    @Test
    public void aRayMissesWithinItsRangeOrOffTheLoadedWorld() {
        CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
        assertFalse(CgWorldQueries.raycast(FLAT, 2.5, -50, 2.5, 0, -1, 0, 5, hit));
        assertFalse(CgWorldQueries.raycast(FLAT, 98.5, -59.5, 0.5, 1, 0, 0, 10, hit));
        assertFalse(CgWorldQueries.raycast(CgWorldQuery.NONE, 2.5, -50, 2.5, 0, -1, 0, 100, hit));
    }

    /** On the flat world: the caster (1), a zombie (2), a dropped item (3), a dead mob (4), and a mob 30 out (5). */
    private static final class Crowd implements CgEntityQuery {
        private static final double[][] AT = {{}, {0.5, -60, 0.5}, {10, -60, 0.5}, {5, -60, 0.5}, {7, -60, 0.5}, {30, -60, 3}};

        @Override public int localPlayer() { return 1; }
        @Override public int cameraEntity() { return 1; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            out[X] = AT[id][0];
            out[Y] = AT[id][1];
            out[Z] = AT[id][2];
            out[WIDTH] = 0.6;
            out[HEIGHT] = 1.95;
            return true;
        }
        @Override public int flags(int id) { return id == 4 ? 0 : ALIVE | LIVING; }
        @Override public int kind(int id) { return id == 3 ? KIND_ITEM : id == 1 ? KIND_PLAYER : KIND_MONSTER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
            for (int id = 1; id < AT.length; id++) {
                double[] a = AT[id];
                if (a[0] + 0.3 >= minX && a[0] - 0.3 <= maxX && a[1] + 1.95 >= minY && a[1] <= maxY
                        && a[2] + 0.3 >= minZ && a[2] - 0.3 <= maxZ) visitor.accept(id);
            }
        }
    }

    @Test
    public void aThickRayMeetsTheFirstLivingEntityPastItsCaster() {
        CgPlatform.provide(CgEntityQuery.SERVICE, new Crowd());
        try {
            CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
            assertTrue(CgWorldQueries.raycastEntities(0.5, -59, 0.5, 1, 0, 0, 100, 0.3, 1, hit));
            assertEquals("past the caster, the item and the dead: the zombie", 2, hit.entity);
            assertEquals("its box grown by the radius", 10 - 0.3 - 0.3, hit.x, 1e-9);
            assertEquals(-1f, hit.normalX, 0f);
            assertEquals("unignored, the caster it starts inside", 1,
                    CgWorldQueries.raycastEntities(0.5, -59, 0.5, 1, 0, 0, 100, 0.3, -1, hit) ? hit.entity : -9);
            assertTrue("found past the first segments", CgWorldQueries.raycastEntities(0.5, -59, 3, 1, 0, 0, 100, 0.3, 1, hit));
            assertEquals(5, hit.entity);
            assertEquals(30 - 0.6, hit.x, 1e-9);
            assertFalse("out of range", CgWorldQueries.raycastEntities(0.5, -59, 3, 1, 0, 0, 20, 0.3, 1, hit));
        } finally {
            CgPlatform.provide(CgEntityQuery.SERVICE, null);
        }
    }

    @Test
    public void raycastAllTakesTheNearerOfABlockAndAnEntity() {
        CgPlatform.provide(CgEntityQuery.SERVICE, new Crowd());
        CgPlatform.provide(CgWorldQuery.SERVICE, FLAT);
        try {
            CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
            assertTrue(CgWorldQueries.raycastAll(0.5, -59.8, 0.5, 1, 0, 0, 100, 0.3, 1, hit));
            assertEquals("the slab in front of the zombie", -1, hit.entity);
            assertEquals(5.0, hit.x, 1e-9);
            assertTrue(CgWorldQueries.raycastAll(0.5, -59, 0.5, 1, 0, 0, 100, 0.3, 1, hit));
            assertEquals("over the slab: the zombie", 2, hit.entity);
        } finally {
            CgPlatform.provide(CgEntityQuery.SERVICE, null);
            CgPlatform.provide(CgWorldQuery.SERVICE, null);
        }
    }

    /** A server's level, on a thread of its own, with nothing in the client slots. */
    @Test
    public void raycastAllGivenALevelsQueriesNeedsNoSlotOrRenderThread() throws Exception {
        CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
        boolean[] found = new boolean[2];
        Thread server = new Thread(() -> {
            found[0] = CgWorldQueries.raycastAll(FLAT, new Crowd(), 0.5, -59.8, 0.5, 1, 0, 0, 100, 0.3, 1, hit)
                    && hit.entity == -1;
            found[1] = CgWorldQueries.raycastAll(FLAT, new Crowd(), 0.5, -59, 0.5, 1, 0, 0, 100, 0.3, 1, hit)
                    && hit.entity == 2;
        });
        server.start();
        server.join();

        assertTrue("the slab", found[0]);
        assertTrue("the zombie", found[1]);
    }
}
