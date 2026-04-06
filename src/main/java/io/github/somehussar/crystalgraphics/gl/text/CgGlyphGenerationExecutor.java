package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;

import java.util.Iterator;
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

final class CgGlyphGenerationExecutor {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphGenerationExecutor.class.getName());
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
