package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFontKey;

import java.util.ArrayList;
import java.util.List;
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
    /**
     * Queue depth for pending generation jobs.
     *
     * <p>Sized for one frame's worth of a cold CJK screen, not for a trickle. A first frame can ask
     * for every glyph it draws at once — measured at 8668 on text-3d — and with the previous bound of
     * 256 and an {@code AbortPolicy}, ~97% of those submissions were rejected outright and no worker
     * ever ran. Jobs are small (a key, a size, and a shared reference to the font bytes — not a
     * copy), so a deep queue costs little beyond the references it holds.
     *
     * <p>Rejection is still possible and still handled: callers that cannot tolerate a dropped job
     * fall back to generating synchronously.
     */
    private static final int MAX_PENDING_TASKS = 16384;

    /** See {@link #bitmapExecutor} — these exist to beat a queue, not to add throughput. */
    private static final int BITMAP_WORKER_COUNT = 4;

    private final Map<CgGlyphGenerationJob, Boolean> pendingJobs = new ConcurrentHashMap<>();
    private final Map<CgGlyphGenerationJob, Boolean> failedJobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CgGlyphGenerationResult> completedResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<CgWorkerFontContext> workerContexts = new ConcurrentLinkedQueue<>();

    private final ThreadLocal<CgWorkerFontContext> threadLocalContext = ThreadLocal.withInitial(() -> {
        CgWorkerFontContext context = new CgWorkerFontContext();
        workerContexts.add(context);
        return context;
    });

    private final ThreadPoolExecutor msdfExecutor;

    /**
     * Dedicated pool for bitmap jobs, kept separate from {@link #msdfExecutor} on purpose.
     *
     * <p>The two job kinds have completely different latency requirements and completely different
     * costs. A bitmap glyph takes ~0.08 ms and is what the user actually sees *now* — it is the
     * fallback rendered while the real MSDF is still being built. An MSDF glyph takes ~13 ms and
     * nobody is waiting on any individual one.
     *
     * <p>Sharing one FIFO queue made the cheap, latency-critical work queue behind the expensive,
     * latency-indifferent work: with ~2000 MSDF jobs at 13 ms across 4 workers, a bitmap submitted
     * on frame 1 waited about 6.5 seconds. Measured exactly that — text converged over ~9 s instead
     * of the ~0.2 s the bitmaps themselves need.
     *
     * <p>Four threads: 8668 bitmaps at 0.08 ms is ~350 ms of work at two, which was itself a floor
     * under how fast a cold screen could fill in. They run at minimum priority, so on a machine with
     * fewer cores they yield to rendering rather than competing with it.
     */
    private final ThreadPoolExecutor bitmapExecutor;

    private volatile boolean shutdown;

    CgGlyphGenerationExecutor() {
        final AtomicInteger threadId = new AtomicInteger(1);
        this.msdfExecutor = new ThreadPoolExecutor(
                DEFAULT_WORKER_COUNT,
                DEFAULT_WORKER_COUNT,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_TASKS),
                runnable -> {
                    Thread thread = new Thread(runnable, "crystalgraphics-glyph-worker-" + threadId.getAndIncrement());
                    thread.setDaemon(true);
                    // Below normal priority: this work is never on the critical path — a glyph
                    // that is not ready renders as a bitmap fallback — so it must never
                    // preempt the render thread.
                    //
                    // It measurably did. During the first frame the render thread rasterizes
                    // the entire visible text set (~4800 glyphs) while these workers saturate
                    // every core generating MSDFs. A single FreeType glyph load, averaging
                    // 41us, was measured at 9.4ms in one run and 51ms in another — the same
                    // operation, an order of magnitude apart, which is preemption rather than
                    // work. GC was ruled out (5 collections, 6.5ms worst pause, none during
                    // frame 1) and so was first-load table parsing (0.04ms).
                    //
                    // Priority is a hint the OS may ignore, so this narrows the window rather
                    // than closing it. It cannot make anything slower: these threads are
                    // throughput work with no deadline.
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.msdfExecutor.allowCoreThreadTimeOut(true);

        final AtomicInteger bitmapThreadId = new AtomicInteger(1);
        this.bitmapExecutor = new ThreadPoolExecutor(
                BITMAP_WORKER_COUNT,
                BITMAP_WORKER_COUNT,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_TASKS),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            "crystalgraphics-bitmap-worker-" + bitmapThreadId.getAndIncrement());
                    thread.setDaemon(true);
                    // Same reasoning as the MSDF workers: never preempt rendering.
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.bitmapExecutor.allowCoreThreadTimeOut(true);
    }

    boolean submit(final CgGlyphGenerationJob job) {
        if (shutdown || failedJobs.containsKey(job)) return false;
        if (pendingJobs.putIfAbsent(job, Boolean.TRUE) != null) return true;
        
        ThreadPoolExecutor target = job.isDistanceField() ? msdfExecutor : bitmapExecutor;
        try {
            target.execute(() -> {
                CgGlyphGenerationResult result = null;
                try {
                    result = generate(job);
                    if (result != null) {
                        // Un-marked at POLL time, not here -- see completedJobs.
                        completedJobs.put(result, job);
                        completedResults.add(result);
                    } else {
                        failedJobs.put(job, Boolean.TRUE);
                    }
                } catch (Throwable throwable) {
                    failedJobs.put(job, Boolean.TRUE);
                    LOGGER.log(Level.WARNING, "Glyph generation failed for " + job, throwable);
                } finally {
                    // A generated-but-unpolled job stays pending; only failures release here.
                    if (result == null) pendingJobs.remove(job);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            pendingJobs.remove(job);
            return false;
        }
    }

    /**
     * Results generated but not yet handed to the render thread, mapped back to the job that
     * produced them so {@link #pollCompleted} can release the dedup entry at the right moment.
     *
     * <h4>Why the release moved out of the worker</h4>
     * <p>{@code pendingJobs} is the "somebody is already making this glyph" guard, and it used to
     * be cleared the instant generation <em>finished</em>. But a finished glyph is not a usable
     * glyph: it sits in {@link #completedResults} until {@code drainCompletedGlyphs} commits it
     * into the atlas, and that drain is deliberately budget-limited. Throughout that window the
     * atlas still misses, the dedup guard is already released, and so the very next frame submits
     * the same glyph again — and the frame after that, and so on until the commit finally lands.
     *
     * <p>Measured on {@code text-3d}: <strong>~24,800 bitmap rasterizations for ~7,000 distinct
     * glyphs</strong>, a 3.5x multiplier. Not a round number precisely because it is proportional
     * to how long results wait, not to anything structural. Every duplicate is a full FreeType
     * rasterization on a worker thread, and {@code commitGeneratedGlyph} throws the loser away.
     *
     * <p>Holding the entry until poll closes the window to the microseconds between polling and
     * committing inside one drain iteration. It also makes {@link #isPending} mean what its callers
     * already assume — "a usable result is coming" — rather than "somebody is mid-rasterize".
     */
    private final Map<CgGlyphGenerationResult, CgGlyphGenerationJob> completedJobs = new ConcurrentHashMap<>();

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

    /**
     * Whether this job previously failed and will never be retried.
     *
     * <p>{@link #submit} returns {@code false} for two very different reasons — the queue is full
     * right now, or this job failed before and is permanently refused. A caller that skips drawing a
     * glyph until its async result lands must tell them apart: a full queue means try again next
     * frame, but a permanent failure means the glyph would never appear at all, and the caller has
     * to fall back to generating it synchronously instead.
     */
    boolean hasFailed(CgGlyphGenerationJob job) {
        return failedJobs.containsKey(job);
    }

    /**
     * How many finished results are waiting to be committed.
     *
     * <p>Used to size the per-frame commit budget against the actual backlog: a cold screen finishes
     * thousands of glyphs at once, and draining them at a budget tuned for steady state is what
     * makes text take seconds to fill in. {@code size()} on a {@link ConcurrentLinkedQueue} is O(n)
     * and only approximate under concurrent writes, which is fine here — this feeds a budget
     * heuristic, not a decision that has to be exact.
     */
    int completedBacklog() {
        return completedResults.size();
    }

    CgGlyphGenerationResult pollCompleted() {
        CgGlyphGenerationResult result = completedResults.poll();
        if (result != null) {
            // Release the dedup entry here rather than when generation finished -- see
            // completedJobs. The caller commits this within the same drain iteration, so the
            // window in which a re-submission could slip through is microseconds instead of the
            // many frames a budget-limited drain can take.
            CgGlyphGenerationJob job = completedJobs.remove(result);
            if (job != null) pendingJobs.remove(job);
        }
        return result;
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
            if (!hasPendingWork()) return true;
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
        if (shutdown) return;
        
        shutdown = true;
        msdfExecutor.shutdownNow();
        bitmapExecutor.shutdownNow();
        try {
            msdfExecutor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (CgWorkerFontContext context : workerContexts) context.close();
        
        workerContexts.clear();
        pendingJobs.clear();
        // Results generated but never polled would otherwise keep their jobs pending forever.
        completedJobs.clear();
        failedJobs.clear();
        completedResults.clear();
    }

    private CgGlyphGenerationResult generate(CgGlyphGenerationJob job) {
        CgWorkerFontContext context = threadLocalContext.get();
        return job.isDistanceField() ? context.generateMsdf(job) : context.generateBitmap(job);
    }

    private void clearMatchingJobs(Map<CgGlyphGenerationJob, Boolean> jobs, CgFontKey fontKey) {
        List<CgGlyphGenerationJob> snapshot = new ArrayList<>(jobs.keySet());
        for (CgGlyphGenerationJob job : snapshot)
            if (job.getSourceFontKey().equals(fontKey)) jobs.remove(job);
        
    }

    private void clearCompletedResults(CgFontKey fontKey) {
        ConcurrentLinkedQueue<CgGlyphGenerationResult> retained = new ConcurrentLinkedQueue<>();
        while (true) {
            CgGlyphGenerationResult result = completedResults.poll();
            if (result == null) break;
            if (!result.getSourceFontKey().equals(fontKey)) retained.add(result);
        }
        completedResults.addAll(retained);
    }
}
