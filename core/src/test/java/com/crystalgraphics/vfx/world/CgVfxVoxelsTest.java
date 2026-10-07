package com.crystalgraphics.vfx.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.*;

public class CgVfxVoxelsTest {

    /** A full block at (1, 2, 3), a bottom slab at (2, 2, 3), a stair at (3, 2, 3), half-full water at (4, 2, 3). */
    private static final class World implements CgWorldQuery {
        boolean holeAt7;

        @Override public int collisionBoxes(int x, int y, int z, float[] out) {
            if (y != 2 || z != 3) return 0;
            switch (x) {
                case 1: return box(out, 0, 0, 0, 0, 1, 1, 1);
                case 2: return box(out, 0, 0, 0, 0, 1, 0.5f, 1);
                case 3: box(out, 0, 0, 0, 0, 1, 0.5f, 1); box(out, 1, 0, 0.5f, 0.5f, 1, 1, 1); return 2;
                default: return 0;
            }
        }

        private static int box(float[] out, int b, float x0, float y0, float z0, float x1, float y1, float z1) {
            int i = b * 6;
            out[i] = x0; out[i + 1] = y0; out[i + 2] = z0; out[i + 3] = x1; out[i + 4] = y1; out[i + 5] = z1;
            return 1;
        }

        @Override public float fluidHeight(int x, int y, int z) { return x == 4 && y == 2 && z == 3 ? 0.5f : Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return x == 4 && y == 2 && z == 3 ? FLUID_WATER : FLUID_NONE; }
        @Override public int light(int x, int y, int z) { return (x & 15) | (y & 15) << 4; }
        @Override public boolean loaded(int x, int z) { return !(holeAt7 && x == 7 && z == 7); }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 10; }
        @Override public int levelEpoch() { return 1; }
        @Override public float collisionTop(int x, int y, int z) { return Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return Float.NaN; }
        @Override public int surface(int x, int y, int z) { return SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return 0; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public int mapColor(int x, int y, int z) { return 0; }
        @Override public int seaLevel() { return 0; }
    }

    private static int at(int x, int y, int z) {
        return (x + 16 * (y + 16 * z)) * 4;
    }

    @Test
    public void packsOctantsLightAndFluid() {
        World world = new World();
        ByteBuffer out = ByteBuffer.allocate(CgVfxVoxels.SECTION_BYTES);
        assertTrue(CgVfxVoxels.pack(world, 0, 0, 0, out, new float[CgVfxVoxels.BOXES * 6]));
        assertEquals(0xFF, CgVfxVoxels.octants(out.get(at(1, 2, 3))));
        assertEquals("a bottom slab: the four lower octants", 0b0011_0011, CgVfxVoxels.octants(out.get(at(2, 2, 3))));
        assertEquals("a stair: the slab and the upper back", 0b1111_0011, CgVfxVoxels.octants(out.get(at(3, 2, 3))));
        assertEquals(0, CgVfxVoxels.octants(out.get(at(5, 2, 3))));
        assertEquals(5, CgVfxVoxels.light(out.get(at(5, 2, 3) + 1)));
        assertEquals(2, CgVfxVoxels.light(out.get(at(5, 2, 3) + 2)));
        assertEquals(CgWorldQuery.FLUID_WATER, CgVfxVoxels.fluidKind(out.get(at(4, 2, 3) + 3)));
        assertEquals(0.5f, CgVfxVoxels.fluidHeight(out.get(at(4, 2, 3) + 3)), 1f / 63f);
        assertEquals("above the level: open sky", 15, CgVfxVoxels.light(out.get(at(0, 12, 0) + 2)));
        assertEquals("below the level: dark", 0, CgVfxVoxels.light(out.get(at(0, 12, 0) + 1)));
    }

    @Test
    public void refusesASectionWithAnUnloadedColumn() {
        World world = new World();
        world.holeAt7 = true;
        assertFalse(CgVfxVoxels.pack(world, 0, 0, 0, ByteBuffer.allocate(CgVfxVoxels.SECTION_BYTES),
                new float[CgVfxVoxels.BOXES * 6]));
    }
}
