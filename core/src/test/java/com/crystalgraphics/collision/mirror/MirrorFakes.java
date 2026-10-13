package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.collision.CollisionFakes;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldQuery;

import java.util.HashMap;
import java.util.Map;

/**
 * A level of chunks whose block writes reach a mirror's hook as a host's mixin makes them, a host answering the mirror
 * for it, and the live answers to compare a version with. Columns are 4 sections high (y 0 to 63).
 *
 * <p>States: {@link #AIR} (null), {@link #STONE}, {@link #SLAB}, {@link #WATER} (stands a full block under water),
 * {@link #DYNAMIC} (its height follows {@link Level#phase}, changed with no write) and {@link #FENCE} (as wide as its
 * fence neighbours make it on a positional-only host; a fixed post otherwise).</p>
 */
final class MirrorFakes {

    static final int SECTIONS = 4;
    static final Integer AIR = null, STONE = 1, SLAB = 2, WATER = 3, DYNAMIC = 4, FENCE = 5;
    static final Integer[] SOLIDS = {STONE, SLAB, WATER, DYNAMIC, FENCE};

    private MirrorFakes() {
    }

    /** One chunk: sections of states, and the field a host's mixin adds. */
    static final class Chunk {
        final Level level;
        final int chunkX, chunkZ;
        final Integer[][] sections = new Integer[SECTIONS][4096];
        volatile CgMirrorColumn column;

        Chunk(Level level, int chunkX, int chunkZ) {
            this.level = level;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        void set(int x, int y, int z, Integer state) {
            CgMirrorColumn c = column;
            if (c != null) c.write(x, y, z, state, DYNAMIC.equals(state));
            sections[y >> 4][(y & 15) << 8 | (z & 15) << 4 | (x & 15)] = state;
        }
    }

    /** The level: its loaded chunks, and the live answers. */
    static final class Level implements CgWorldQuery {
        final Map<Long, Chunk> chunks = new HashMap<>();
        final Map<Long, Integer> phases = new HashMap<>();
        final boolean positionalOnly;

        Level(boolean positionalOnly) {
            this.positionalOnly = positionalOnly;
        }

        Chunk load(int chunkX, int chunkZ) {
            return chunks.computeIfAbsent(CgLevelMirror.key(chunkX, chunkZ), k -> new Chunk(this, chunkX, chunkZ));
        }

        void unload(int chunkX, int chunkZ) {
            Chunk c = chunks.remove(CgLevelMirror.key(chunkX, chunkZ));
            if (c != null && c.column != null) c.column.unloaded();
        }

        void set(int x, int y, int z, Integer state) {
            Chunk c = chunks.get(CgLevelMirror.key(x >> 4, z >> 4));
            if (c != null) c.set(x, y, z, state);
        }

        Integer get(int x, int y, int z) {
            if (y < 0 || y >= SECTIONS * 16) return AIR;
            Chunk c = chunks.get(CgLevelMirror.key(x >> 4, z >> 4));
            return c == null ? AIR : c.sections[y >> 4][(y & 15) << 8 | (z & 15) << 4 | (x & 15)];
        }

        /** A dynamic block's height, changed with no write. */
        void phase(int x, int y, int z, int phase) {
            phases.put(pos(x, y, z), phase);
        }

        int phaseAt(int x, int y, int z) {
            Integer p = phases.get(pos(x, y, z));
            return p == null ? 0 : p;
        }

        private static long pos(int x, int y, int z) {
            return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | y & 0xFFF;
        }

        /** Block (x, y, z)'s shape where it stands. */
        void shapeAt(int x, int y, int z, CgMirrorShape out) {
            Integer s = get(x, y, z);
            describeState(s, out, positionalOnly);
            if (DYNAMIC.equals(s)) {
                out.clear().box(0, 0, 0, 1, (phaseAt(x, y, z) % 16 + 1) / 16.0, 1).dynamic();
            } else if (FENCE.equals(s) && positionalOnly) {
                int n = 0;
                if (FENCE.equals(get(x + 1, y, z))) n++;
                if (FENCE.equals(get(x - 1, y, z))) n++;
                if (FENCE.equals(get(x, y, z + 1))) n++;
                if (FENCE.equals(get(x, y, z - 1))) n++;
                out.clear().box(0.375 - n * 0.05, 0, 0.375, 0.625 + n * 0.05, 1.5, 0.625).positional();
            } else if (positionalOnly && s != null) {
                out.positional();
            }
        }

