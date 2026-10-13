package com.crystalgraphics.collision.query;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.shape.bounds.CgAABBc;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * A level's entities in a box, copied on the level's thread and read from any: each one's flags, kind, parent, mount,
 * box and pose as they were, in 16-block cells. Immutable once made.
 *
 * <ul>
 *   <li>It holds the entities whose boxes touch the box, and the wholes and mounts they belong to; any other id
 *       answers as none (flags 0, parent -1, no box).</li>
 *   <li>Boxes and poses are where the host read them at the snapshot's partial tick, whatever a caller asks.</li>
 *   <li>An entity is listed in every cell its box touches and handed out from one, as {@link CgEntityCache} does.</li>
 * </ul>
 */
final class CgEntitySnapshot implements CgEntityQuery {

    private static final int SHIFT = CgEntityCache.SHIFT;
    /** An entity spanning more cells than this is tested by every walk instead of listed. */
    private static final int MAX_CELLS = 64;
    private static final long NO_CELL = Long.MIN_VALUE;

    private final int localPlayer, camera, count;
    private final int[] ids, flags, kinds, parents, roots;
    private final boolean[] boxed;
    private final double[] boxes, poses;
    /** Open addressing, id + 1 to entry + 1; 0 is empty. */
    private final int[] idKeys, idValues;
    private final long[] cellKeys;
    private final int[] cellStart, cellLength, members;
    /** Entries too wide for the cells, tested by every walk. */
    private final int[] wide;

    private CgEntitySnapshot(Builder b) {
        localPlayer = b.localPlayer;
        camera = b.camera;
        count = b.count;
        ids = Arrays.copyOf(b.ids, count);
        flags = Arrays.copyOf(b.flags, count);
        kinds = Arrays.copyOf(b.kinds, count);
        parents = Arrays.copyOf(b.parents, count);
        roots = Arrays.copyOf(b.roots, count);
        boxed = Arrays.copyOf(b.boxed, count);
        boxes = Arrays.copyOf(b.boxes, count * 6);
        poses = Arrays.copyOf(b.poses, count * POSE_LENGTH);

        int idSize = Integer.highestOneBit(Math.max(8, count * 2)) << 1, idMask = idSize - 1;
        idKeys = new int[idSize];
        idValues = new int[idSize];
        for (int e = 0; e < count; e++) {
            int s = slot(ids[e], idMask);
            while (idKeys[s] != 0) s = (s + 1) & idMask;
            idKeys[s] = ids[e] + 1;
            idValues[s] = e + 1;
        }

        // count each cell's members, then lay each cell's run out in members
        int cellSize = 16;
        int listed = 0, wideCount = 0;
        for (int e = 0; e < count; e++) {
            if (!boxed[e]) continue;
            int span = span(e);
            if (span > MAX_CELLS) wideCount++;
            else listed += span;
        }
        while (cellSize < listed * 2) cellSize <<= 1;
        cellKeys = new long[cellSize];
        Arrays.fill(cellKeys, NO_CELL);
        cellStart = new int[cellSize];
        cellLength = new int[cellSize];
        members = new int[listed];
        wide = new int[wideCount];
        wideCount = 0;
        for (int e = 0; e < count; e++) {
            if (!boxed[e]) continue;
            if (span(e) > MAX_CELLS) {
                wide[wideCount++] = e;
                continue;
            }
            forCells(e, -1);
        }
        int at = 0;
        for (int s = 0; s < cellSize; s++) {
            if (cellKeys[s] == NO_CELL) continue;
            cellStart[s] = at;
            at += cellLength[s];
            cellLength[s] = 0;
        }
        for (int e = 0; e < count; e++) {
            if (boxed[e] && span(e) <= MAX_CELLS) forCells(e, e);
        }
    }

