package com.crystalgraphics.render.draw;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * One version of a uniform block that outlives frames: a material's parameters as they were when a property last
 * moved. Written once into persistent memory the first time a frame binding it executes, and bound from there by
 * every draw after, so an unchanged material uploads nothing (Unreal's cached material uniform buffer).
 *
 * <pre>{@code
 * CgBlockVersion v = CgBlockVersion.of(packed, floats);       // when a value moved
 * CgMaterialBindings b = CgMaterialBindings.builder().block(CgBindingModel.MATERIAL_BLOCK, v).build();
 * v.supersede();                                              // replaced: freed once its frames retire
 *
 * CgBlockVersion w = CgBlockVersion.in(arena, packed, floats); // a slot of its shader's arena instead
 * }</pre>
 *
 * <ul>
 *   <li>A value: its floats never change. A changed block is a new version; a draw keeps the one it named.</li>
 *   <li>{@link #supersede} once nothing will capture it again, or its memory is kept for the session.</li>
 *   <li>Made on any thread; its placement is the render thread's ({@code CgFrameBindings}).</li>
 * </ul>
 */
public final class CgBlockVersion {

    final float[] floats;
    /** The arena whose slot it takes ({@link #in}); null for a range of a page. */
    @Nullable
    final CgMaterialArena arena;
    /** Where it was written: a page's buffer, its range's node and its byte offset; buffer null until written. */
    CgGpuBuffer buffer;
    int node = -1, offset;
    CgFrameBindings.BlockStore.Page page;
    /** In an arena, under its lock: its slot, -1 for none; the frames built naming it and not yet uploaded. */
    int slot = -1, pins;
    /** In an arena: whether its slot holds its floats. */
    boolean written;
    /** The last frame a draw bound it in. */
    long lastUse;
    volatile boolean superseded;

    private CgBlockVersion(float[] floats, @Nullable CgMaterialArena arena) {
        this.floats = floats;
        this.arena = arena;
    }

    /** A version holding {@code data[0, count)}, copied. */
    public static CgBlockVersion of(float[] data, int count) {
        return new CgBlockVersion(Arrays.copyOf(data, count), null);
    }

    /**
     * A version holding {@code data[0, count)} in a slot of {@code arena}: bound as the arena, its draws' ids naming
     * the slot, so draws of other versions of the arena join with its.
     *
     * @throws IllegalArgumentException if {@code count} floats are more than the arena's slot holds
     */
    public static CgBlockVersion in(CgMaterialArena arena, float[] data, int count) {
        if (count * 4 > arena.stride()) throw new IllegalArgumentException("a block of " + count + " floats in " + arena);
        return new CgBlockVersion(Arrays.copyOf(data, count), arena);
    }

    /** The arena it takes a slot of; null for a version of its own. */
    @Nullable
    public CgMaterialArena arena() {
        return arena;
    }

    /** Its floats' count. */
    public int floatCount() {
        return floats.length;
    }

    /** A copy of its floats. */
    public float[] floats() {
        return floats.clone();
    }

    /** Whether it holds {@code data[0, count)}: a writer's floats a new version would only repeat. */
    public boolean holds(float[] data, int count) {
        if (floats.length != count) return false;
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(floats[i]) != Float.floatToRawIntBits(data[i])) return false;
        }
        return true;
    }

    /** Never captured again: its memory is freed once the frames that drew it retire. */
    public void supersede() {
        superseded = true;
    }
}
