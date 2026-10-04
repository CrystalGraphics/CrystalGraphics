package com.crystalgraphics.vfx;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs {@code count} independent jobs on the render thread and a pool of daemon workers ({@code crystalgraphics-vfx-*})
 * together, each taking the next job until none is left: a tick's emitters one effect a job, a frame's particle records
 * one emitter a job.
 *
 * <pre>{@code
 * private final CgVfxWorkers.Job tickEach = i -> emitting.get(i).tickEmitters();   // held: run allocates nothing
 * workers.run(emitting.size(), tickEach);                                          // returns once all have run
 * }</pre>
 *
 * <ul>
 *   <li>{@code -Dcrystalgraphics.vfx.threads=N} sets how many threads in all, the render thread one of them; 1 runs
 *       everything on the render thread. The default is one per core.</li>
 *   <li>Jobs run in any order and on any thread: one job never reads what another writes.</li>
 *   <li>The pool is made on the first run with two jobs or more, so a server starts no thread.</li>
 * </ul>
 */
final class CgVfxWorkers {

    static final int THREADS = Math.max(1, Integer.getInteger("crystalgraphics.vfx.threads",
            Runtime.getRuntime().availableProcessors()));

    /** One job of a run, by its index. */
    interface Job {
        void run(int index);
    }

    private static final class Pool {
        static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, THREADS - 1), pool -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            t.setName("crystalgraphics-vfx-" + t.getPoolIndex());
            t.setDaemon(true);
            return t;
        }, null, false);
    }

    /** A worker's share of one run, reused across runs. */
    private final class Helper extends RecursiveAction {
        @Override
        protected void compute() {
            drain();
        }
    }

    private final AtomicInteger next = new AtomicInteger();
    private Helper[] helpers = new Helper[0];
    private Job job;
    private int count;

    /** Runs jobs {@code 0..count-1}; returns when all have run. Render thread. */
    void run(int count, Job job) {
        int extra = Math.min(THREADS - 1, count - 1);
        if (extra <= 0) {
            for (int i = 0; i < count; i++) job.run(i);
            return;
        }
        this.job = job;
        this.count = count;
        next.set(0);
        if (helpers.length < extra) {
            helpers = new Helper[extra];
            for (int i = 0; i < extra; i++) helpers[i] = new Helper();
        }
        for (int i = 0; i < extra; i++) {
            helpers[i].reinitialize();
            Pool.POOL.execute(helpers[i]);
        }
        try {
            drain();
        } finally {
            for (int i = 0; i < extra; i++) helpers[i].join();
            this.job = null;
        }
    }

    private void drain() {
        Job j = job;
        int n = count;
        for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) j.run(i);
    }
}