    /** {@code host}'s entities touching {@code bounds}, read now at {@code partialTick}. The host's thread. */
    static CgEntitySnapshot of(CgEntityQuery host, float partialTick, CgAABBc bounds) {
        Builder b = new Builder(host, partialTick);
        host.within(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(), b);
        // the wholes and mounts of what was found, which a query asks of a part or a rider
        for (int e = 0; e < b.count; e++) {
            b.accept(b.parents[e]);
            b.accept(b.roots[e]);
        }
        return new CgEntitySnapshot(b);
    }

    /** With {@code member} below 0, counts {@code e} in each cell its box touches; else lists it there. */
    private void forCells(int e, int member) {
        int b = e * 6, mask = cellKeys.length - 1;
        for (int cx = cell(boxes[b]); cx <= cell(boxes[b + 3]); cx++) {
            for (int cy = cell(boxes[b + 1]); cy <= cell(boxes[b + 4]); cy++) {
                for (int cz = cell(boxes[b + 2]); cz <= cell(boxes[b + 5]); cz++) {
                    long k = key(cx, cy, cz);
                    int s = (int) (mix(k) & mask);
                    while (cellKeys[s] != NO_CELL && cellKeys[s] != k) s = (s + 1) & mask;
                    cellKeys[s] = k;
                    if (member >= 0) members[cellStart[s] + cellLength[s]] = member;
                    cellLength[s]++;
                }
            }
        }
    }

    private int span(int e) {
        int b = e * 6;
        return (cell(boxes[b + 3]) - cell(boxes[b]) + 1) * (cell(boxes[b + 4]) - cell(boxes[b + 1]) + 1)
                * (cell(boxes[b + 5]) - cell(boxes[b + 2]) + 1);
    }

    /** Entities held, for the measurements. */
    int size() {
        return count;
    }

    private int entry(int id) {
        if (id < 0) return -1;
        int mask = idKeys.length - 1;
        for (int s = slot(id, mask); idKeys[s] != 0; s = (s + 1) & mask) {
            if (idKeys[s] == id + 1) return idValues[s] - 1;
        }
        return -1;
    }

    @Override
    public int localPlayer() {
        return localPlayer;
    }

    @Override
    public int cameraEntity() {
        return camera;
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        int e = entry(id);
        if (e < 0 || Double.isNaN(poses[e * POSE_LENGTH])) return false;
        System.arraycopy(poses, e * POSE_LENGTH, out, 0, POSE_LENGTH);
        return true;
    }

    @Override
    public int flags(int id) {
        int e = entry(id);
        return e < 0 ? 0 : flags[e];
    }

    @Override
    public int kind(int id) {
        int e = entry(id);
        return e < 0 ? KIND_OTHER : kinds[e];
    }

