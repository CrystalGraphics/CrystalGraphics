package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFontKey;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Async glyph-generation thread pool owned by {@link CgFontRegistry}.
 *
 * <p>Accepts {@link CgGlyphGenerationJob}s, runs them on worker threads
 * via {@link CgWorkerFontContext}, and collects
 * {@link CgGlyphGenerationResult}s that the registry drains each frame
 * for atlas upload.</p>
 */
final class CgGlyphGenerationExecutor {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphGenerationExecutor.class.getName());
    /**
     * Worker threads generating glyphs in the background.
     *
     * <p>Glyph generation is pure CPU work — around 13 ms for an average glyph and 32 ms for dense
     * CJK, essentially all of it inside a single native call. That invites the assumption that
     * convergence scales inversely with worker count, and it mostly does not.
     *
     * <p><strong>Scaling this with core count was tried and is not worth it.</strong> On an 8-core
     * machine, {@code cores - 2} (six workers) shortened atlas convergence on the CJK scene from
     * 9.3s to only 8.4s. Frame time showed no effect at all: warmup means over three runs were
     * 16.9ms, 19.0ms and 19.2ms with no relationship to worker count, so run-to-run variance on
     * that scene comfortably exceeds whatever the change is worth. Per-glyph cost cannot improve by
     * construction — more workers run more glyphs at once, not each one faster.
     *
     * <p>The reason it barely helped is that these workers were never the constraint. Instrumented
     * across warmup, the pending-job queue drains to zero by 2 seconds and sits empty for most of
     * the run: glyph demand arrives progressively as the scene reveals text, so the workers are
     * idle far more than they are backed up. Convergence is mostly governed by when glyphs are
     * first drawn, not by how fast they can be generated. Before assuming otherwise, check
     * {@code pendingAsync} in the text-3d profile — if it is not pinned near
     * {@link #MAX_PENDING_TASKS}, more workers cannot help.
     *
     * <p>Nor is the consuming side the constraint: the per-frame commit budget admits roughly 12
     * glyphs (1 MB at ~82 KB per MTSDF glyph), about 2.6 seconds for 2000 glyphs. Raising that
     * would achieve nothing either.
     *
     * <p>None of this is on the render thread in any case — glyphs not yet generated fall back to a
     * bitmap raster, so all convergence buys is how long text renders at lower fidelity.
     */
    private static final int DEFAULT_WORKER_COUNT = Math.max(1,
            Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
    private static final int MAX_PENDING_TASKS = 256;

    private final Map<CgGlyphGenerationJob, Boolean> pendingJobs =
            new ConcurrentHashMap<CgGlyphGenerationJob, Boolean>();
    private final Map<CgGlyphGenerationJob, Boolean> failedJobs =
            new ConcurrentHashMap<CgGlyphGenerationJob, Boolean>();
    private final ConcurrentLinkedQueue<CgGlyphGenerationResult> completedResults =
            new ConcurrentLinkedQueue<CgGlyphGenerationResult>();
    private final ConcurrentLinkedQueue<CgWorkerFontContext> workerContexts =
            new ConcurrentLinkedQueue<CgWorkerFontContext>();

    private final ThreadLocal<CgWorkerFontContext> threadLocalContext = new ThreadLocal<CgWorkerFontContext>() {
        @Override
        protected CgWorkerFontContext initialValue() {
            CgWorkerFontContext context = new CgWorkerFontContext();
            workerContexts.add(context);
            return context;
        }
    };

    private final ThreadPoolExecutor executor;
    private volatile boolean shutdown;

    CgGlyphGenerationExecutor() {
        final AtomicInteger threadId = new AtomicInteger(1);
        this.executor = new ThreadPoolExecutor(
                DEFAULT_WORKER_COUNT,
                DEFAULT_WORKER_COUNT,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(MAX_PENDING_TASKS),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "crystalgraphics-glyph-worker-" + threadId.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    boolean submit(final CgGlyphGenerationJob job) {
        if (shutdown || failedJobs.containsKey(job)) {
            return false;
        }
        if (pendingJobs.putIfAbsent(job, Boolean.TRUE) != null) {
            return true;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        CgGlyphGenerationResult result = generate(job);
                        if (result != null) {
                            completedResults.add(result);
                        } else {
                            failedJobs.put(job, Boolean.TRUE);
                        }
                    } catch (Throwable throwable) {
                        failedJobs.put(job, Boolean.TRUE);
                        LOGGER.log(Level.WARNING, "Glyph generation failed for " + job, throwable);
                    } finally {
                        pendingJobs.remove(job);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingJobs.remove(job);
            return false;
        }
    }

    /**
     * Whether an equivalent job is already queued or running on a background worker.
     *
     * <p>Lets the synchronous render-thread path avoid regenerating a glyph that a worker is
     * already producing. Generation costs 13 ms for an average glyph and 32 ms for dense CJK, so
     * duplicating one is not a wasted allocation — it is a dropped frame spent on a result that
     * {@code commitGeneratedGlyph} will discard anyway once the worker's copy lands.
     *
     * <p>Advisory, not a lock: the job may finish immediately after this returns. That is harmless
     * in both directions — a false {@code true} costs a few frames of bitmap fallback, and a false
     * {@code false} costs one redundant generation, which is exactly the status quo.
     *
     * <p>Job equality ignores {@code fontBytes} (see {@link CgGlyphGenerationJob#equals}), so
     * callers can build an equivalent job for this check without holding the font's byte array.
     */
    boolean isPending(CgGlyphGenerationJob job) {
        return pendingJobs.containsKey(job);
    }

    CgGlyphGenerationResult pollCompleted() {
        return completedResults.poll();
    }

    boolean hasPendingWork() {
        return !pendingJobs.isEmpty() || !completedResults.isEmpty();
    }

    int getPendingJobCount() {
        return pendingJobs.size();
    }

    void clearFont(CgFontKey fontKey) {
        clearMatchingJobs(failedJobs, fontKey);
        clearCompletedResults(fontKey);
    }

    boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!hasPendingWork()) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !hasPendingWork();
    }

    void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (CgWorkerFontContext context : workerContexts) {
            context.close();
        }
        workerContexts.clear();
        pendingJobs.clear();
        failedJobs.clear();
        completedResults.clear();
    }

    private CgGlyphGenerationResult generate(CgGlyphGenerationJob job) {
        CgWorkerFontContext context = threadLocalContext.get();
        return job.isDistanceField() ? context.generateMsdf(job) : context.generateBitmap(job);
    }

    private void clearMatchingJobs(Map<CgGlyphGenerationJob, Boolean> jobs, CgFontKey fontKey) {
        java.util.List<CgGlyphGenerationJob> snapshot = new java.util.ArrayList<CgGlyphGenerationJob>(jobs.keySet());
        for (CgGlyphGenerationJob job : snapshot) {
            if (job.getSourceFontKey().equals(fontKey)) {
                jobs.remove(job);
            }
        }
    }

    private void clearCompletedResults(CgFontKey fontKey) {
        ConcurrentLinkedQueue<CgGlyphGenerationResult> retained = new ConcurrentLinkedQueue<CgGlyphGenerationResult>();
        while (true) {
            CgGlyphGenerationResult result = completedResults.poll();
            if (result == null) {
                break;
            }
            if (!result.getSourceFontKey().equals(fontKey)) {
                retained.add(result);
            }
        }
        completedResults.addAll(retained);
    }
}
