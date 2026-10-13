package com.crystalgraphics.collision.mirror;

import com.crystalgraphics.collision.entity.CgEntitySnapshot;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * Everything the level's thread gathered at one publish, for the worker to turn into that epoch's version: the
 * sections written and how far their logs reached, first copies, shapes resolved where they stand, what went away, and
 * the entities and hittables as they stood. Made on the level thread, read once by the worker after the queue hands
 * it over; never touched by the level thread again.
 */
final class MirrorBatch {

    final long epoch;

    MirrorSection[] dirty = new MirrorSection[16];
    long[] marks = new long[16];
    int dirtyCount;

    MirrorSection[] bases = new MirrorSection[8];
    /** A host copy, null for air; or, from a positional-only host, a {@code char[4096]} of {@link #shapes} entries. */
    Object[] baseCopies = new Object[8];
    int baseCount;

    /** Shapes resolved where they stand this publish, each as an entry of {@link #shapes}. */
    MirrorSection[] resolved = new MirrorSection[16];
    int[] resolvedCells = new int[16];
    char[] resolvedEntries = new char[16];
    int resolvedCount;
    final ShapeStore shapes = new ShapeStore();

    /** Sections whose positional cells, found in their first copy, were all resolved this publish. */
    MirrorSection[] settled = new MirrorSection[4];
    int settledCount;

    /** Sections no longer mirrored, or replaced by a fresh copy. */
    MirrorSection[] removed = new MirrorSection[4];
    int removedCount;
    /** Columns that unloaded, read as unloaded; and columns nothing watches any more, dropped. */
    long[] unloaded = new long[4], dropped = new long[4];
    int unloadedCount, droppedCount;

    @Nullable CgEntitySnapshot entities;
    @Nullable Object frozen;
    @Nullable double[] border;
    int minY, maxY, seaLevel, levelEpoch;

    /** {@code crystalgraphics.mirror.verify}: copies of every mirrored section taken at this publish. */
    MirrorSection[] verified = new MirrorSection[0];
    Object[] verifyCopies = new Object[0];
    int verifyCount;

    MirrorBatch(long epoch) {
        this.epoch = epoch;
    }

    void dirty(MirrorSection s, long mark) {
        if (dirtyCount == dirty.length) {
            dirty = Arrays.copyOf(dirty, dirtyCount * 2);
            marks = Arrays.copyOf(marks, dirtyCount * 2);
        }
        dirty[dirtyCount] = s;
        marks[dirtyCount++] = mark;
    }

    void base(MirrorSection s, @Nullable Object copy) {
        if (baseCount == bases.length) {
            bases = Arrays.copyOf(bases, baseCount * 2);
            baseCopies = Arrays.copyOf(baseCopies, baseCount * 2);
        }
        bases[baseCount] = s;
        baseCopies[baseCount++] = copy;
    }

    void resolved(MirrorSection s, int cell, char entry) {
        if (resolvedCount == resolved.length) {
            resolved = Arrays.copyOf(resolved, resolvedCount * 2);
            resolvedCells = Arrays.copyOf(resolvedCells, resolvedCount * 2);
            resolvedEntries = Arrays.copyOf(resolvedEntries, resolvedCount * 2);
        }
        resolved[resolvedCount] = s;
        resolvedCells[resolvedCount] = cell;
        resolvedEntries[resolvedCount++] = entry;
    }

    void settled(MirrorSection s) {
        if (settledCount == settled.length) settled = Arrays.copyOf(settled, settledCount * 2);
        settled[settledCount++] = s;
    }

    void removed(MirrorSection s) {
        if (removedCount == removed.length) removed = Arrays.copyOf(removed, removedCount * 2);
        removed[removedCount++] = s;
    }

    void unloaded(long key) {
        if (unloadedCount == unloaded.length) unloaded = Arrays.copyOf(unloaded, unloadedCount * 2);
        unloaded[unloadedCount++] = key;
    }

    void dropped(long key) {
        if (droppedCount == dropped.length) dropped = Arrays.copyOf(dropped, droppedCount * 2);
        dropped[droppedCount++] = key;
    }

    void verify(MirrorSection s, @Nullable Object copy) {
        if (verifyCount == verified.length) {
            verified = Arrays.copyOf(verified, Math.max(16, verifyCount * 2));
            verifyCopies = Arrays.copyOf(verifyCopies, verified.length);
        }
        verified[verifyCount] = s;
        verifyCopies[verifyCount++] = copy;
    }
}
