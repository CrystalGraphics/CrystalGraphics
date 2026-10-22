package com.crystalgraphics.render.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgWorldQuery;
import org.junit.Test;

import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;

/** The boxes Step kernels collide with: the living near the camera, nearest first, relative to the camera's block. */
public class CgWorldEntityBoxesTest {

    /** id i stands at x = AT[i]; 3 is an item, 4 dead; the rest mobs. */
    private static final double[] AT = {0, 10.5, 2.5, 3.5, 4.5, -6.5};

    private static final class Line implements CgEntityQuery {
        int asked;

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            out[X] = AT[id];
            out[Y] = 64;
            out[Z] = 0.5;
            out[WIDTH] = 1.0;
            out[HEIGHT] = 2.0;
            return true;
        }
        @Override public int flags(int id) { return id == 4 ? 0 : ALIVE | LIVING; }
        @Override public int kind(int id) { return id == 3 ? KIND_ITEM : KIND_MONSTER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
            asked++;
            for (int id = 1; id < AT.length; id++) if (AT[id] >= minX && AT[id] <= maxX) visitor.accept(id);
        }
    }

    @Test
    public void theLivingNearestFirstInTheCamerasBlockFrame() {
        Line line = new Line();
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(CgWorldQuery.NONE, line));
        try {
            CgWorldEntityBoxes.around(0.25, 65.5, 0.75, 0f);
            CgWorldEntityBoxes.gather();
            assertEquals("items and the dead left out", 3, CgWorldEntityBoxes.count());
            int[] w = CgWorldEntityBoxes.words();
            assertEquals("the nearest first", 2, w[3]);
            assertEquals(5, w[11]);
            assertEquals(1, w[19]);
            assertEquals("min x, from block x 0", 2.0f, Float.intBitsToFloat(w[0]), 0f);
            assertEquals("feet, from block y 65", -1.0f, Float.intBitsToFloat(w[1]), 0f);
            assertEquals("head", 1.0f, Float.intBitsToFloat(w[5]), 0f);
            assertEquals(3.0f, Float.intBitsToFloat(w[4]), 0f);

            CgWorldEntityBoxes.gather();
            assertEquals("not placed again: nothing gathered", 0, CgWorldEntityBoxes.count());
            assertEquals(1, line.asked);
        } finally {
            CgPlatform.provide(CgLevels.SERVICE, null);
        }
    }

    @Test
    public void pastTheMostTheFarthestGo() {
        double[] many = new double[CgWorldEntityBoxes.MAX + 10];
        CgEntityQuery crowd = new CgEntityQuery() {
            @Override public int localPlayer() { return -1; }
            @Override public int cameraEntity() { return -1; }
            @Override public boolean pose(int id, float partialTick, double[] out) {
                out[X] = id;
                out[Y] = 0;
                out[Z] = 0;
                out[WIDTH] = 0.6;
                out[HEIGHT] = 1.8;
                return true;
            }
            @Override public int flags(int id) { return ALIVE; }
            @Override public int kind(int id) { return KIND_ANIMAL; }
            @Override public boolean box(int id, float partialTick, CgBoxSink out) { return false; }
            @Override public int parent(int id) { return id; }
            @Override public int rootVehicle(int id) { return id; }
            @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                         IntConsumer visitor) {
                for (int id = many.length - 1; id >= 1; id--) visitor.accept(id);   // farthest first
            }
        };
        CgPlatform.provide(CgLevels.SERVICE, CgLevels.single(CgWorldQuery.NONE, crowd));
        try {
            CgWorldEntityBoxes.around(0, 0, 0, 0f);
            CgWorldEntityBoxes.gather();
            assertEquals(CgWorldEntityBoxes.MAX, CgWorldEntityBoxes.count());
            int[] w = CgWorldEntityBoxes.words();
            for (int i = 0; i < CgWorldEntityBoxes.MAX; i++) assertEquals(i + 1, w[i * 8 + 3]);
        } finally {
            CgPlatform.provide(CgLevels.SERVICE, null);
        }
    }
}
