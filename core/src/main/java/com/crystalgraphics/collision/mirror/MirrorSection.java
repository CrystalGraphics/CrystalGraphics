package com.crystalgraphics.collision.mirror;

import java.util.Arrays;

/**
 * One mirrored 16-block section: its write log, kept by the level's thread, and its cells, kept by the mirror's worker.
 * Each half's fields are that thread's alone; they meet only through the log and the batches.
 */
final class MirrorSection {

    private static final int[] EMPTY = new int[0];

    final CgMirrorColumn column;
    /** In the column, 0 the lowest. */
    final int index;
    final WriteLog log = new WriteLog();

    // ---- the level thread's
    /** Its first copy was taken: writes from here on are logged. */
    boolean based;
    /** Its log overflowed: copied again at the next publish. */
    boolean resync;
    /** The publish generation it was last marked dirty in. */
    int dirtyGeneration = -1;
    /** Cells whose shape changes with no write (a moving piston), resolved at every publish. */
    int[] dynamic = EMPTY;
    int dynamicCount;
    /** Whether it is in its mirror's list of sections with {@link #dynamic} cells. */
    boolean listedDynamic;

    // ---- the worker's
    /** Shape-palette entry per cell, y << 8 | z << 4 | x; null while every cell is {@link #uniform}. */
    char[] cells;
    char uniform;
    /** Whether {@link #cells} is held by a published version, so a change copies it first. */
    boolean shared;
    /** Its first copy reached the worker, and the positional cells found in it were resolved since. */
    boolean ready;
    /** The epoch of its first copy. */
    long baseEpoch = -1;

    MirrorSection(CgMirrorColumn column, int index) {
        this.column = column;
        this.index = index;
    }

    /** The worker: the entry at {@code cell}. */
    char get(int cell) {
        return cells == null ? uniform : cells[cell];
    }

    /** The worker: sets {@code cell}, copying the cells first if a published version holds them. */
    void set(int cell, char entry) {
        if (cells == null) {
            if (entry == uniform) return;
            cells = new char[4096];
            Arrays.fill(cells, uniform);
        } else if (shared) {
            cells = cells.clone();
        }
        shared = false;
        cells[cell] = entry;
    }

    /** The level thread: {@code cell} changes shape with no write from now on. */
    void addDynamic(int cell) {
        for (int i = 0; i < dynamicCount; i++) {
            if (dynamic[i] == cell) return;
        }
        if (dynamicCount == dynamic.length) dynamic = Arrays.copyOf(dynamic, Math.max(4, dynamicCount * 2));
        dynamic[dynamicCount++] = cell;
    }

    /** The level thread: {@code cell} no longer does. */
    void removeDynamic(int cell) {
        for (int i = 0; i < dynamicCount; i++) {
            if (dynamic[i] != cell) continue;
            dynamic[i] = dynamic[--dynamicCount];
            return;
        }
    }

    /** Block coordinates of {@code cell}. */
    int x(int cell) {
        return (column.chunkX << 4) + (cell & 15);
    }

    int y(int cell) {
        return (column.mirror.minSection + index << 4) + (cell >> 8);
    }

    int z(int cell) {
        return (column.chunkZ << 4) + (cell >> 4 & 15);
    }
}
