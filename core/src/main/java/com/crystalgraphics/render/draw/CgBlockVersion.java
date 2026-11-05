package com.crystalgraphics.render.draw;

import java.util.Arrays;

/**
 * One version of a uniform block that outlives frames: a material's parameters as they were when a property last
 * moved. Written once into persistent memory the first time a snapshot holding it executes, and bound from there by
 * every draw after, so an unchanged material uploads nothing (Unreal's cached material uniform buffer).
 *
 * <pre>{@code
 * CgBlockVersion v = CgBlockVersion.of(packed, floats);       // when a value moved
 * int id = table.begin().block(binding, v).end();             // every capture until the next change
 * v.supersede();                                              // replaced: freed once its frames retire
 * }</pre>
 *
 * <ul>
 *   <li>A value: its floats never change. A changed block is a new version; a snapshot keeps the one it captured.</li>
 *   <li>{@link #supersede} once nothing will capture it again, or its memory is kept for the session.</li>
 *   <li>Made on any thread; its placement is the render thread's ({@code CgBindingTable}).</li>
 * </ul>
 */
public final class CgBlockVersion {

    final float[] floats;
    /** Where it was written: a page's buffer, its range's node and its byte offset; buffer 0 until written. */
    int buffer, node = -1, offset;
    CgBindingTable.BlockStore.Page page;
    /** The last frame a draw bound it in. */
    long lastUse;
    volatile boolean superseded;

    private CgBlockVersion(float[] floats) {
        this.floats = floats;
    }

    /** A version holding {@code data[0, count)}, copied. */
    public static CgBlockVersion of(float[] data, int count) {
        return new CgBlockVersion(Arrays.copyOf(data, count));
    }

    /** Its floats' count. */
    public int floatCount() {
        return floats.length;
    }

    /** A copy of its floats. */
    public float[] floats() {
        return floats.clone();
    }

    /** Never captured again: its memory is freed once the frames that drew it retire. */
    public void supersede() {
        superseded = true;
    }
}
