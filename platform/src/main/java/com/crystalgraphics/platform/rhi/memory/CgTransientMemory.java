package com.crystalgraphics.platform.rhi.memory;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuHeap;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

/**
 * A device's placed resources (Unreal's {@code FVulkanTransientHeap}): heaps, and textures made over a range of one, so
 * that textures alive at different times of a frame share memory. {@link CgRHI#transientMemory()} gives it where the
 * device can place; {@link CgTransientAllocator} decides the ranges.
 *
 * <pre>{@code
 * CgTransientMemory memory = rhi.transientMemory();          // null on a device that cannot place
 * CgTransientMemory.Requirements need = memory.requirements(desc);
 * CgGpuHeap heap = memory.createHeap("transient 0", 128 << 20, need.memoryTypes());
 * CgGpuTexture bloom = memory.place(heap, 0, desc);          // at an offset aligned to need.alignment()
 * memory.discard(bloom);                                     // each time it takes the memory over
 * ...
 * rhi.release(bloom);                                        // the image alone: its memory is the heap's
 * rhi.release(heap);
 * }</pre>
 *
 * <ul>
 *   <li>A placed texture's contents are undefined after {@link #discard}, which every use of its memory by it must
 *   begin with: its first barrier then waits on whatever used the memory before, on this queue or one it waited for.</li>
 *   <li>A heap outlives every texture placed in it.</li>
 * </ul>
 */
public interface CgTransientMemory {

    /**
     * What a texture needs of a heap: bytes, the alignment of its offset, and the memory types it may be placed in, one
     * bit per {@link CgGpuHeap#memoryType()}.
     */
    record Requirements(long size, long alignment, int memoryTypes) {

        /** Whether {@code heap}'s memory type is one these allow. */
        public boolean fits(CgGpuHeap heap) {
            return (memoryTypes & 1 << heap.memoryType()) != 0;
        }
    }

    /** What {@code desc} needs; the same for equal descriptions, so a caller keeps it. */
    Requirements requirements(CgGpuTexture.Desc desc);

    /** A heap of {@code size} bytes, of a memory type {@code memoryTypes} allows, device-local where one is. */
    CgGpuHeap createHeap(String label, long size, int memoryTypes);

    /** A texture over {@code heap} from {@code offset}, which {@link #requirements} of {@code desc} must fit. */
    CgGpuTexture place(CgGpuHeap heap, long offset, CgGpuTexture.Desc desc);

    /**
     * {@code texture} takes its memory over (Unreal's {@code ERHIAccess::Discard}): its contents are undefined, and its
     * next barrier waits on every earlier use of the memory.
     */
    void discard(CgGpuTexture texture);
}
