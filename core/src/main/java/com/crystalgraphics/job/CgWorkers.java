package com.crystalgraphics.job;

import com.crystalgraphics.variable.CgVar;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Runs {@code count} independent items on the calling thread and a pool of daemon workers ({@code crystalgraphics-each-*})
 * together, each taking the next item until none is left: {@link CgJob#forEach}, and effects' emitters each tick.
 *
 * <ul>
 *   <li>{@code -Dcrystalgraphics.job.forEachThreads=N} sets how many threads in all, the caller one of them; 1 runs
 *       everything on the caller. The default is two fewer than the cores, leaving room for the host's own threads.</li>
 *   <li>A pool of its own, apart from jobs' work: {@link #run} joins every helper it queued, so a helper waiting behind a
 *       long job would hold the caller's frame.</li>
 *   <li>The pool is made on the first run with two items or more, so a server starts no thread.</li>
 *   <li>One per thread ({@link #mine}): runs on different threads share the pool, not a runner.</li>
 * </ul>
 */
final class CgWorkers {

    private static final CgVar.Int THREADS_VAR = CgVar.integer("crystalgraphics.job.forEachThreads", 0, 0, 256,
            CgVar.DEV | CgVar.RESTART, "Threads CgJob.forEach and effects' emitters run on, the caller one of them; 0 for"
                    + " two fewer than the cores, 1 for all on the caller.");
    static final int THREADS = THREADS_VAR.get() > 0 ? THREADS_VAR.get()
            : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);

    private static final ThreadLocal<CgWorkers> MINE = ThreadLocal.withInitial(CgWorkers::new);

    private static final class Pool {
        static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, THREADS - 1), EachThread::new, null, false);
    }

    private static final class EachThread extends ForkJoinWorkerThread implements CgWorkerThread {
        EachThread(ForkJoinPool pool) {
            super(pool);
            setName("crystalgraphics-each-" + getPoolIndex());
            setDaemon(true);
        }
    }

    /** A worker's share of one run, reused across runs. */
    private final class Helper extends RecursiveAction {
        @Override
        protected void compute() {
            drain();
        }
    }

    /** A list's items as indices, reused so a list run allocates nothing. */
    private final class EachOf implements IntConsumer {
        List<?> items;
        Consumer<Object> each;

        @Override
        public void accept(int i) {
            each.accept(items.get(i));
        }
    }

    private final AtomicInteger next = new AtomicInteger();
    private final EachOf eachOf = new EachOf();
    private Helper[] helpers = new Helper[0];
    private IntConsumer job;
    private int count;
    private boolean running;

    static CgWorkers mine() {
        return MINE.get();
    }

    /** Runs items {@code 0..count-1}; returns when all have run. */
    void run(int count, IntConsumer job) {
        int extra = Math.min(THREADS - 1, count - 1);
        if (extra <= 0 || running || Thread.currentThread() instanceof EachThread) {
            // one item, one thread, or a run nested in an item: here, in order
            for (int i = 0; i < count; i++) job.accept(i);
            return;
        }
        running = true;
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
            running = false;
        }
    }

    @SuppressWarnings("unchecked")
    <E> void run(List<? extends E> items, Consumer<? super E> each) {
        if (running || Thread.currentThread() instanceof EachThread) {
            for (int i = 0, n = items.size(); i < n; i++) each.accept(items.get(i));
            return;
        }
        eachOf.items = items;
        eachOf.each = (Consumer<Object>) each;
        try {
            run(items.size(), eachOf);
        } finally {
            eachOf.items = null;
            eachOf.each = null;
        }
    }

    private void drain() {
        IntConsumer j = job;
        int n = count;
        for (int i = next.getAndIncrement(); i < n; i = next.getAndIncrement()) j.accept(i);
    }
}
