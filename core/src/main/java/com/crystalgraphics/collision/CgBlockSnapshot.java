package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgBlockCopy;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.shape.bounds.CgAABBc;

import java.util.Arrays;

/**
 * A level's blocks in a box, copied on the level's thread and read from any: each block's collision boxes and fluid,
 * as a palette index per block, the way Minecraft's sections store states. Immutable once made.
 *
 * <ul>
 *   <li>Answers what collision asks (boxes, their top and bottom, fluid, loaded, the border, the level's height and
 *       epoch); the rest as {@link CgWorldQuery#NONE}.</li>
 *   <li>Blocks answer as to no one ({@link CgQuery#as} is not kept): what scaffolding shows an entity depends on where
 *       it is when it asks.</li>
 *   <li>Outside its box there is nothing: no boxes, no fluid, and loaded, so sight passes. A column unloaded inside it
 *       is unloaded here too.</li>
 * </ul>
 */
final class CgBlockSnapshot implements CgWorldQuery {

    /** The most blocks a snapshot spans along an axis. */
    static final int MAX_SIDE = 128;

    private final int x0, y0, z0, sx, sy, sz, minY, maxY, epoch, seaLevel;
    private final boolean[] loaded;
    private final char[] cells;
    // the palette: entry p's boxes are boxes[start[p] .. start[p + 1]), six floats each
    private final int[] start;
    private final float[] boxes, top, bottom, fluidTop;
    private final byte[] fluid;
    /** Min x, min z, max x, max z; null for no border. */
    private final double[] border;

    private CgBlockSnapshot(Builder b, int entries) {
        border = b.border;
        x0 = b.x0;
        y0 = b.y0;
        z0 = b.z0;
        sx = b.sx;
        sy = b.sy;
        sz = b.sz;
        minY = b.minY;
        maxY = b.maxY;
        epoch = b.epoch;
        seaLevel = b.seaLevel;
        loaded = b.loaded;
        cells = b.cells;
        start = Arrays.copyOf(b.start, entries + 1);
        boxes = Arrays.copyOf(b.boxes, b.start[entries]);
        fluid = Arrays.copyOf(b.fluid, entries);
        fluidTop = Arrays.copyOf(b.fluidTop, entries);
        top = new float[entries];
        bottom = new float[entries];
        for (int p = 0; p < entries; p++) {
            float t = Float.NaN, d = Float.NaN;
            for (int i = start[p]; i < start[p + 1]; i += 6) {
                t = Float.isNaN(t) ? boxes[i + 4] : Math.max(t, boxes[i + 4]);
                d = Float.isNaN(d) ? boxes[i + 1] : Math.min(d, boxes[i + 1]);
            }
            top[p] = t;
            bottom[p] = d;
        }
    }

    /** {@code host}'s blocks touching {@code bounds}, read now. The host's thread. */
    static CgBlockSnapshot of(CgWorldQuery host, CgAABBc bounds) {
        return new Builder(host, bounds).build();
    }

    /** Bytes held, for the measurements. */
    long bytes() {
        return loaded.length + cells.length * 2L + start.length * 4L + boxes.length * 4L + fluid.length * 9L;
    }

