package com.crystalgraphics.platform;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The thread frames are drawn on, and a way onto it from any other: what lets a service whose host is bound to that
 * thread (the clipboard, sounds, the cursor) be called from a worker or a UI thread of its own.
 *
 * <pre>{@code
 * CgFrameThread.run(() -> toolkit.setClipboard(text));   // now on the frame thread, at the next frame's start elsewhere
 * if (CgFrameThread.isCurrent()) answerNow(); else queue();
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread is the one {@link #drain()} runs on: the host's frame, once a frame
 *       ({@code CgGraphicsLifecycle.tickFrame}). Before the first, start-up is single-threaded and every thread counts as
 *       it.</li>
 *   <li>Work queued from another thread runs in the order it was queued, at the start of the next frame, before anything
 *       that frame draws.</li>
 *   <li>Work that throws is logged and the rest runs on.</li>
 *   <li>Where nothing draws frames (a dedicated server, a test that never drains), queued work never runs: a service on
 *       this has nothing to reach there anyway.</li>
 * </ul>
 */
public final class CgFrameThread {

    private static final Logger LOGGER = LogManager.getLogger("CgPlatform");
    private static final Queue<Runnable> QUEUED = new ConcurrentLinkedQueue<>();
    /** Null until the first frame. */
    private static volatile Thread frameThread;

    private CgFrameThread() {
    }

    /** Whether the calling thread is the frame thread; true for every thread before the first frame. */
    public static boolean isCurrent() {
        Thread bound = frameThread;
        return bound == null || bound == Thread.currentThread();
    }

    /** Runs {@code work} now on the frame thread, or queues it for the start of the next frame. */
    public static void run(Runnable work) {
        if (isCurrent()) work.run();
        else QUEUED.add(work);
    }

    /**
     * Binds the calling thread as the frame thread and runs what was queued for it. The host's frame calls it, once a
     * frame; nothing else should.
     */
    public static void drain() {
        frameThread = Thread.currentThread();
        for (Runnable work; (work = QUEUED.poll()) != null; ) {
            try {
                work.run();
            } catch (RuntimeException | LinkageError failed) {
                LOGGER.error("Work queued for the frame thread threw", failed);
            }
        }
    }

    /** Forgets the frame thread and drops what is queued. Tests. */
    public static void resetForTesting() {
        frameThread = null;
        QUEUED.clear();
    }
}
