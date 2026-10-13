package com.crystalgraphics.collision.entity;

import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Entities read from a host, each once, and listed in the 16-block cells their boxes touch: what a batch's cache and a
 * snapshot answer from. Each entry keeps the flags, kind, parent, mount and box the host gave.
 *
 * <ul>
 *   <li>An entity is listed in every cell its box touches and handed out from one: the cell holding the near corner of
 *       where its box and the bounds asked meet.</li>
 *   <li>How cells are filled is the subclass's: as they are asked ({@link CgEntityCache}) or all at once
 *       ({@link CgEntitySnapshot}).</li>
 * </ul>
 *
 * <p>Public only for those two: a mod never extends it.
 */
public abstract class CgEntityTable implements CgEntityQuery {

    static final int SHIFT = 4, CELL = 1 << SHIFT;
    static final long NO_CELL = Long.MIN_VALUE;

    // the entities read, by entry
    int count;
    int[] ids = new int[64], flags = new int[64], kinds = new int[64], parents = new int[64], roots = new int[64];
    boolean[] boxed = new boolean[64];
    double[] boxes = new double[6 * 64];
    /** Open addressing, id + 1 to entry + 1; 0 is empty. */
    private int[] idKeys = new int[128], idValues = new int[128];

    // the cells, each a run of entries in members
    long[] cellKeys = new long[64];
    int[] cellStart = new int[64], cellLength = new int[64];
    int cells;
    int[] members = new int[256];
    int memberCount;

    private final BoxInto boxInto = new BoxInto();
    private final int[] facts = new int[ROW_LENGTH];

    CgEntityTable() {
        Arrays.fill(cellKeys, NO_CELL);
    }

    /** The slot of cell (cx, cy, cz) to walk; -1 for one holding nothing. */
    abstract int cellSlot(int cx, int cy, int cz);

    /** Whether entry {@code e} is handed out from its cells: false for one the host answers for now. */
    boolean listed(int e) {
        return true;
    }

    /** Grows the subclass's own per-entry arrays to {@code size}. */
    void grown(int size) {
    }

    /** Forgets every entry and cell. */
    void clearAll() {
        count = 0;
        Arrays.fill(idKeys, 0);
        if (cells > 0) Arrays.fill(cellKeys, NO_CELL);
        cells = 0;
        memberCount = 0;
    }

    /** The entry for {@code id}; -1 for none. */
    final int entry(int id) {
        if (id < 0) return -1;
        int mask = idKeys.length - 1;
        for (int s = slot(id, mask); idKeys[s] != 0; s = (s + 1) & mask) {
            if (idKeys[s] == id + 1) return idValues[s] - 1;
        }
        return -1;
    }

    /** Reads {@code id} from {@code host} into a new entry; its index. */
    int read(CgEntityQuery host, float partialTick, int id) {
        int e = add(id);
        flags[e] = host.flags(id);
        kinds[e] = host.kind(id);
        parents[e] = host.parent(id);
        roots[e] = host.rootVehicle(id);
        boxInto.at = e * 6;
        boxed[e] = host.box(id, partialTick, boxInto);
        return e;
    }

    /** {@link #read} in one {@link CgEntityQuery#row} call, its pose into {@code pose}: the row's bits. The new entry is the last. */
    int readRow(CgEntityQuery host, float partialTick, int id, double[] pose) {
        int e = add(id);
        boxInto.at = e * 6;
        int got = host.row(id, partialTick, facts, boxInto, pose);
        flags[e] = facts[ROW_FLAGS];
        kinds[e] = facts[ROW_KIND];
        parents[e] = facts[ROW_PARENT];
        roots[e] = facts[ROW_ROOT];
        boxed[e] = (got & ROW_BOXED) != 0;
        return got;
    }

    /** A new entry for {@code id}, listed by id; its index. */
    private int add(int id) {
        if (count == ids.length) growEntries();
        if ((count + 1) * 2 > idKeys.length) growIds();
        int e = count++;
        ids[e] = id;
        int mask = idKeys.length - 1, s = slot(id, mask);
        while (idKeys[s] != 0) s = (s + 1) & mask;
        idKeys[s] = id + 1;
        idValues[s] = e + 1;
        return e;
    }