        @Override
        public int collisionBoxes(int x, int y, int z, float[] out) {
            if (!loaded(x, z)) return 0;
            CgMirrorShape s = new CgMirrorShape();
            shapeAt(x, y, z, s);
            System.arraycopy(s.boxes, 0, out, 0, Math.min(s.count, out.length / 6) * 6);
            return s.count;
        }

        @Override
        public float collisionTop(int x, int y, int z) {
            CgMirrorShape s = new CgMirrorShape();
            if (loaded(x, z)) shapeAt(x, y, z, s);
            float t = Float.NaN;
            for (int i = 0; i < s.count; i++) t = Float.isNaN(t) ? s.boxes[i * 6 + 4] : Math.max(t, s.boxes[i * 6 + 4]);
            return t;
        }

        @Override
        public float collisionBottom(int x, int y, int z) {
            CgMirrorShape s = new CgMirrorShape();
            if (loaded(x, z)) shapeAt(x, y, z, s);
            float b = Float.NaN;
            for (int i = 0; i < s.count; i++) b = Float.isNaN(b) ? s.boxes[i * 6 + 1] : Math.min(b, s.boxes[i * 6 + 1]);
            return b;
        }

        @Override
        public float fluidHeight(int x, int y, int z) {
            if (!loaded(x, z) || !WATER.equals(get(x, y, z))) return Float.NaN;
            return WATER.equals(get(x, y + 1, z)) ? 1f : 8f / 9f;
        }

        @Override
        public int fluidKind(int x, int y, int z) {
            return loaded(x, z) && WATER.equals(get(x, y, z)) ? FLUID_WATER : FLUID_NONE;
        }

        @Override
        public boolean loaded(int x, int z) {
            return chunks.containsKey(CgLevelMirror.key(x >> 4, z >> 4));
        }

        @Override public boolean border(double[] out) { return false; }
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
        @Override public int minY() { return 0; }
        @Override public int maxY() { return SECTIONS * 16; }
        @Override public int seaLevel() { return 32; }
        @Override public int levelEpoch() { return 1; }
    }

    /** What a state is wherever it stands; a dynamic or (positional-only) fence one only says it is positional. */
    static void describeState(Integer s, CgMirrorShape out, boolean positionalOnly) {
        out.clear();
        if (s == null) return;
        switch (s) {
            case 1: out.box(0, 0, 0, 1, 1, 1); break;
            case 2: out.box(0, 0, 0, 1, 0.5, 1); break;
            case 3: out.fluid(CgWorldQuery.FLUID_WATER, 8f / 9f, true); break;
            case 4: out.positional(); break;
            case 5: out.box(0.375, 0, 0.375, 0.625, 1.5, 0.625); break;
            default: throw new IllegalArgumentException("state " + s);
        }
        if (positionalOnly) out.positional();
    }

    /** The host: the fake level's chunks, copies and shapes, as a Minecraft host answers. */
    static final class Host implements CgMirrorHost {
        @Override public boolean mirrors(Object level) { return level instanceof Level; }
        @Override public int minSection(Object level) { return 0; }
        @Override public int sections(Object level) { return SECTIONS; }

        @Override
        public Object chunk(Object level, int chunkX, int chunkZ) {
            return ((Level) level).chunks.get(CgLevelMirror.key(chunkX, chunkZ));
        }

        @Override
        public boolean attach(Object chunk, CgMirrorColumn column) {
            ((Chunk) chunk).column = column;
            return true;
        }

        @Override
        public Object copy(Object chunk, int section) {
            Integer[] s = ((Chunk) chunk).sections[section];
            for (Integer state : s) {
                if (state != null) return s.clone();
            }
            return null;
        }

        @Override
        public void states(Object copy, Object[] out) {
            System.arraycopy((Integer[]) copy, 0, out, 0, 4096);
        }

        @Override
        public void describe(Object state, CgMirrorShape out) {
            describeState((Integer) state, out, positional);
        }

        @Override
        public void resolveAt(Object level, int x, int y, int z, CgMirrorShape out) {
            ((Level) level).shapeAt(x, y, z, out);
        }

        @Override
        public boolean positionalOnly() {
            return positional;
        }

        boolean positional;
    }

    /** Fake levels' queries, so {@code CgCollisionWorld.of(level)} reaches one. */
    static final class Queries extends CollisionFakes.Levels {
        final CgEntityQuery entities;

        Queries(CgEntityQuery entities) {
            this.entities = entities;
        }

        @Override
        public CgWorldQuery world(Object level) {
            return level instanceof Level ? (Level) level : CgWorldQuery.NONE;
        }

        @Override
        public CgEntityQuery entities(Object level) {
            return level instanceof Level ? entities : CgEntityQuery.NONE;
        }
    }
}
