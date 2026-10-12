package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import static com.crystalgraphics.platform.service.CgEntityQuery.ALIVE;
import static com.crystalgraphics.platform.service.CgEntityQuery.LIVING;
import static com.crystalgraphics.platform.service.CgEntityQuery.PICKABLE;

/** A world's blocks and entities for collision tests, answered from code. */
public final class CollisionFakes {

    private CollisionFakes() {
    }

    /** Ground below y = 0. Along z = 5: a stair at x 0, a slab at 2, a fence at 4, water at 6. A wall at x = 24. */
    public static final class Yard implements CgWorldQuery {
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
    public static final class Table implements CgEntityQuery {
        final List<double[]> rows = new ArrayList<>();
        final List<int[]> facts = new ArrayList<>();
        final Map<Integer, double[]> looks = new HashMap<>();
        int[] ids = new int[0];
        private static final double[] NO_LOOK = {0, 0};

        public Table add(int id, double x, double y, double z, double half, double height, int flags, int kind,
                         int parent, int root) {
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

        public Table mob(int id, double x, double z, int kind) {
            return add(id, x, 0, z, 0.3, 1.8, ALIVE | LIVING | PICKABLE, kind, id, id);
        }

        /** Moves {@code id}'s feet to {@code (x, y, z)}. */
        public Table move(int id, double x, double y, double z) {
            double[] r = rows.get(id);
            r[0] = x;
            r[1] = y;
            r[2] = z;
            return this;
        }

        /** Turns {@code id}: its look and its body, in Minecraft's degrees. */
        public Table look(int id, double yaw, double pitch) {
            looks.put(id, new double[]{yaw, pitch});
            return this;
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
            double[] look = looks.getOrDefault(id, NO_LOOK);
            out[YAW] = out[BODY_YAW] = out[HEAD_YAW] = look[0];
            out[PITCH] = look[1];
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
}