    /** Hands {@code visitor} each listed entry whose box touches the bounds, once each, from the cells they span. */
    final void walk(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        int x0 = cellOf(minX), y0 = cellOf(minY), z0 = cellOf(minZ), x1 = cellOf(maxX), y1 = cellOf(maxY),
                z1 = cellOf(maxZ);
        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    int c = cellSlot(cx, cy, cz);
                    if (c < 0) continue;
                    for (int m = cellStart[c], end = m + cellLength[c]; m < end; m++) {
                        int e = members[m];
                        if (!listed(e) || !meets(e, minX, minY, minZ, maxX, maxY, maxZ)) continue;
                        int b = e * 6;
                        if (cellOf(Math.max(boxes[b], minX)) != cx || cellOf(Math.max(boxes[b + 1], minY)) != cy
                                || cellOf(Math.max(boxes[b + 2], minZ)) != cz) continue;
                        visitor.accept(ids[e]);
                    }
                }
            }
        }
    }

    final boolean meets(int e, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if (!boxed[e]) return false;
        int b = e * 6;
        return boxes[b] <= maxX && boxes[b + 3] >= minX && boxes[b + 1] <= maxY && boxes[b + 4] >= minY
                && boxes[b + 2] <= maxZ && boxes[b + 5] >= minZ;
    }

    /** The slot holding cell {@code key}, or the empty slot it would take. */
    final int findCell(long key) {
        int mask = cellKeys.length - 1, s = (int) (mix(key) & mask);
        while (cellKeys[s] != NO_CELL && cellKeys[s] != key) s = (s + 1) & mask;
        return s;
    }

    /** Doubles the cell table until it holds {@code needed} cells at half load. */
    final void growCells(int needed) {
        int size = cellKeys.length;
        while (needed * 2 > size) size <<= 1;
        if (size == cellKeys.length) return;
        long[] oldKeys = cellKeys;
        int[] oldStart = cellStart, oldLength = cellLength;
        cellKeys = new long[size];
        Arrays.fill(cellKeys, NO_CELL);
        cellStart = new int[size];
        cellLength = new int[size];
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] == NO_CELL) continue;
            int s = findCell(oldKeys[i]);
            cellKeys[s] = oldKeys[i];
            cellStart[s] = oldStart[i];
            cellLength[s] = oldLength[i];
        }
    }

    /** Appends entry {@code e} to the members list. */
    final void addMember(int e) {
        if (memberCount == members.length) members = Arrays.copyOf(members, memberCount * 2);
        members[memberCount++] = e;
    }

    static int cellOf(double v) {
        return (int) Math.floor(v) >> SHIFT;
    }

    static long key(int cx, int cy, int cz) {
        return ((long) cx & 0x3FFFFFL) << 42 | ((long) cy & 0xFFFFFL) << 22 | (long) cz & 0x3FFFFFL;
    }

    private static long mix(long k) {
        k *= 0x9E3779B97F4A7C15L;
        return k ^ k >>> 32;
    }

    private static int slot(int id, int mask) {
        return (id * 0x9E3779B9 >>> 7) & mask;
    }

    /** Room for {@code entries} with no growing, before the first is read: a capture sized from the last one's. */
    final void reserve(int entries) {
        if (count > 0 || entries <= ids.length) return;
        int size = Integer.highestOneBit(entries - 1) << 1;
        ids = new int[size];
        flags = new int[size];
        kinds = new int[size];
        parents = new int[size];
        roots = new int[size];
        boxed = new boolean[size];
        boxes = new double[size * 6];
        grown(size);
        idKeys = new int[size * 2];
        idValues = new int[size * 2];
    }

    private void growEntries() {
        int size = ids.length * 2;
        ids = Arrays.copyOf(ids, size);
        flags = Arrays.copyOf(flags, size);
        kinds = Arrays.copyOf(kinds, size);
        parents = Arrays.copyOf(parents, size);
        roots = Arrays.copyOf(roots, size);
        boxed = Arrays.copyOf(boxed, size);
        boxes = Arrays.copyOf(boxes, size * 6);
        grown(size);
    }

    private void growIds() {
        int size = idKeys.length * 2, mask = size - 1;
        idKeys = new int[size];
        idValues = new int[size];
        for (int e = 0; e < count; e++) {
            int s = slot(ids[e], mask);
            while (idKeys[s] != 0) s = (s + 1) & mask;
            idKeys[s] = ids[e] + 1;
            idValues[s] = e + 1;
        }
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
}
