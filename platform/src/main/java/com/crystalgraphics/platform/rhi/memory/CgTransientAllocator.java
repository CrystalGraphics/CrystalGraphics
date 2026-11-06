package com.crystalgraphics.platform.rhi.memory;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuHeap;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

/**
 * Ranges of {@link CgTransientMemory} heaps for textures alive during part of a frame, so that textures never alive
 * together share memory (Unreal's {@code FRHITransientHeapAllocator} and its heaps, {@code RHICoreTransientResourceAllocator.cpp}).
 * Heaps are at least {@link #MIN_HEAP}; a range is taken first fit and given back at the texture's last use.
 *
 * <pre>{@code
 * CgTransientAllocator heaps = new CgTransientAllocator(rhi, rhi.transientMemory());
 * CgTransientMemory.Requirements need = memory.requirements(desc);       // kept per description
 * long range = heaps.allocate(need, waited);                             // at the texture's first use
 * CgGpuTexture bloom = heaps.place(range, desc);                         // or the one placed there before
 * heaps.discard(bloom);
 * ...
 * heaps.free(range, need.size(), pending);                               // after its last use
 * heaps.endFrame(waited);
 * ...
 * rhi.release(bloom);                                                    // no longer kept for that range
 * heaps.unplaced(range);
 * }</pre>
 *
 * <ul>
 *   <li>A range freed with an async point ({@code fence}, a {@code cgEndAsync} value) is skipped by an allocation whose
 *   {@code joined} is below it, since the frame's queue has not waited for that work yet: as Unreal skips a range whose
 *   fences overlap. An allocation for async work passes {@link Long#MAX_VALUE}: its queue runs after everything
 *   recorded before it.</li>
 *   <li>A heap with nothing allocated and nothing placed for {@link #GC_FRAMES} frames is released.</li>
 *   <li>Render thread. Steady frames allocate nothing.</li>
 * </ul>
 */
public final class CgTransientAllocator {

    /** The least a heap is: Unreal's {@code RHI.TransientAllocator.MinimumHeapSize}. */
    public static final long MIN_HEAP = 128L << 20;
    /** Frames a heap sits unused before it is released: Unreal's {@code RHI.TransientAllocator.GarbageCollectLatency}. */
    public static final int GC_FRAMES = 16;
    private static final long HEAP_GRANULE = 64 << 10;
    private static final int OFFSET_BITS = 40;
    private static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1;

    private final CgRHI rhi;
    private final CgTransientMemory memory;
    private Heap[] heaps = new Heap[4];
    private long heapBytes, used, peak, framePeak;

    public CgTransientAllocator(CgRHI rhi, CgTransientMemory memory) {
        this.rhi = rhi;
        this.memory = memory;
    }

    /**
     * A range for {@code need}, as a handle, in a new heap where no heap has one.
     *
     * @param joined the newest async point the queue using it has waited for
     */
    public long allocate(CgTransientMemory.Requirements need, long joined) {
        for (int h = 0; h < heaps.length; h++) {
            Heap heap = heaps[h];
            if (heap == null || !need.fits(heap.heap)) continue;
            long at = heap.take(need.size(), need.alignment(), joined);
            if (at >= 0) return took(h, at, need.size());
        }
        int h = freeSlot();
        long size = Math.max(MIN_HEAP, alignUp(need.size(), HEAP_GRANULE));
        Heap heap = new Heap(memory.createHeap("transient heap " + h, size, need.memoryTypes()));
        heaps[h] = heap;
        heapBytes += size;
        return took(h, heap.take(need.size(), need.alignment(), joined), need.size());
    }

    /** Gives back {@code size} bytes at {@code range}; {@code fence} is the async point of its last use there, else 0. */
    public void free(long range, long size, long fence) {
        heaps[heap(range)].give(offset(range), size, fence);
        used -= size;
    }

    /** A texture placed at {@code range}; the caller keeps it while the range recurs, and releases it once not. */
    public CgGpuTexture place(long range, CgGpuTexture.Desc desc) {
        Heap heap = heaps[heap(range)];
        heap.placed++;
        return memory.place(heap.heap, offset(range), desc);
    }

    /** A texture placed at {@code range} was released: its heap may then go. */
    public void unplaced(long range) {
        heaps[heap(range)].placed--;
    }

    /** {@link CgTransientMemory#discard}: as a texture takes its range over, before its first use. */
    public void discard(CgGpuTexture texture) {
        memory.discard(texture);
    }

    /**
     * At the frame's end: free ranges whose async work {@code joined} covers are free to all, adjacent ones merge, and a
     * heap unused for {@link #GC_FRAMES} frames is released.
     */
    public void endFrame(long joined) {
        framePeak = peak;
        peak = used;
        for (int h = 0; h < heaps.length; h++) {
            Heap heap = heaps[h];
            if (heap == null) continue;
            heap.flush(joined);
            heap.idle = heap.live > 0 || heap.taken > 0 || heap.placed > 0 ? 0 : heap.idle + 1;
            heap.taken = 0;
            if (heap.idle >= GC_FRAMES) {
                rhi.release(heap.heap);
                heapBytes -= heap.heap.size();
                heaps[h] = null;
            }
        }
    }

