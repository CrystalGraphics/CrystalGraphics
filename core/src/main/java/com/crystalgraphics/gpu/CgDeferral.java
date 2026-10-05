package com.crystalgraphics.gpu;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.util.CgBufferUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

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
 *     gpu.run(pixels, data -> texSubImage(data));   // the caller may reuse pixels once this returns
 * }
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
 *   <li>A queued task that throws is logged and dropped; the rest of the queue still runs.</li>
 *   <li>One owner at a time: an object's work is asked for from one thread at once.</li>
 *   <li>Work that binds something to do its job names what to restore, or it leaks into whoever draws next:
 *       {@code new CgDeferral(CgGlSlot.TEXTURES)} runs each piece of work inside a scope saving the texture
 *       bindings.</li>
 * </ul>
 */
public final class CgDeferral {

    private static final Logger LOGGER = LogManager.getLogger("CgDeferral");

    /** Objects with queued work, oldest first. Guarded by itself. */
    private static final Set<CgDeferral> SCHEDULED = new LinkedHashSet<>();

    /** Guarded by this. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

    /** What each piece of work is scoped to restore; empty for none. */
    private final CgGlSlot[] restores;

    /** Work that leaves GL state as it found it, or restores it itself. */
    public CgDeferral() {
        this(new CgGlSlot[0]);
    }

    /** Work restoring {@code restores} when each piece is done. */
    public CgDeferral(CgGlSlot... restores) {
        this.restores = restores;
    }

    /** Whether {@link #run} would do its work now: this thread may drive the device and nothing is waiting. */
    public synchronized boolean immediate() {
        return queue.isEmpty() && mayDrive();
    }

    /** Whether work is queued. */
    public synchronized boolean pending() {
        return !queue.isEmpty();
    }

    /**
     * Does {@code work} with {@code data}: the caller's buffer itself when it runs now, else a copy taken here and
     * handed over in a render-thread staging buffer when it runs. Either way the caller may reuse {@code data} once
     * this returns, and {@code work} must not keep the buffer it is given. Null is handed over as null.
     */
    public void run(@Nullable ByteBuffer data, Consumer<ByteBuffer> work) {
        if (data == null) {
            run(() -> work.accept(null));
            return;
        }
        if (immediate()) {
            try (CgGlScope ignored = scope()) {
                work.accept(data);
            }
            return;
        }
        byte[] copy = new byte[data.remaining()];
        data.duplicate().get(copy);
        run(() -> work.accept(stagedBytes(copy)));
    }

    /** {@link #run(ByteBuffer, Consumer)} for floats. */
    public void run(FloatBuffer data, Consumer<FloatBuffer> work) {
        if (immediate()) {
            try (CgGlScope ignored = scope()) {
                work.accept(data);
            }
            return;
        }
        float[] copy = new float[data.remaining()];
        data.duplicate().get(copy);
        run(() -> work.accept(stagedFloats(copy)));
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
        try (CgGlScope ignored = scope()) {
            work.run();
        }
    }

    /** A scope over {@link #restores}, for work about to run; nothing to open when there are none. */
    private CgGlScope scope() {
        return restores.length == 0 ? CgGlScope.NOOP_SCOPE : CgGlState.save(restores);
    }

    /** Does what is queued, oldest first, where the device may be driven; nothing elsewhere. Cheap when empty. */
    public void flush() {
        if (!mayDrive()) return;
        Runnable next;
        while ((next = poll()) != null) {
            try (CgGlScope ignored = scope()) {
                next.run();
            } catch (RuntimeException failed) {
                LOGGER.error("deferred GPU work failed", failed);
            }
        }
    }

    /** Drops what is queued: work a delete has made moot. */
    public synchronized void clear() {
        queue.clear();
    }

    /** Queued work runs on the render thread alone, so one staging buffer of each kind serves all of it. */
    private static ByteBuffer stagingBytes;
    private static FloatBuffer stagingFloats;

    private static ByteBuffer stagedBytes(byte[] data) {
        if (stagingBytes == null || stagingBytes.capacity() < data.length) {
            stagingBytes = CgBufferUtils.createByteBuffer(Math.max(data.length, 4096));
        }
        stagingBytes.clear();
        stagingBytes.put(data).flip();
        return stagingBytes;
    }

    private static FloatBuffer stagedFloats(float[] data) {
        if (stagingFloats == null || stagingFloats.capacity() < data.length) {
            stagingFloats = CgBufferUtils.createFloatBuffer(Math.max(data.length, 1024));
        }
        stagingFloats.clear();
        stagingFloats.put(data).flip();
        return stagingFloats;
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
        for (CgDeferral deferral : work) deferral.flush();
    }
}
