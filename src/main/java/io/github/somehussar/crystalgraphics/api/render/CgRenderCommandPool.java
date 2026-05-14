package io.github.somehussar.crystalgraphics.api.render;

import java.util.Arrays;

/**
 * Pre-allocated pool of {@link CgRenderCommand} slots.
 * Zero allocations in the steady state. Grows with {@link Arrays#copyOf} when exhausted.
 *
 * <p>GC contract: all JOML matrix/vector objects are allocated in pool constructor, never
 * replaced. No ArrayList, no HashMap, no Iterator in hot path.</p>
 */
public final class CgRenderCommandPool {

    private static final int INITIAL_CAPACITY = 256;
    private static final float GROWTH_FACTOR  = 1.5f;

    /** Pre-allocated command slots. Grows with Arrays.copyOf(slots, newLen) only on exhaustion. */
    private CgRenderCommand[] slots;

    /** Stack pointer: index of the next available free slot. */
    private int freeTop;

    /** Free-list indices. Pre-allocated. Grows in sync with slots. */
    private int[] freeStack;

    /**
     * Creates a new pool with {@value #INITIAL_CAPACITY} pre-allocated command slots.
     * Each slot is fully initialised with pre-allocated JOML matrices.
     */
    public CgRenderCommandPool() {
        slots     = new CgRenderCommand[INITIAL_CAPACITY];
        freeStack = new int[INITIAL_CAPACITY];
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            slots[i]          = new CgRenderCommand();
            slots[i].pool     = this;
            slots[i].acquired = false;
            // JOML matrices initialise to identity by default — no extra init needed
            freeStack[i]      = i;
        }
        freeTop = INITIAL_CAPACITY;
    }

    /**
     * Acquires a command slot. Grows pool if exhausted (Arrays.copyOf — O(N), amortised O(1)).
     * Never returns null. Calls reset() on the returned slot.
     */
    public CgRenderCommand acquire() {
        if (freeTop == 0) grow();
        int idx = freeStack[--freeTop];
        CgRenderCommand cmd = slots[idx];
        cmd.reset();
        cmd.acquired = true;
        return cmd;
    }

    /**
     * Returns a command slot to the pool. Safe to call multiple times — idempotent.
     * Uses an O(N) identity scan to find the slot index; pool is small so this is acceptable.
     *
     * @param cmd the command to release; must belong to this pool
     */
    public void release(CgRenderCommand cmd) {
        if (!cmd.acquired) return;
        cmd.acquired = false;
        // Find slot index via identity scan (O(N) — pool is small)
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == cmd) {
                freeStack[freeTop++] = i;
                return;
            }
        }
    }

    /**
     * Releases all acquired commands at once (frame-end bulk release).
     * O(N) sweep — resets all slots and rebuilds freeStack in index order.
     */
    public void releaseAll() {
        freeTop = 0;
        for (int i = 0; i < slots.length; i++) {
            slots[i].acquired = false;
            freeStack[freeTop++] = i;
        }
    }

    /** Returns the number of pre-allocated slots (including both acquired and free). */
    public int capacity() {
        return slots.length;
    }

    private void grow() {
        int oldLen = slots.length;
        int newLen = (int) (oldLen * GROWTH_FACTOR);
        slots     = Arrays.copyOf(slots, newLen);
        freeStack = Arrays.copyOf(freeStack, newLen);
        // Allocate new CgRenderCommand objects for the new slots
        for (int i = oldLen; i < newLen; i++) {
            slots[i]          = new CgRenderCommand();
            slots[i].pool     = this;
            slots[i].acquired = false;
            // JOML Matrix4f starts as identity — no init call needed
            freeStack[freeTop++] = i;
        }
    }
}