    /** The palette entry of a block, or -1 outside the box or in an unloaded column. */
    private int entry(int x, int y, int z) {
        int dx = x - x0, dy = y - y0, dz = z - z0;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sx || dy >= sy || dz >= sz) return -1;
        return cells[(dx * sz + dz) * sy + dy];
    }

    @Override
    public float collisionTop(int x, int y, int z) {
        int p = entry(x, y, z);
        return p < 0 ? Float.NaN : top[p];
    }

    @Override
    public float collisionBottom(int x, int y, int z) {
        int p = entry(x, y, z);
        return p < 0 ? Float.NaN : bottom[p];
    }

    @Override
    public int collisionBoxes(int x, int y, int z, float[] out) {
        int p = entry(x, y, z);
        if (p < 0) return 0;
        int from = start[p], n = (start[p + 1] - from) / 6;
        System.arraycopy(boxes, from, out, 0, Math.min(n, out.length / 6) * 6);
        return n;
    }

    @Override
    public float fluidHeight(int x, int y, int z) {
        int p = entry(x, y, z);
        return p < 0 ? Float.NaN : fluidTop[p];
    }

    @Override
    public int fluidKind(int x, int y, int z) {
        int p = entry(x, y, z);
        return p < 0 ? FLUID_NONE : fluid[p];
    }

    @Override
    public boolean loaded(int x, int z) {
        int dx = x - x0, dz = z - z0;
        return dx < 0 || dz < 0 || dx >= sx || dz >= sz || loaded[dx * sz + dz];
    }

    @Override
    public boolean border(double[] out) {
        if (border == null) return false;
        System.arraycopy(border, 0, out, 0, 4);
        return true;
    }

    @Override public int minY() { return minY; }
    @Override public int maxY() { return maxY; }
    @Override public int seaLevel() { return seaLevel; }
    @Override public int levelEpoch() { return epoch; }

    @Override public int surface(int x, int y, int z) { return NONE.surface(x, y, z); }
    @Override public float hardness(int x, int y, int z) { return NONE.hardness(x, y, z); }
    @Override public int lightEmission(int x, int y, int z) { return NONE.lightEmission(x, y, z); }
    @Override public int tint(int x, int y, int z) { return NONE.tint(x, y, z); }
    @Override public int biomeColor(int x, int y, int z, int kind) { return NONE.biomeColor(x, y, z, kind); }
    @Override public int precipitation(int x, int y, int z) { return NONE.precipitation(x, y, z); }
    @Override public int surfaceY(int x, int z, int kind) { return NONE.surfaceY(x, z, kind); }
    @Override public boolean spriteRect(int x, int y, int z, float[] out) { return NONE.spriteRect(x, y, z, out); }
    @Override public int light(int x, int y, int z) { return NONE.light(x, y, z); }
    @Override public int mapColor(int x, int y, int z) { return NONE.mapColor(x, y, z); }

    /**
     * Copies the host into a palette, from its sections where it can ({@link CgWorldQuery#copyBlocks}) and block by
     * block where it cannot; entry 0 is empty air, entry 1 a full cube.
     */
    private static final class Builder implements CgBlockCopy {
        final int x0, y0, z0, sx, sy, sz, minY, maxY, epoch, seaLevel;
        final boolean[] loaded;
        final char[] cells;
        double[] border = new double[4];
        int[] start = new int[64];
        float[] boxes = new float[256];
        byte[] fluid = new byte[64];
        float[] fluidTop = new float[64];
        int entries;
        private int[] table = new int[256];
        // the host's keys: keys[s] is key + 1, keyEntries[s] its entry
        private int[] keys = new int[256], keyEntries = new int[256];
        private int keyCount;
        private final float[] read = new float[6 * 16];
        private final CgWorldQuery host;

        Builder(CgWorldQuery host, CgAABBc bounds) {
            this.host = host;
            minY = host.minY();
            maxY = host.maxY();
            epoch = host.levelEpoch();
            seaLevel = host.seaLevel();
            if (!host.border(border)) border = null;
            x0 = (int) Math.floor(bounds.minX());
            z0 = (int) Math.floor(bounds.minZ());
            y0 = Math.max(minY, (int) Math.floor(bounds.minY()));
            int x1 = (int) Math.floor(bounds.maxX()), z1 = (int) Math.floor(bounds.maxZ());
            int y1 = Math.min(maxY - 1, (int) Math.floor(bounds.maxY()));
            sx = x1 - x0 + 1;
            sz = z1 - z0 + 1;
            sy = Math.max(0, y1 - y0 + 1);
            if (sx > MAX_SIDE || sz > MAX_SIDE || sy > MAX_SIDE) {
                throw new IllegalArgumentException("a snapshot spans at most " + MAX_SIDE + " blocks a side, not "
                        + sx + " x " + sy + " x " + sz + ": snapshot only what the work reaches");
            }
            loaded = new boolean[sx * sz];
            cells = new char[sx * sz * sy];
            add(read, 0, FLUID_NONE, Float.NaN);
            read[0] = read[1] = read[2] = 0f;
            read[3] = read[4] = read[5] = 1f;
            add(read, 1, FLUID_NONE, Float.NaN);
        }

        CgBlockSnapshot build() {
            if (host.copyBlocks(x0, y0, z0, x0 + sx - 1, y0 + sy - 1, z0 + sz - 1, this)) {
                return new CgBlockSnapshot(this, entries);
            }
            float[] boxesRead = read;
            for (int dx = 0; dx < sx; dx++) {
                for (int dz = 0; dz < sz; dz++) {
                    int x = x0 + dx, z = z0 + dz, column = dx * sz + dz;
                    if (!host.loaded(x, z)) continue;
                    loaded[column] = true;
                    for (int dy = 0; dy < sy; dy++) {
                        int y = y0 + dy;
                        int n = host.collisionBoxes(x, y, z, boxesRead);
                        if (n * 6 > boxesRead.length) {
                            boxesRead = new float[n * 6];
                            n = host.collisionBoxes(x, y, z, boxesRead);
                        }
                        int kind = host.fluidKind(x, y, z);
                        float fluidHeight = kind == FLUID_NONE ? Float.NaN : host.fluidHeight(x, y, z);
                        cells[column * sy + dy] = (char) find(boxesRead, n, kind, fluidHeight);
                    }
                }
            }
            return new CgBlockSnapshot(this, entries);
        }

        @Override
        public int entry(int key) {
            int mask = keys.length - 1;
            for (int s = (key * 0x9E3779B9 >>> 8) & mask; ; s = (s + 1) & mask) {
                if (keys[s] == 0) return -1;
                if (keys[s] == key + 1) return keyEntries[s];
            }
        }

        @Override
        public int add(int key, float[] boxes, int count, int fluidKind, float fluidHeight) {
            int entry = find(boxes, count, fluidKind, fluidKind == FLUID_NONE ? Float.NaN : fluidHeight);
            if (key < 0) return entry;
            if (++keyCount * 2 > keys.length) {
                int[] oldKeys = keys, oldEntries = keyEntries;
                keys = new int[oldKeys.length * 2];
                keyEntries = new int[oldKeys.length * 2];
                for (int s = 0; s < oldKeys.length; s++) {
                    if (oldKeys[s] != 0) putKey(oldKeys[s], oldEntries[s]);
                }
            }
            putKey(key + 1, entry);
            return entry;
        }

        private void putKey(int stored, int entry) {
            int mask = keys.length - 1;
            int s = ((stored - 1) * 0x9E3779B9 >>> 8) & mask;
            while (keys[s] != 0 && keys[s] != stored) s = (s + 1) & mask;
            keys[s] = stored;
            keyEntries[s] = entry;
        }

        @Override
        public void set(int x, int y, int z, int entry) {
            cells[((x - x0) * sz + (z - z0)) * sy + (y - y0)] = (char) entry;
        }

        @Override
        public void loaded(int x, int z) {
            loaded[(x - x0) * sz + (z - z0)] = true;
        }

        /** The palette entry holding exactly these boxes and fluid, added if new. */
        private int find(float[] b, int n, int kind, float fluidHeight) {
            if (kind == FLUID_NONE) {
                if (n == 0) return 0;
                if (n == 1 && b[0] == 0f && b[1] == 0f && b[2] == 0f && b[3] == 1f && b[4] == 1f && b[5] == 1f) return 1;
            }
            int hash = kind * 31 + Float.floatToIntBits(fluidHeight);
            for (int i = 0; i < n * 6; i++) hash = hash * 31 + Float.floatToIntBits(b[i]);
            int mask = table.length - 1;
            for (int s = (hash * 0x9E3779B9 >>> 8) & mask; ; s = (s + 1) & mask) {
                int p = table[s] - 1;
                if (p < 0) {
                    if (entries == Character.MAX_VALUE) throw new IllegalStateException("over 65535 block shapes in a snapshot");
                    int added = add(b, n, kind, fluidHeight);
                    table[s] = added + 1;
                    if (entries * 2 > table.length) rehash();
                    return added;
                }
                if (same(p, b, n, kind, fluidHeight)) return p;
            }
        }

        private boolean same(int p, float[] b, int n, int kind, float fluidHeight) {
            int from = start[p];
            if (fluid[p] != kind || start[p + 1] - from != n * 6
                    || Float.floatToIntBits(fluidTop[p]) != Float.floatToIntBits(fluidHeight)) return false;
            for (int i = 0; i < n * 6; i++) {
                if (boxes[from + i] != b[i]) return false;
            }
            return true;
        }

        private int add(float[] b, int n, int kind, float fluidHeight) {
            int p = entries++;
            if (entries + 1 > start.length) {
                start = Arrays.copyOf(start, start.length * 2);
                fluid = Arrays.copyOf(fluid, fluid.length * 2);
                fluidTop = Arrays.copyOf(fluidTop, fluidTop.length * 2);
            }
            int from = start[p];
            if (from + n * 6 > boxes.length) boxes = Arrays.copyOf(boxes, Math.max(boxes.length * 2, from + n * 6));
            System.arraycopy(b, 0, boxes, from, n * 6);
            start[p + 1] = from + n * 6;
            fluid[p] = (byte) kind;
            fluidTop[p] = fluidHeight;
            return p;
        }

        private void rehash() {
            int[] grown = new int[table.length * 2];
            int mask = grown.length - 1;
            for (int p = 2; p < entries; p++) {
                int from = start[p], n = (start[p + 1] - from) / 6;
                int hash = fluid[p] * 31 + Float.floatToIntBits(fluidTop[p]);
                for (int i = 0; i < n * 6; i++) hash = hash * 31 + Float.floatToIntBits(boxes[from + i]);
                int s = (hash * 0x9E3779B9 >>> 8) & mask;
                while (grown[s] != 0) s = (s + 1) & mask;
                grown[s] = p + 1;
            }
            table = grown;
        }
    }
}
