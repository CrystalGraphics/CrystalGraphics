package com.crystalgraphics.render.draw;

import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.util.Arrays;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * One shader's material blocks side by side: a storage buffer of fixed slots, a slot a {@link CgBlockVersion}, so draws
 * of different materials of the shader bind one buffer and join in one multi-draw, each instance reading its material
 * from the slot its id carries (Bevy's bindless material slab, {@code MaterialBindGroupAllocator}). A shader's owner
 * makes one per block layout; a material's versions take slots.
 *
 * <pre>{@code
 * CgMaterialArena arena = new CgMaterialArena("dual_path_test", format.getFloatCount());
 * CgBlockVersion v = CgBlockVersion.in(arena, packed, floats);        // a material's values, when one moved
 * int id = table.begin().block(CgBindingModel.MATERIAL_BLOCK.packed(), v).end();
 * int slot = frameTable.slotOf(id);                                   // what its draws' ids carry, at frame build
 * }</pre>
 *
 * <ul>
 *   <li>A slot is {@link #stride()} bytes, the block rounded to 256, every device's storage offset alignment: a batch
 *       that cannot carry slots in its ids binds its own slot as a range, which the shader tells by its length.</li>
 *   <li>Slots are given on any thread. The buffer is {@link CgFrameBindings}'s block store's, on the render thread: grown
 *       by copy at a table's upload, the one it outgrew deleted once its frames retire.</li>
 *   <li>At most {@value #MAX_SLOTS} live slots; every arena's are counted as {@code material.arena-slots}.</li>
 * </ul>
 */
public final class CgMaterialArena {

    public static final int MAX_SLOTS = 4096;
    static final int ALIGNMENT = 256;
    /** More than one: the whole buffer is told from one slot's range by its length. */
    static final int FIRST_SLOTS = 16;

    final String name;
    private final int stride;

    /** Slots given back, and the first never given: under this. */
    private int[] free = new int[16];
    private int freeCount, highWater;

    /** Render thread, the block store's: the buffer, null before its first slot, and the slots it holds. */
    CgGpuBuffer buffer;
    int capacity;

    public CgMaterialArena(String name, int floats) {
        this.name = name;
        this.stride = stride(floats);
    }

    /** The bytes a slot of a block of {@code floats} floats takes: its std140 size rounded to {@value #ALIGNMENT}. */
    public static int stride(int floats) {
        int bytes = Math.max(16, (floats * 4 + 15) & ~15);
        return (bytes + ALIGNMENT - 1) / ALIGNMENT * ALIGNMENT;
    }

    /** Bytes a slot. */
    public int stride() {
        return stride;
    }

    /** {@code v}'s slot, given now if it holds none, pinned until {@link #unpin}; its write is the next upload's. */
    int slotOf(CgBlockVersion v) {
        synchronized (this) {
            if (v.slot < 0) {
                if (freeCount > 0) {
                    v.slot = free[--freeCount];
                } else {
                    if (highWater == MAX_SLOTS) {
                        throw new IllegalStateException(this + " holds " + MAX_SLOTS + " live material blocks: no slot left");
                    }
                    v.slot = highWater++;
                }
                v.written = false;
            }
            v.pins++;
            return v.slot;
        }
    }

    /** Releases a {@link #slotOf} pin: the frame that asked has uploaded, or will not. */
    void unpin(CgBlockVersion v) {
        synchronized (this) {
            v.pins--;
        }
    }

    /** Frees {@code v}'s slot if nothing will read it again: superseded, unpinned, its frames retired. */
    boolean free(CgBlockVersion v, long safe) {
        synchronized (this) {
            if (!v.superseded || v.pins > 0 || v.lastUse > safe || v.slot < 0) return false;
            CgFrameRing.awaitRetired(v.lastUse);
            if (freeCount == free.length) free = Arrays.copyOf(free, freeCount * 2);
            free[freeCount++] = v.slot;
            v.slot = -1;
            return true;
        }
    }

    /** Slots given and not freed. */
    synchronized int live() {
        return highWater - freeCount;
    }

    /** The slots the buffer must hold: every slot ever given. */
    synchronized int reach() {
        return highWater;
    }

    @Override
    public String toString() {
        return "material arena " + name + " (" + stride + " B a slot)";
    }
}
