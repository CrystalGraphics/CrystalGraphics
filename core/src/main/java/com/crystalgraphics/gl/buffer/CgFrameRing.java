package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The frame clock every streaming ring shares: one fence per frame, and the answer to "has the GPU finished
 * frame N yet".
 *
 * <p>A ring keeps a region per frame in flight and reuses frame N's region in frame N + {@link #FRAMES}. Before
 * it writes there it asks this class to wait for frame N — which, three frames later, has almost always long
 * since finished, so the wait costs a {@code glClientWaitSync} that returns at once.</p>
 *
 * <pre>{@code
 * // A ring, at its first upload of a frame:
 * long frame = CgFrameRing.frame();
 * CgFrameRing.awaitRetired(frame - CgFrameRing.FRAMES);   // the frame that last wrote this region
 * int region = CgFrameRing.region(frame);
 *
 * // The host's frame end -- CgGraphicsLifecycle.tickFrame() calls it, nothing else should:
 * CgFrameRing.endFrame();
 * }</pre>
 *
 * <p>A host that never ends a frame leaves {@link #frame()} where it is; a ring then fills its one region and
 * orphans, which is correct and merely unpipelined. GL thread only.</p>
 */
public final class CgFrameRing {

    /** Frames in flight: how many regions a ring keeps, and how far behind the GPU may run. */
    public static final int FRAMES = 3;

    private static final long WAIT_SLICE_NS = 1_000_000L;
    private static final long WAIT_LIMIT_NS = 5_000_000_000L;

    private static final long[] fences = new long[FRAMES];
    private static final long[] fenceFrame = new long[FRAMES];
    private static long frame;
    private static long retired = -1;

    private CgFrameRing() {}

    /** The frame now being recorded. Starts at 0 and advances at {@link #endFrame()}. */
    public static long frame() {
        return frame;
    }

    /** Which of a ring's {@link #FRAMES} regions belongs to {@code frame}. */
    public static int region(long frame) {
        return (int) (frame % FRAMES);
    }

    /** Places this frame's fence and starts the next frame. */
    public static void endFrame() {
        int slot = region(frame);
        // Frame (frame - FRAMES)'s fence: nobody waited on it, so nobody needed it. Later fences retire it.
        if (fences[slot] != 0) CgGL.glDeleteSync(fences[slot]);
        fences[slot] = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        fenceFrame[slot] = frame;
        frame++;
    }

    /**
     * Blocks until the GPU has finished {@code target}, and everything before it.
     *
     * @throws IllegalStateException if the GPU has not finished it within five seconds -- a hang, not a wait
     */
    public static void awaitRetired(long target) {
        if (target <= retired) return;
        int slot = region(target);
        long fence = fences[slot];
        if (fence == 0 || fenceFrame[slot] != target) {
            // No fence was ever placed for it: the host has not ended that frame, so there is nothing in
            // flight to wait for from it.
            return;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "frameRing.wait")) {
            waitFor(fence, target);
        }
        CgGL.glDeleteSync(fence);
        fences[slot] = 0;
        retired = target;
    }

    private static void waitFor(long fence, long target) {
        long waited = 0;
        while (true) {
            int result = CgGL.glClientWaitSync(fence, CgGL.GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_SLICE_NS);
            if (result == CgGL.GL_ALREADY_SIGNALED || result == CgGL.GL_CONDITION_SATISFIED) break;
            if (result == CgGL.GL_WAIT_FAILED) throw new IllegalStateException("glClientWaitSync failed on frame " + target);
            waited += WAIT_SLICE_NS;
            if (waited >= WAIT_LIMIT_NS) {
                throw new IllegalStateException("frame " + target + " not finished by the GPU within 5s -- possible GPU hang");
            }
        }
    }

    /** Drops every fence. For context teardown; the next context starts at frame 0. */
    public static void reset() {
        for (int i = 0; i < FRAMES; i++) {
            if (fences[i] != 0) CgGL.glDeleteSync(fences[i]);
            fences[i] = 0;
        }
        frame = 0;
        retired = -1;
    }
}
