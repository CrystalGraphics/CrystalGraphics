package com.crystalgraphics.gpu;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
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
 * private final CgDeferral gpu = new CgDeferral(CgGlSlot.TEXTURES);
 *
 * public void upload(ByteBuffer pixels) {
 *     if (gpu.immediate()) {
 *         try (CgGlScope ignored = gpu.restoring()) {
 *             texSubImage(pixels);                  // here and now, from the caller's buffer
 *         }
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
 *   <li>Work that binds something to do its job names what to restore, or it leaks into whoever draws next:
 *       {@code new CgDeferral(CgGlSlot.TEXTURES)} runs each piece of work inside a scope saving the texture
 *       bindings.</li>
 * </ul>
 */
public final class CgDeferral {

    private static final Logger LOGGER = LogManager.getLogger("CgDeferral");
    private static final int APPLY = CgTrace.name("deferral.apply");
    /** One queued task: its own time is the GL work around the uploads zoned inside it. */
    private static final int TASK = CgTrace.name("deferral.task");

    /** Objects with queued work, oldest first. Guarded by itself. */
    private static final Set<CgDeferral> SCHEDULED = new LinkedHashSet<>();

    private static final CgVar.Num SPREAD_MILLIS = CgVar.number("crystalgraphics.deferral.budget", 4f, 0f, 1000f,
            CgVar.DEV, "Milliseconds a frame lands queued work of objects that land their own before each use (textures);"
                    + " the rest waits a frame. 0 for no limit.");
    private static final CgVar.Num SPREAD_MEGABYTES = CgVar.number("crystalgraphics.deferral.megabytes", 4f, 0f, 4096f,
            CgVar.DEV, "Megabytes of upload leases a frame lands for objects that land their own before each use (textures):"
                    + " the GPU's copy and mips of a kit's 17 MB in one frame took 156 ms. 0 for no limit.");

    /** Guarded by this. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    /** What each piece of work is scoped to restore; empty for none. */
    private final CgGlSlot[] restores;
    /** Whether its work may land over frames: its owner flushes it before every use. */
    private boolean spread;

    /** Work that leaves GL state as it found it, or restores it itself. */
    public CgDeferral() {
        this(new CgGlSlot[0]);
    }

    /** Work restoring {@code restores} when each piece is done. */
    public CgDeferral(CgGlSlot... restores) {
        this.restores = restores;
    }

    /**
     * Lets {@link #applyAll()} land this object's work over several frames, within {@code crystalgraphics.deferral.budget}
     * milliseconds and {@code .megabytes} each, as Unreal's texture streaming caps its uploads a frame: a kit's textures decoded together no longer land in
     * one frame. Only for an owner that {@link #flush()}es before every use (a texture's bind and {@code getId}), so
     * nothing ever draws what has not landed. Answers this.
     */
    public CgDeferral spreadOverFrames() {
        spread = true;
        return this;
    }

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
        try (CgGlScope ignored = restoring()) {
            work.run();
        }
    }

    /**
     * A scope over what this deferral's work restores, for work a caller does directly once {@link #immediate()}
     * held; nothing to open when it restores nothing.
     *
     * <pre>{@code
     * if (gpu.immediate()) {
     *     try (CgGlScope ignored = gpu.restoring()) {
     *         texSubImage(pixels);
     *     }
     *     return;
     * }
     * }</pre>
     */
    public CgGlScope restoring() {
        return restores.length == 0 ? CgGlScope.NOOP_SCOPE : CgGlState.save(restores);
    }

    /** Does what is queued, oldest first, where the device may be driven; nothing elsewhere. Cheap when empty. */
    public void flush() {
        if (!mayDrive()) return;
        Runnable next;
        while ((next = poll()) != null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, TASK); CgGlScope restore = restoring()) {
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

    /**
     * Does every object's queued work, oldest object first: all of it, except that objects {@linkplain #spreadOverFrames()
     * spread over frames} stop once the frame's budget is spent, the first always landing, the rest kept first in line for
     * the next frame. Render thread; the executor calls it before each frame.
     */
    public static void applyAll() {
        CgDeferral[] work;
        synchronized (SCHEDULED) {
            if (SCHEDULED.isEmpty()) return;
            work = SCHEDULED.toArray(new CgDeferral[0]);
            SCHEDULED.clear();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, APPLY)) {
            long budget = (long) (SPREAD_MILLIS.get() * 1_000_000L), start = System.nanoTime();
            long bytes = (long) (SPREAD_MEGABYTES.get() * (1 << 20)), landedAt = CgUploads.landedBytes();
            boolean spent = false;
            int kept = 0;
            for (CgDeferral deferral : work) {
                if (deferral.spread && spent) {
                    work[kept++] = deferral;
                    continue;
                }
                deferral.flush();
                if (deferral.spread && (budget > 0 && System.nanoTime() - start >= budget
                        || bytes > 0 && CgUploads.landedBytes() - landedAt >= bytes)) spent = true;
            }
            if (kept > 0) requeue(work, kept);
        }
    }

    /** {@code work[0, kept)} back in line, ahead of what was scheduled since: oldest first still. */
    private static void requeue(CgDeferral[] work, int kept) {
        synchronized (SCHEDULED) {
            Set<CgDeferral> since = new LinkedHashSet<>(SCHEDULED);
            SCHEDULED.clear();
            for (int i = 0; i < kept; i++) if (work[i].pending()) SCHEDULED.add(work[i]);
            SCHEDULED.addAll(since);
        }
    }
}
