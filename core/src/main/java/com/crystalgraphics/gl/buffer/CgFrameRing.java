package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The frame clock every streaming ring shares, and the answer to "has the GPU finished frame N yet": each frame's end
 * notes the device frame its work went into, and a wait for it is the device's.
 *
 * <p>A ring keeps a region per frame in flight and reuses frame N's region in frame N + {@link #FRAMES}. Before it
 * writes there it asks this class to wait for frame N, which three frames later has almost always long since
 * retired.</p>
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
 * <p>A host that never ends a frame leaves {@link #frame()} where it is; a ring then fills its one region and grows,
 * which is correct and merely unpipelined. Render thread only.</p>
 */
public final class CgFrameRing {

    /** Frames in flight: how many regions a ring keeps, and how far behind the GPU may run. */
    public static final int FRAMES = 3;

    /** Blocked on the GPU: a wait, so a report keeps it out of the cost tables. */
    private static final int WAIT = CgTrace.waitName("frameRing.wait");

    /** Per region: the device frame its ring frame's work went into, -1 for none noted. */
    private static final long[] deviceFrames = {-1, -1, -1};
    private static final long[] fenceFrame = new long[FRAMES];
    private static long frame;
    private static long retired = -1;

    private CgFrameRing() {}

    /** The frame now being recorded. Starts at 0 and advances at {@link #endFrame()}. */
    public static long frame() {
        return frame;
    }

    /**
     * The newest frame the GPU is known to have finished, -1 for none: whatever was issued in it or before has finished
     * too, known with no query.
     */
    public static long retired() {
        return retired;
    }

    /** Which of a ring's {@link #FRAMES} regions belongs to {@code frame}. */
    public static int region(long frame) {
        return (int) (frame % FRAMES);
    }

    /** Notes the device frame this frame's work went into, and starts the next frame. */
    public static void endFrame() {
        int slot = region(frame);
        deviceFrames[slot] = CgRHI.installed() ? CgRHI.get().frameIndex() : -1;
        fenceFrame[slot] = frame;
        frame++;
    }

    /**
     * Blocks until the GPU has finished {@code target}, and everything before it.
     *
     * @throws IllegalStateException on a hosted device that has not: its host submits, and a wait would never end
     */
    public static void awaitRetired(long target) {
        if (target <= retired) return;
        int slot = region(target);
        long device = deviceFrames[slot];
        if (device < 0 || fenceFrame[slot] != target) {
            // Nothing was noted for it: the host has not ended that frame, so nothing of it is in flight.
            return;
        }
        CgRHI rhi = CgRHI.get();
        if (device > rhi.retiredFrame()) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, WAIT)) {
                rhi.waitRetired(device);
            }
        }
        deviceFrames[slot] = -1;
        retired = target;
    }

    /** Forgets every frame. At the device's teardown; the next device starts at frame 0. */
    public static void reset() {
        for (int i = 0; i < FRAMES; i++) deviceFrames[i] = -1;
        frame = 0;
        retired = -1;
    }
}
