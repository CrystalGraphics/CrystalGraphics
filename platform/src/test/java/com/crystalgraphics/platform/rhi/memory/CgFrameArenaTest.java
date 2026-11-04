package com.crystalgraphics.platform.rhi.memory;

import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.sun.management.ThreadMXBean;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;

import static org.junit.Assert.*;

/** A frame's uploads, once warm: written in place in mapped pages, no allocation on the heap or the device. */
public class CgFrameArenaTest {

    private final CgNullRHI rhi = new CgNullRHI(8, 8).withoutLog();
    private final CgFrameArena arena = new CgFrameArena(new CgSlabAllocator(rhi, true, false, 4L << 20, 256), 256);

    /** About a page and a half a frame, written where the GPU reads it. */
    private void frame() {
        for (int i = 0; i < 400; i++) {
            long at = arena.allocate(1024, rhi.frameIndex(), rhi.retiredFrame());
            CgAllocation page = arena.page();
            ByteBuffer mapped = page.buffer().mapped();
            int base = (int) (page.offset() + at);
            mapped.putFloat(base, i).putInt(base + 4, (int) rhi.frameIndex());
        }
        rhi.endFrame();
    }

    @Test
    public void aWarmFrameAllocatesNothingAndMakesNoBuffer() {
        for (int warm = 0; warm < 10; warm++) frame();
        int buffers = rhi.liveObjects();
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long quiet = Long.MAX_VALUE;
        for (int window = 0; window < 3; window++) {
            long from = threads.getThreadAllocatedBytes(thread);
            for (int f = 0; f < 60; f++) frame();
            quiet = Math.min(quiet, threads.getThreadAllocatedBytes(thread) - from);
        }
        assertEquals("device objects made once warm", buffers, rhi.liveObjects());
        assertEquals("heap bytes over 60 frames of 400 uploads", 0, quiet);
    }

    @Test
    public void aPageIsTakenAgainOnlyOnceItsLastFrameRetires() {
        long at = arena.allocate(CgFrameArena.PAGE, rhi.frameIndex(), rhi.retiredFrame());
        CgAllocation first = arena.page();
        assertEquals(0, at);
        rhi.endFrame();
        arena.allocate(CgFrameArena.PAGE, rhi.frameIndex(), rhi.retiredFrame());
        assertNotSame("frame 0 is still in flight", first, arena.page());
        rhi.endFrame();
        rhi.endFrame();
        rhi.endFrame();
        arena.allocate(CgFrameArena.PAGE, rhi.frameIndex(), rhi.retiredFrame());
        assertSame("frame 0 retired: its page comes back", first, arena.page());
    }
}
