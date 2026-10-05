package com.crystalgraphics.gpu;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One GPU object's device work, in the order it was asked for: done at once where this thread may drive the device,
 * and otherwise queued and done on the render thread before the next frame executes. What lets a texture, a mesh or
 * a glyph atlas be made, filled and deleted from a recording or a worker through the same methods as on the render
 * thread.
 *
 * <pre>{@code
 * private final CgDeferral gpu = new CgDeferral();
 *
 * public void upload(ByteBuffer pixels) {
 *     if (gpu.immediate()) {
 *         texSubImage(pixels);                      // here and now, from the caller's buffer
 *         return;
 *     }
 *     gpu.run(CgUploads.copyOf(pixels, false).into(landing, 0, x, y, 0, w, h, 1, format, type));
 * }                                                 // the caller may reuse pixels once this returns
 *
 * public int getId() {
 *     gpu.flush();                                  // render thread: what is queued, first
 *     return id;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>The device may be driven by the thread that owns it, outside a recording. Everywhere else, work queues.</li>
 *   <li>Work behind queued work waits even where it could run, so an object's work keeps its order.</li>
 *   <li>{@link #applyAll()} is the executor's: every object's queue, before a frame executes. Nothing else needs to
 *       know which objects have work.</li>
 *   <li>Data goes in a {@link CgUploadLease}, which is itself the task: copied once by the thread that has it, and
 *       given back when it lands or is cleared.</li>
 *   <li>A queued task that throws is logged and dropped; the rest of the queue still runs.</li>
 *   <li>One owner at a time: an object's work is asked for from one thread at once.</li>
 * </ul>
 */
public final class CgDeferral {

    private static final Logger LOGGER = LogManager.getLogger("CgDeferral");
    private static final int APPLY = CgTrace.name("deferral.apply");
    /** One queued task: its own time is the GL work around the uploads zoned inside it. */
    private static final int TASK = CgTrace.name("deferral.task");

    /** Objects with queued work, oldest first. Guarded by itself. */
    private static final Set<CgDeferral> SCHEDULED = new LinkedHashSet<>();

    /** Guarded by this. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    /** Whether {@link #run} would do its work now: this thread may drive the device and nothing is waiting. */
    public synchronized boolean immediate() {
        return queue.isEmpty() && mayDrive();
    }

    /** Whether work is queued. */
    public synchronized boolean pending() {
        return !queue.isEmpty();
    }

    /** Does {@code work} now where the device may be driven and nothing waits, or queues it behind what does. */
    public void run(Runnable work) {
        synchronized (this) {
            if (!queue.isEmpty() || !mayDrive()) {
                if (queue.isEmpty()) schedule(this);
                queue.add(work);
                return;
            }
        }
        work.run();
    }

    /** Does what is queued, oldest first, where the device may be driven; nothing elsewhere. Cheap when empty. */
    public void flush() {
        if (!mayDrive()) return;
        Runnable next;
        while ((next = poll()) != null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, TASK)) {
                next.run();
            } catch (RuntimeException failed) {
                LOGGER.error("deferred GPU work failed", failed);
            }
        }
    }

    /** Drops what is queued: work a delete has made moot. Its leases are given back. */
    public synchronized void clear() {
        for (Runnable work : queue) {
            if (work instanceof CgUploadLease) ((CgUploadLease) work).release();
        }
        queue.clear();
    }

    /** The thread owns the device and is not recording: the one question every deferral asks. */
    private static boolean mayDrive() {
        return CgGL.mayIssueGl();
    }

    private synchronized Runnable poll() {
        return queue.poll();
    }

    private static void schedule(CgDeferral deferral) {
        synchronized (SCHEDULED) {
            SCHEDULED.add(deferral);
        }
    }

    /** Does every object's queued work, oldest object first. Render thread; the executor calls it before each frame. */
    public static void applyAll() {
        CgDeferral[] work;
        synchronized (SCHEDULED) {
            if (SCHEDULED.isEmpty()) return;
            work = SCHEDULED.toArray(new CgDeferral[0]);
            SCHEDULED.clear();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, APPLY)) {
            for (CgDeferral deferral : work) deferral.flush();
        }
    }
}
