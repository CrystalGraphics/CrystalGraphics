package com.crystalgraphics.world;

import com.crystalgraphics.platform.world.CgWorldQuery;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The scans against a fake superflat world: grass top at y = -60, a slab and a top slab on it. */
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
}
