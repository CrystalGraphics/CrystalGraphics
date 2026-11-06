package com.crystalgraphics.platform.rhi.memory;

import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.*;

/** Textures never alive together share a heap's memory; async work still pending on a range keeps it apart. */
public class CgTransientAllocatorTest {

    private final CgNullRHI rhi = new CgNullRHI(8, 8).withoutLog();
    private final CgTransientMemory memory = rhi.transientMemory();
    private final CgTransientAllocator heaps = new CgTransientAllocator(rhi, memory);
    private final CgTransientMemory.Requirements need = memory.requirements(desc("bloom", 512));
    /** The device's own objects: its surface's images. */
    private final int base = rhi.liveObjects();

    private static CgGpuTexture.Desc desc(String label, int size) {
        return new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2, CgFormat.RGBA16_FLOAT, size, size, 1, 1, 1,
                CgGpuTexture.Usage.ALL);
    }

    @Test
    public void texturesApartInTimeShareARangeAndThoseAliveTogetherDoNot() {
        long bloom = heaps.allocate(need, 0);
        heaps.free(bloom, need.size(), 0);
        long blur = heaps.allocate(need, 0);
        long other = heaps.allocate(need, 0);
        heaps.free(blur, need.size(), 0);
        heaps.free(other, need.size(), 0);
        heaps.endFrame(0);
        assertEquals("blur takes bloom's range once bloom is done", bloom, blur);
        assertNotEquals("alive beside blur", blur, other);
        assertEquals("one heap of the least size", CgTransientAllocator.MIN_HEAP, heaps.heapBytes());
        assertEquals("two textures at the most at once", 2 * need.size(), heaps.framePeak());
    }

    @Test
    public void aWarmFrameAllocatesNothing() {
        for (int warm = 0; warm < 20; warm++) frame();
        int objects = rhi.liveObjects();
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long quiet = Long.MAX_VALUE;
        for (int window = 0; window < 8; window++) {
            long from = threads.getThreadAllocatedBytes(thread);
            for (int f = 0; f < 100; f++) frame();
            quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - from);
        }
        assertEquals("device objects made once warm", objects, rhi.liveObjects());
        assertEquals("heap bytes over 100 frames", 0, quiet);
    }

    /** Bloom then blur, apart in time, then two alive together: what a post chain's frame asks. */
    private void frame() {
        long bloom = heaps.allocate(need, 0);
        heaps.free(bloom, need.size(), 0);
        long blur = heaps.allocate(need, 0);
        long other = heaps.allocate(need, 0);
        heaps.free(blur, need.size(), 0);
        heaps.free(other, need.size(), 0);
        heaps.endFrame(0);
    }

    @Test
    public void aRangeFreedWithAsyncWorkPendingIsSkippedUntilItsQueueWaits() {
        long field = heaps.allocate(need, 0);
        heaps.free(field, need.size(), 5);   // last written by async work at point 5
        long before = heaps.allocate(need, 4);
        assertNotEquals("the frame's queue has waited for point 4 only", field, before);
        heaps.free(before, need.size(), 0);
        long async = heaps.allocate(need, Long.MAX_VALUE);
        assertEquals("the async queue runs in order after it", field, async);
        heaps.free(async, need.size(), 5);
        long after = heaps.allocate(need, 5);
        assertEquals("waited for", field, after);
        heaps.free(after, need.size(), 0);
    }

    @Test
    public void aHeapGoesOnceNothingIsPlacedInItOrAllocatedForItsLatency() {
        long range = heaps.allocate(need, 0);
        CgGpuTexture bloom = heaps.place(range, desc("bloom", 512));
        heaps.discard(bloom);
        heaps.free(range, need.size(), 0);
        for (int f = 0; f < 2 * CgTransientAllocator.GC_FRAMES; f++) heaps.endFrame(0);
        assertEquals("kept while a texture is placed in it", CgTransientAllocator.MIN_HEAP, heaps.heapBytes());
        rhi.release(bloom);
        heaps.unplaced(range);
        for (int f = 0; f < CgTransientAllocator.GC_FRAMES; f++) heaps.endFrame(0);
        assertEquals(0, heaps.heapBytes());
        rhi.endFrame();
        rhi.retireAll();
        assertEquals("the texture and the heap destroyed", 0, rhi.liveObjects() - base);
    }

    @Test
    public void aTextureLargerThanAHeapGetsAHeapOfItsSize() {
        CgTransientMemory.Requirements big = memory.requirements(desc("huge", 8192));
        assertTrue(big.size() > CgTransientAllocator.MIN_HEAP);
        long range = heaps.allocate(big, 0);
        CgGpuTexture huge = heaps.place(range, desc("huge", 8192));
        assertEquals(big.size(), heaps.heapBytes());
        rhi.release(huge);
        heaps.unplaced(range);
        heaps.free(range, big.size(), 0);
    }
}
