package com.crystalgraphics.job;

import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The threads jobs' work runs on ({@code crystalgraphics-job-*}), shared by every loop: daemon, a step below normal
 * priority so the game's threads come first, started at the first job. Which job a free thread takes is
 * {@link CgJobQueue}'s.
 */
final class CgJobPool {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final CgVar.Int THREADS_VAR = CgVar.integer("crystalgraphics.job.threads", 0, 0, 256,
            CgVar.DEV | CgVar.RESTART, "Threads jobs' work runs on; 0 for half the cores past two, at least one.");
    static final int THREADS = THREADS_VAR.get() > 0 ? THREADS_VAR.get()
            : Math.max(1, (Runtime.getRuntime().availableProcessors() - 2) / 2);

    private static final class Pool {
        static final Object LOCK = new Object();
        static final CgJobQueue QUEUE = new CgJobQueue(THREADS, System::nanoTime);

        static {
            for (int i = 0; i < THREADS; i++) new JobThread(i).start();
        }
    }

    /** A job worker: {@code CgWorkers} gives its {@code forEach} the background helpers. */
    static final class JobThread extends Thread implements CgWorkerThread {
        JobThread(int index) {
            super("crystalgraphics-job-" + index);
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }

        @Override
        public void run() {
            CgJobQueue.Entry e = null;
            while (true) {
                synchronized (Pool.LOCK) {
                    if (e != null) Pool.QUEUE.finished(e);
                    // a background entry the cap held back is taken by whichever thread finishes one
                    while ((e = Pool.QUEUE.take()) == null) {
                        try {
                            Pool.LOCK.wait();
                        } catch (InterruptedException ignored) {
                            // daemon: nothing to stop for
                        }
                    }
                }
                try {
                    e.runQueued();
                } catch (Throwable t) {
                    LOGGER.error("[cg-job] a job's worker step threw", t);
                }
            }
        }
    }

    private CgJobPool() {
    }

    static void submit(CgJobQueue.Entry job) {
        synchronized (Pool.LOCK) {
            Pool.QUEUE.add(job);
            Pool.LOCK.notify();
        }
    }
}
