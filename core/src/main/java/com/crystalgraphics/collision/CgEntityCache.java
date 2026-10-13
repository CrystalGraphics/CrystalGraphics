package com.crystalgraphics.collision;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * One world's entities as a {@link CgCollisionWorld.Batch} first finds them: each 16-block cell asked of the host once,
 * and each entity's flags, kind, parent, mount and box read once, so a thousand hitboxes in a tick ask the host about
 * an entity once rather than a thousand times. What it has not seen it asks the host.
 *
 * <ul>
 *   <li>It caches only past {@link #CACHE_AFTER} asks in a batch, since filling its cells costs more than a few queries
 *       save: until then the host answers.</li>
 *   <li>An entity is found again in every cell its box touches, and handed out from one: the cell holding the near
 *       corner of where its box and the bounds asked meet.</li>
 *   <li>Bounds spanning more than {@link #MAX_CELLS} cells go straight to the host, uncached.</li>
 *   <li>An entity {@link #touch}ed is the host's again for the rest of the batch, and every query tests it wherever it
 *       now is: what a hit's listener damages, moves or kills is never answered as it was.</li>
 * </ul>
 */
final class CgEntityCache implements CgEntityQuery {

    static final int SHIFT = 4, CELL = 1 << SHIFT;
    static final int MAX_CELLS = 512;
    /** Break-even, measured: 1,000 overlaps over 200 entities 29% faster batched, 100 slower (CgEntityCacheTest). */
    static final int CACHE_AFTER = 160;
    /** Grows a cell's bounds as it is asked of the host, so a box ending on the cell's face is found in it. */
    private static final double EDGE = 1.0e-7;
    private static final long NO_CELL = Long.MIN_VALUE;

    private CgEntityQuery host = CgEntityQuery.NONE;
    private float partialTick;
    int generation = -1;
    private int asked;

    // the entities seen, by entry
    private int count;
    private int[] ids = new int[64], flags = new int[64], kinds = new int[64], parents = new int[64],
            roots = new int[64];
    private double[] boxes = new double[6 * 64];
    private boolean[] boxed = new boolean[64], stale = new boolean[64];
    /** The stale entries: few, each tested live by every query. */
    private int[] touched = new int[16];
    private int touchedCount;
    /** Open addressing, id + 1 to entry + 1; 0 is empty. */
    private int[] idKeys = new int[128], idValues = new int[128];

    // the cells asked, each a run of entries in members
    private long[] cellKeys = new long[64];
    private int[] cellStart = new int[64], cellLength = new int[64];
    private int cells;
    private int[] members = new int[256];
    private int memberCount;

    private final Collector collector = new Collector();
    private final BoxInto boxInto = new BoxInto();
    private final Probe probe = new Probe();

    CgEntityCache() {
        Arrays.fill(cellKeys, NO_CELL);
    }

    /** Forgets everything, answering for {@code host} at {@code partialTick} from now on. */
    void reset(CgEntityQuery host, float partialTick, int generation) {
        this.host = host;
        this.partialTick = partialTick;
        this.generation = generation;
        asked = 0;
        forget();
    }

    /** Forgets every entity and cell, still answering for the same batch. */
    void forget() {
        count = 0;
        touchedCount = 0;
        Arrays.fill(idKeys, 0);
        if (cells > 0) Arrays.fill(cellKeys, NO_CELL);
        cells = 0;
        memberCount = 0;
    }

    boolean answersFor(CgEntityQuery host, float partialTick, int generation) {
        return this.generation == generation && this.host == host && this.partialTick == partialTick;
    }

    /** Hands {@code id} back to the host for the rest of the batch: it moved, died or changed. */
    void touch(int id) {
        int e = entry(id);
        if (e < 0) e = add(id);
        if (stale[e]) return;
        stale[e] = true;
        if (touchedCount == touched.length) touched = Arrays.copyOf(touched, touchedCount * 2);
        touched[touchedCount++] = e;
    }

    /** Counts one ask; whether the batch has asked enough to answer from the cache. */
    boolean warm() {
        return ++asked > CACHE_AFTER;
    }

    // ---- the host's questions, from what was seen

    @Override
    public int localPlayer() {
        return host.localPlayer();
    }

    @Override
    public int cameraEntity() {
        return host.cameraEntity();
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        return host.pose(id, partialTick, out);
    }

    @Override
    public int flags(int id) {
        int e = fresh(id);
        return e < 0 ? host.flags(id) : flags[e];
    }

    @Override
    public int kind(int id) {
        int e = fresh(id);
        return e < 0 ? host.kind(id) : kinds[e];
    }

    @Override
    public boolean box(int id, float partialTick, CgBoxSink out) {
        int e = fresh(id);
        if (e < 0 || partialTick != this.partialTick) return host.box(id, partialTick, out);
        if (!boxed[e]) return false;
        int b = e * 6;
        out.set(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
        return true;
    }

    @Override
    public int parent(int id) {
        int e = fresh(id);
        return e < 0 ? host.parent(id) : parents[e];
    }

    @Override
    public int rootVehicle(int id) {
        int e = fresh(id);
        return e < 0 ? host.rootVehicle(id) : roots[e];
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                       IntConsumer visitor) {
        int x0 = cell(minX), y0 = cell(minY), z0 = cell(minZ), x1 = cell(maxX), y1 = cell(maxY), z1 = cell(maxZ);
        long span = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (span > MAX_CELLS) {
            host.within(minX, minY, minZ, maxX, maxY, maxZ, visitor);
            return;
        }
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    int c = cellIndex(cx, cy, cz);
                    for (int m = cellStart[c], end = m + cellLength[c]; m < end; m++) {
                        int e = members[m];
                        if (!boxed[e] || stale[e]) continue;
                        int b = e * 6;
                        if (boxes[b] > maxX || boxes[b + 3] < minX || boxes[b + 1] > maxY || boxes[b + 4] < minY
                                || boxes[b + 2] > maxZ || boxes[b + 5] < minZ) continue;
                        if (cell(Math.max(boxes[b], minX)) != cx || cell(Math.max(boxes[b + 1], minY)) != cy
                                || cell(Math.max(boxes[b + 2], minZ)) != cz) continue;
                        visitor.accept(ids[e]);
                    }
                }
            }
        }
        for (int t = 0; t < touchedCount; t++) {
            int id = ids[touched[t]];
            if (host.box(id, partialTick, probe) && probe.meets(minX, minY, minZ, maxX, maxY, maxZ)) visitor.accept(id);
        }
    }

    // ---- cells

    private static int cell(double v) {
        return (int) Math.floor(v) >> SHIFT;
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) cx & 0x3FFFFFL) << 42 | ((long) cy & 0xFFFFFL) << 22 | (long) cz & 0x3FFFFFL;
    }

    /** The cell's slot, asking the host for it the first time. */
    private int cellIndex(int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        int mask = cellKeys.length - 1;
        int s = (int) (mix(k) & mask);
        while (cellKeys[s] != NO_CELL) {
            if (cellKeys[s] == k) return s;
            s = (s + 1) & mask;
        }
        if ((cells + 1) * 2 > cellKeys.length) {
            growCells();
            return cellIndex(cx, cy, cz);
        }
        int start = memberCount;
        double x = cx << SHIFT, y = cy << SHIFT, z = cz << SHIFT;
        host.within(x - EDGE, y - EDGE, z - EDGE, x + CELL + EDGE, y + CELL + EDGE, z + CELL + EDGE, collector);
        cellKeys[s] = k;
        cellStart[s] = start;
        cellLength[s] = memberCount - start;
        cells++;
        return s;
    }

    private void growCells() {
        long[] oldKeys = cellKeys;
        int[] oldStart = cellStart, oldLength = cellLength;
        int size = oldKeys.length * 2, mask = size - 1;
        cellKeys = new long[size];
        Arrays.fill(cellKeys, NO_CELL);
        cellStart = new int[size];
        cellLength = new int[size];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == NO_CELL) continue;
            int s = (int) (mix(oldKeys[i]) & mask);
            while (cellKeys[s] != NO_CELL) s = (s + 1) & mask;
            cellKeys[s] = oldKeys[i];
            cellStart[s] = oldStart[i];
            cellLength[s] = oldLength[i];
        }
    }

    private static long mix(long k) {
        k *= 0x9E3779B97F4A7C15L;
        return k ^ k >>> 32;
    }

    /** Each entity the host finds in a cell: read once, listed in the cell. */
    private final class Collector implements IntConsumer {
        @Override
        public void accept(int id) {
            int e = entry(id);
            if (e < 0) e = add(id);
            if (memberCount == members.length) members = Arrays.copyOf(members, memberCount * 2);
            members[memberCount++] = e;
        }
    }

    // ---- entries

    /** The entry answering for {@code id}, or -1 for the host: unseen, or touched. */
    private int fresh(int id) {
        int e = entry(id);
        return e >= 0 && stale[e] ? -1 : e;
    }

    private int entry(int id) {
        int mask = idKeys.length - 1, s = (id * 0x9E3779B9 >>> 7) & mask;
        while (idKeys[s] != 0) {
            if (idKeys[s] == id + 1) return idValues[s] - 1;
            s = (s + 1) & mask;
        }
        return -1;
    }

    private int add(int id) {
        if (count == ids.length) growEntries();
        if ((count + 1) * 2 > idKeys.length) growIds();
        int e = count++;
        ids[e] = id;
        flags[e] = host.flags(id);
        kinds[e] = host.kind(id);
        parents[e] = host.parent(id);
        roots[e] = host.rootVehicle(id);
        stale[e] = false;
        boxInto.at = e * 6;
        boxed[e] = host.box(id, partialTick, boxInto);
        int mask = idKeys.length - 1, s = (id * 0x9E3779B9 >>> 7) & mask;
        while (idKeys[s] != 0) s = (s + 1) & mask;
        idKeys[s] = id + 1;
        idValues[s] = e + 1;
        return e;
    }

    /** An entry's box, as the host writes it. */
    private final class BoxInto implements CgBoxSink {
        int at;

        @Override
        public CgBoxSink set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            boxes[at] = minX;
            boxes[at + 1] = minY;
            boxes[at + 2] = minZ;
            boxes[at + 3] = maxX;
            boxes[at + 4] = maxY;
            boxes[at + 5] = maxZ;
            return this;
        }
    }

    /** A touched entity's box, as the host has it now. */
    private static final class Probe implements CgBoxSink {
        private double minX, minY, minZ, maxX, maxY, maxZ;

        @Override
        public CgBoxSink set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            return this;
        }

        boolean meets(double x0, double y0, double z0, double x1, double y1, double z1) {
            return minX <= x1 && maxX >= x0 && minY <= y1 && maxY >= y0 && minZ <= z1 && maxZ >= z0;
        }
    }

    private void growEntries() {
        int size = ids.length * 2;
        ids = Arrays.copyOf(ids, size);
        flags = Arrays.copyOf(flags, size);
        kinds = Arrays.copyOf(kinds, size);
        parents = Arrays.copyOf(parents, size);
        roots = Arrays.copyOf(roots, size);
        boxed = Arrays.copyOf(boxed, size);
        stale = Arrays.copyOf(stale, size);
        boxes = Arrays.copyOf(boxes, size * 6);
    }

    private void growIds() {
        int size = idKeys.length * 2, mask = size - 1;
        idKeys = new int[size];
        idValues = new int[size];
        for (int e = 0; e < count; e++) {
            int s = (ids[e] * 0x9E3779B9 >>> 7) & mask;
            while (idKeys[s] != 0) s = (s + 1) & mask;
            idKeys[s] = ids[e] + 1;
            idValues[s] = e + 1;
        }
    }
}
