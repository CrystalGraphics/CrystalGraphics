package com.crystalgraphics.job;

import com.crystalgraphics.variable.CgVar;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads jobs' work runs on ({@code crystalgraphics-job-*}): daemon, a step below normal priority so the game's
 * threads come first, made at the first job.
 */
final class CgJobPool {

    private static final CgVar.Int THREADS_VAR = CgVar.integer("crystalgraphics.job.threads", 0, 0, 256,
            CgVar.DEV | CgVar.RESTART, "Threads jobs' work runs on; 0 for half the cores past two, at least one.");
    static final int THREADS = THREADS_VAR.get() > 0 ? THREADS_VAR.get()
            : Math.max(1, (Runtime.getRuntime().availableProcessors() - 2) / 2);

    private static final class Pool {
        static final AtomicInteger NAMED = new AtomicInteger();
        static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(THREADS, THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), JobThread::new);
    }

    private static final class JobThread extends Thread implements CgWorkerThread {
        JobThread(Runnable work) {
            super(work, "crystalgraphics-job-" + Pool.NAMED.getAndIncrement());
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private CgJobPool() {
    }

    static void submit(Runnable work) {
        Pool.POOL.execute(work);
    }
}
