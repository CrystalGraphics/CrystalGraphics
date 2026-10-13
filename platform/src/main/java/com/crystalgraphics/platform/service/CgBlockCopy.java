package com.crystalgraphics.platform.service;

/**
 * Where a host copies a box of its blocks: what {@link CgWorldQuery#copyBlocks} writes into. Each block is a palette
 * entry, as in the host's own sections, so a host describes each distinct block once and sets its entry on every block
 * holding it. Every block starts as {@link #AIR} and every column as unloaded.
 *
 * <pre>{@code
 * // a host, per block of a loaded section, once it has marked the section's columns with copy.loaded(x, z):
 * int key = Block.getId(state);                  // a block whose boxes and fluid follow from its state alone
 * int entry = copy.entry(key);
 * if (entry < 0) {
 *     int n = boxesOf(state, boxes);             // block-local, six floats a box
 *     entry = copy.add(key, boxes, n, fluidKind, fluidHeight);
 * }
 * if (entry != CgBlockCopy.AIR) copy.set(x, y, z, entry);
 *
 * // a block whose shape reads its neighbours or its position: no key, described where it stands
 * copy.set(x, y, z, copy.add(CgBlockCopy.NO_KEY, boxes, n, fluidKind, fluidHeight));
 * }</pre>
 *
 * <ul>
 *   <li>A key is the host's own int for "blocks with these same boxes and fluid", never negative. A fluid's height
 *       follows its neighbour above in Minecraft, so a host folds that into the key.</li>
 *   <li>{@link #set} takes absolute coordinates inside the box the host was asked for; outside it is a bug.</li>
 *   <li>The host's thread, during {@code copyBlocks} only.</li>
 * </ul>
 */
public interface CgBlockCopy {

    /** The entry every block starts as: no boxes, no fluid. */
    int AIR = 0;

    /** {@link #add}'s key for a block described where it stands, remembered under no key. */
    int NO_KEY = -1;

    /** The entry added under {@code key}, or -1 if none was yet. */
    int entry(int key);

    /**
     * Adds a block's collision boxes ({@code count} of them, block-local, min x, y, z then max x, y, z) and fluid (a
     * {@code CgWorldQuery.FLUID_} kind and its height, NaN for none), remembered under {@code key}; answers its entry.
     * Blocks alike share an entry whatever their keys.
     */
    int add(int key, float[] boxes, int count, int fluidKind, float fluidHeight);

    /** Sets a block's entry. */
    void set(int x, int y, int z, int entry);

    /** Marks the column at block {@code (x, z)} loaded. */
    void loaded(int x, int z);
}