    @Override
    public boolean box(int id, float partialTick, CgBoxSink out) {
        int e = entry(id);
        if (e < 0 || !boxed[e]) return false;
        int b = e * 6;
        out.set(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
        return true;
    }

    @Override
    public int parent(int id) {
        int e = entry(id);
        return e < 0 ? -1 : parents[e];
    }

    @Override
    public int rootVehicle(int id) {
        int e = entry(id);
        return e < 0 ? -1 : roots[e];
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       IntConsumer visitor) {
        int x0 = cell(minX), y0 = cell(minY), z0 = cell(minZ), x1 = cell(maxX), y1 = cell(maxY), z1 = cell(maxZ);
        long span = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (span > count) {
            // more cells than entities: test each once
            for (int e = 0; e < count; e++) {
                if (meets(e, minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(ids[e]);
            }
            return;
        }
        int mask = cellKeys.length - 1;
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    long k = key(cx, cy, cz);
                    int s = (int) (mix(k) & mask);
                    while (cellKeys[s] != NO_CELL && cellKeys[s] != k) s = (s + 1) & mask;
                    if (cellKeys[s] == NO_CELL) continue;
                    for (int m = cellStart[s], end = m + cellLength[s]; m < end; m++) {
                        int e = members[m];
                        if (!meets(e, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                        int b = e * 6;
                        if (cell(Math.max(boxes[b], minX)) != cx || cell(Math.max(boxes[b + 1], minY)) != cy
                                || cell(Math.max(boxes[b + 2], minZ)) != cz) continue;
                        visitor.accept(ids[e]);
                    }
                }
            }
        }
        for (int e : wide) {
            if (meets(e, minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(ids[e]);
        }
    }

    private boolean meets(int e, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!boxed[e]) return false;
        int b = e * 6;
        return boxes[b] <= maxX && boxes[b + 3] >= minX && boxes[b + 1] <= maxY && boxes[b + 4] >= minY
                && boxes[b + 2] <= maxZ && boxes[b + 5] >= minZ;
    }

    private static int cell(double v) {
        return (int) Math.floor(v) >> SHIFT;
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) cx & 0x3FFFFFL) << 42 | ((long) cy & 0xFFFFFL) << 22 | (long) cz & 0x3FFFFFL;
    }

    private static long mix(long k) {
        k *= 0x9E3779B97F4A7C15L;
        return k ^ k >>> 32;
    }

    private static int slot(int id, int mask) {
        return (id * 0x9E3779B9 >>> 7) & mask;
    }

    /** Reads each entity the host finds, once. */
    private static final class Builder implements IntConsumer, CgBoxSink {
        final CgEntityQuery host;
        final float partialTick;
        final int localPlayer, camera;
        int count;
        int[] ids = new int[16], flags = new int[16], kinds = new int[16], parents = new int[16], roots = new int[16];
        boolean[] boxed = new boolean[16];
        double[] boxes = new double[6 * 16], poses = new double[POSE_LENGTH * 16];
        private final double[] pose = new double[POSE_LENGTH];
        private int[] seen = new int[64];
        private int boxAt;

        Builder(CgEntityQuery host, float partialTick) {
            this.host = host;
            this.partialTick = partialTick;
            localPlayer = host.localPlayer();
            camera = host.cameraEntity();
        }

        @Override
        public void accept(int id) {
            if (id < 0 || !markSeen(id)) return;
            if (count == ids.length) grow();
            int e = count++;
            ids[e] = id;
            flags[e] = host.flags(id);
            kinds[e] = host.kind(id);
            parents[e] = host.parent(id);
            roots[e] = host.rootVehicle(id);
            boxAt = e * 6;
            boxed[e] = host.box(id, partialTick, this);
            // NaN at X marks no pose
            if (host.pose(id, partialTick, pose)) System.arraycopy(pose, 0, poses, e * POSE_LENGTH, POSE_LENGTH);
            else poses[e * POSE_LENGTH] = Double.NaN;
        }

        @Override
        public CgBoxSink set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            boxes[boxAt] = minX;
            boxes[boxAt + 1] = minY;
            boxes[boxAt + 2] = minZ;
            boxes[boxAt + 3] = maxX;
            boxes[boxAt + 4] = maxY;
            boxes[boxAt + 5] = maxZ;
            return this;
        }

        /** Whether {@code id} is new, marking it seen. */
        private boolean markSeen(int id) {
            if ((count + 1) * 2 > seen.length) {
                int[] old = seen;
                seen = new int[old.length * 2];
                for (int k : old) {
                    if (k != 0) insert(k - 1);
                }
            }
            int mask = seen.length - 1;
            for (int s = slot(id, mask); seen[s] != 0; s = (s + 1) & mask) {
                if (seen[s] == id + 1) return false;
            }
            insert(id);
            return true;
        }

        private void insert(int id) {
            int mask = seen.length - 1, s = slot(id, mask);
            while (seen[s] != 0) s = (s + 1) & mask;
            seen[s] = id + 1;
        }

        private void grow() {
            int n = ids.length * 2;
            ids = Arrays.copyOf(ids, n);
            flags = Arrays.copyOf(flags, n);
            kinds = Arrays.copyOf(kinds, n);
            parents = Arrays.copyOf(parents, n);
            roots = Arrays.copyOf(roots, n);
            boxed = Arrays.copyOf(boxed, n);
            boxes = Arrays.copyOf(boxes, n * 6);
            poses = Arrays.copyOf(poses, n * POSE_LENGTH);
        }
    }
}
