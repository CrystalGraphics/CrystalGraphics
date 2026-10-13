package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.platform.world.CgWorldQuery;

import java.util.Arrays;

/**
 * Block shapes, each distinct one stored once: an entry's boxes, their top and bottom, and its fluid. Entry 0 is air
 * and entry 1 a full cube in every store. One thread's: the level-wide {@link MirrorPalette} is the worker's, a batch's
 * table the level thread's until it is handed over.
 *
 * <ul>
 *   <li>Append-only: an entry never changes, and arrays grow by copying, so a reader bounded by a count it was handed
 *       with a published version reads entries that never move.</li>
 *   <li>At most 65,535 entries, a section's cells being {@code char}s.</li>
 * </ul>
 */
class ShapeStore {

    static final char AIR = 0, CUBE = 1;

    // entry p's boxes are boxes[start[p] .. start[p + 1]), six floats each
    int[] start = new int[65];
    float[] boxes = new float[256];
    float[] top = new float[64], bottom = new float[64], fluidHeight = new float[64];
    byte[] fluidKind = new byte[64];
    boolean[] followsAbove = new boolean[64];
    int count;
    private int[] table = new int[128];

    ShapeStore() {
        CgMirrorShape s = new CgMirrorShape();
        add(s.clear());
        add(s.clear().box(0, 0, 0, 1, 1, 1));
    }

    /** The entry holding exactly {@code s}'s boxes and fluid, added if new. */
    final char intern(CgMirrorShape s) {
        return intern(s.boxes, 0, s.count, s.fluidKind, s.fluidHeight, s.followsAbove);
    }

    /** The entry holding exactly entry {@code p} of {@code other}, added here if new. */
    final char intern(ShapeStore other, int p) {
        int from = other.start[p];
        return intern(other.boxes, from, (other.start[p + 1] - from) / 6, other.fluidKind[p], other.fluidHeight[p],
                other.followsAbove[p]);
    }

    private char intern(float[] b, int from, int n, int kind, float height, boolean follows) {
        if (kind == CgWorldQuery.FLUID_NONE) {
            if (n == 0) return AIR;
            if (n == 1 && b[from] == 0f && b[from + 1] == 0f && b[from + 2] == 0f && b[from + 3] == 1f
                    && b[from + 4] == 1f && b[from + 5] == 1f && count > CUBE) return CUBE;
        }
        int hash = hash(b, from, n, kind, height, follows);
        int mask = table.length - 1;
        for (int s = (hash * 0x9E3779B9 >>> 8) & mask; ; s = (s + 1) & mask) {
            int p = table[s] - 1;
            if (p < 0) {
                if (count == Character.MAX_VALUE) throw new IllegalStateException("over 65535 block shapes in one level");
                int added = add(b, from, n, kind, height, follows);
                table[s] = added + 1;
                if (count * 2 > table.length) rehash();
                return (char) added;
            }
            if (same(p, b, from, n, kind, height, follows)) return (char) p;
        }
    }

    private int add(CgMirrorShape s) {
        int p = add(s.boxes, 0, s.count, s.fluidKind, s.fluidHeight, s.followsAbove);
        int mask = table.length - 1, slot = (hash(s.boxes, 0, s.count, s.fluidKind, s.fluidHeight, s.followsAbove)
                * 0x9E3779B9 >>> 8) & mask;
        while (table[slot] != 0) slot = (slot + 1) & mask;
        table[slot] = p + 1;
        return p;
    }

    private int add(float[] b, int from, int n, int kind, float height, boolean follows) {
        int p = count++;
        if (count + 1 > start.length) {
            int size = start.length * 2;
            start = Arrays.copyOf(start, size);
            top = Arrays.copyOf(top, size);
            bottom = Arrays.copyOf(bottom, size);
            fluidHeight = Arrays.copyOf(fluidHeight, size);
            fluidKind = Arrays.copyOf(fluidKind, size);
            followsAbove = Arrays.copyOf(followsAbove, size);
        }
        int at = start[p];
        if (at + n * 6 > boxes.length) boxes = Arrays.copyOf(boxes, Math.max(boxes.length * 2, at + n * 6));
        System.arraycopy(b, from, boxes, at, n * 6);
        start[p + 1] = at + n * 6;
        float t = Float.NaN, d = Float.NaN;
        for (int i = at; i < at + n * 6; i += 6) {
            t = Float.isNaN(t) ? boxes[i + 4] : Math.max(t, boxes[i + 4]);
            d = Float.isNaN(d) ? boxes[i + 1] : Math.min(d, boxes[i + 1]);
        }
        top[p] = t;
        bottom[p] = d;
        fluidKind[p] = (byte) kind;
        fluidHeight[p] = height;
        followsAbove[p] = follows;
        return p;
    }

    private boolean same(int p, float[] b, int from, int n, int kind, float height, boolean follows) {
        int at = start[p];
        if (fluidKind[p] != kind || followsAbove[p] != follows || start[p + 1] - at != n * 6
                || Float.floatToIntBits(fluidHeight[p]) != Float.floatToIntBits(height)) return false;
        for (int i = 0; i < n * 6; i++) {
            if (boxes[at + i] != b[from + i]) return false;
        }
        return true;
    }

    private static int hash(float[] b, int from, int n, int kind, float height, boolean follows) {
        int h = (kind * 31 + Float.floatToIntBits(height)) * 31 + (follows ? 1 : 0);
        for (int i = from; i < from + n * 6; i++) h = h * 31 + Float.floatToIntBits(b[i]);
        return h;
    }

    private void rehash() {
        int[] grown = new int[table.length * 2];
        int mask = grown.length - 1;
        for (int p = 0; p < count; p++) {
            int at = start[p];
            int s = (hash(boxes, at, (start[p + 1] - at) / 6, fluidKind[p], fluidHeight[p], followsAbove[p])
                    * 0x9E3779B9 >>> 8) & mask;
            while (grown[s] != 0) s = (s + 1) & mask;
            grown[s] = p + 1;
        }
        table = grown;
    }
}