    /** Bytes of every heap held. */
    public long heapBytes() {
        return heapBytes;
    }

    /** The most bytes allocated at once in the last frame ended. */
    public long framePeak() {
        return framePeak;
    }

    /** Releases every heap: everything placed must be unplaced first. */
    public void delete() {
        for (int h = 0; h < heaps.length; h++) {
            Heap heap = heaps[h];
            if (heap == null) continue;
            if (heap.placed > 0) throw new IllegalStateException(heap.heap.label() + " still has " + heap.placed
                    + " textures placed in it");
            rhi.release(heap.heap);
            heaps[h] = null;
        }
        heapBytes = 0;
    }

    static int heap(long range) {
        return (int) (range >>> OFFSET_BITS);
    }

    static long offset(long range) {
        return range & OFFSET_MASK;
    }

    private long took(int h, long at, long size) {
        used += size;
        if (used > peak) peak = used;
        return (long) h << OFFSET_BITS | at;
    }

    private int freeSlot() {
        for (int h = 0; h < heaps.length; h++) if (heaps[h] == null) return h;
        Heap[] grown = new Heap[heaps.length * 2];
        System.arraycopy(heaps, 0, grown, 0, heaps.length);
        int h = heaps.length;
        heaps = grown;
        return h;
    }

    private static long alignUp(long x, long alignment) {
        return (x + alignment - 1) / alignment * alignment;
    }

    /** One heap's free ranges, sorted by offset, as parallel arrays. */
    private static final class Heap {
        final CgGpuHeap heap;
        long[] offsets = new long[8], sizes = new long[8], fences = new long[8];
        int count;
        /** Ranges out now, ranges taken this frame, textures placed in it, frames it sat unused. */
        int live, taken, placed, idle;

        Heap(CgGpuHeap heap) {
            this.heap = heap;
            offsets[0] = 0;
            sizes[0] = heap.size();
            count = 1;
        }

        /** The first fit's offset, or -1: the range is split around it, its padding kept free. */
        long take(long size, long alignment, long joined) {
            for (int i = 0; i < count; i++) {
                if (fences[i] > joined) continue;
                long start = offsets[i], end = start + sizes[i];
                long at = alignUp(start, alignment);
                if (at + size > end) continue;
                long fence = fences[i];
                boolean pad = at > start, tail = at + size < end;
                if (pad && tail) {
                    sizes[i] = at - start;
                    insert(i + 1, at + size, end - at - size, fence);
                } else if (pad) {
                    sizes[i] = at - start;
                } else if (tail) {
                    offsets[i] = at + size;
                    sizes[i] = end - at - size;
                } else {
                    remove(i);
                }
                live++;
                taken++;
                return at;
            }
            return -1;
        }

        void give(long offset, long size, long fence) {
            int i = 0;
            while (i < count && offsets[i] < offset) i++;
            insert(i, offset, size, fence);
            if (i + 1 < count) mergeNext(i);
            if (i > 0) mergeNext(i - 1);
            live--;
        }

        /** Fences {@code joined} covers are cleared; then every adjacent pair with equal fences merges. */
        void flush(long joined) {
            for (int i = 0; i < count; i++) if (fences[i] <= joined) fences[i] = 0;
            for (int i = 0; i + 1 < count; ) {
                if (!mergeNext(i)) i++;
            }
        }

        /** Merges range {@code i + 1} into {@code i} where they touch and share a fence. */
        private boolean mergeNext(int i) {
            if (offsets[i] + sizes[i] != offsets[i + 1] || fences[i] != fences[i + 1]) return false;
            sizes[i] += sizes[i + 1];
            remove(i + 1);
            return true;
        }

        private void insert(int i, long offset, long size, long fence) {
            if (count == offsets.length) {
                offsets = grow(offsets);
                sizes = grow(sizes);
                fences = grow(fences);
            }
            System.arraycopy(offsets, i, offsets, i + 1, count - i);
            System.arraycopy(sizes, i, sizes, i + 1, count - i);
            System.arraycopy(fences, i, fences, i + 1, count - i);
            offsets[i] = offset;
            sizes[i] = size;
            fences[i] = fence;
            count++;
        }

        private void remove(int i) {
            System.arraycopy(offsets, i + 1, offsets, i, count - i - 1);
            System.arraycopy(sizes, i + 1, sizes, i, count - i - 1);
            System.arraycopy(fences, i + 1, fences, i, count - i - 1);
            count--;
        }

        private static long[] grow(long[] a) {
            long[] g = new long[a.length * 2];
            System.arraycopy(a, 0, g, 0, a.length);
            return g;
        }
    }
}
