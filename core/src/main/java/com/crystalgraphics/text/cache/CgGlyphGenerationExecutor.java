package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Glyph generation on the engine's job workers, owned by {@link CgFontRegistry}.
 *
 * <p>Runs {@link CgGlyphGenerationJob}s through {@link CgJob#executor} with a per-thread
 * {@link CgWorkerFontContext}, and keeps the finished {@link CgGlyphGenerationResult}s for the render thread, which
 * commits them each frame within a budget. Glyphs are asked for from any thread a text is recorded on and committed on
 * the render thread whatever asked, so nothing here reports through a job loop: the registry's frame is the drain.</p>
 *
 * <table>
 *   <caption>Lanes</caption>
 *   <tr><th>Job</th><th>Lane</th></tr>
 *   <tr><td>a drawn bitmap glyph</td><td>{@code INTERACTIVE}: ~0.1 ms, and the screen waits on it</td></tr>
 *   <tr><td>a drawn text-shadow cell</td><td>{@code LATENCY}: visible, a frame late is fine</td></tr>
 *   <tr><td>a drawn distance field</td><td>{@code BACKGROUND}: 13-50 ms, and its bitmap stand-in already shows</td></tr>
 *   <tr><td>anything warmed ({@link CgFontWarmer})</td><td>{@code BACKGROUND}</td></tr>
 * </table>
 *
 * <p>Distance fields are background so they can never hold every worker: background never takes the last one, which
 * keeps a bitmap from queueing behind them. One FIFO queue for both once left a frame-one bitmap waiting 6.5 s behind
 * ~2000 distance fields.</p>
 */
final class CgGlyphGenerationExecutor {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphGenerationExecutor.class.getName());

    /**
     * Glyph tasks queued and not yet started, past which a submission is refused.
     *
     * <p>Sized for one frame's worth of a cold CJK screen, not for a trickle. A first frame can ask
     * for every glyph it draws at once — measured at 8668 on text-3d — and with an earlier bound of
     * 256, ~97% of those submissions were rejected outright and no worker ever ran. Jobs are small
     * (a key, a size, and a shared reference to the font bytes — not a copy).
     *
     * <p>Rejection is still possible and still handled: callers that cannot tolerate a dropped job
     * fall back to generating synchronously.
     */
    private static final int MAX_PENDING_TASKS = 16384;

    /**
     * {@code -Dcrystalgraphics.text.deterministicGlyphs=true}: every job runs on the thread that asks for it, in the
     * order asked, so the atlas fills the same way every run. For pixel comparisons of text; never for play.
     */
    static final boolean DETERMINISTIC = CgVar.bool("crystalgraphics.text.deterministicGlyphs", false,
            CgVar.DEV | CgVar.RESTART, "Every glyph job runs on the thread that asks for it, in the order asked, so the atlas"
                    + " fills the same way every run: for pixel comparisons of text, never for play.").get();

    /** Microseconds from submission to a worker starting the job, by kind and by who waits on it. */
    private static final String WAIT_BITMAP_DRAWN = "glyph.queueWaitUs.bitmap.drawn";
    private static final String WAIT_BITMAP_WARM = "glyph.queueWaitUs.bitmap.warm";
    private static final String WAIT_MSDF_DRAWN = "glyph.queueWaitUs.msdf.drawn";
    private static final String WAIT_MSDF_WARM = "glyph.queueWaitUs.msdf.warm";

    private final Map<CgGlyphGenerationJob, Boolean> pendingJobs = new ConcurrentHashMap<>();

    /** Warm jobs still queued, with the claim a promoted copy shares. @see #promote */
    private final Map<CgGlyphGenerationJob, AtomicBoolean> queuedWarm = new ConcurrentHashMap<>();

    private final Map<CgGlyphGenerationJob, Boolean> failedJobs = new ConcurrentHashMap<>();

    /**
     * Finished results waiting for the render thread, drawn and warm apart: the commit is budgeted per frame, and in
     * one queue a warm's results filled that budget while a label's glyph sat behind them for frames.
     */
    private final ConcurrentLinkedQueue<CgGlyphGenerationResult> drawnResults = new ConcurrentLinkedQueue<>();

    private final ConcurrentLinkedQueue<CgGlyphGenerationResult> warmResults = new ConcurrentLinkedQueue<>();

    /** Warm results not yet committed, by job, so a draw asking for one can move it to {@link #drawnResults}. */
    private final Map<CgGlyphGenerationJob, CgGlyphGenerationResult> completedWarm = new ConcurrentHashMap<>();

    private final ConcurrentLinkedQueue<CgWorkerFontContext> workerContexts = new ConcurrentLinkedQueue<>();

    /** Each worker's FreeType and msdfgen state, for this executor: the one replacing it after a shutdown starts fresh. */
    private final ThreadLocal<CgWorkerFontContext> threadLocalContext = ThreadLocal.withInitial(() -> {
        CgWorkerFontContext context = new CgWorkerFontContext();
        workerContexts.add(context);
        return context;
    });

    /** Task copies queued and not yet started: all of them, and the distance fields among them. */
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicInteger queuedMsdf = new AtomicInteger();

    /**
     * Tasks inside {@link GlyphTask#run}. The workers are the engine's and outlive this executor, so the contexts are
     * closed by whichever of {@link #shutdown} and the last of these finishes second. @see #closeContextsIfIdle
     */
    private final AtomicInteger running = new AtomicInteger();

    private final AtomicBoolean contextsClosed = new AtomicBoolean();

    private volatile boolean shutdown;

    /**
     * A text-shadow cell nobody has asked for in this many frames is not built. @see #lastWanted
     */
    private static final long SHADOW_CELL_ABANDONED_FRAMES = 2;

    /** The render thread's frame, as {@link #noteFrame} last saw it. */
    private volatile long frame;

    /**
     * The frame each queued text-shadow cell was last asked for.
     *
     * <p>A slider moves a shadow's blur, growth or size every frame, and every step is a new cell. Built in
     * the order asked, the workers spend the whole drag on looks already slid past while the current one
     * waits behind them: measured on the text lab, dragging the stroke queued 115 cells and left the shadow
     * missing for 20 frames. A draw still waiting on a cell asks for it again every frame (the degraded
     * count makes it repaint), so a cell unasked for two frames is one nobody will draw, and a worker that
     * reaches it drops it instead.</p>
     */
    private final Map<CgGlyphGenerationJob, Long> lastWanted = new ConcurrentHashMap<>();

    /** Called once a frame by the registry, before that frame's glyph requests. */
    void noteFrame(long frame) {
        this.frame = frame;
    }

    /**
     * Asks a worker for {@code job}.
     *
     * <pre>{@code
     * executor.submit(job, true);    // a draw is waiting on it: in its lane, ahead of every warm job
     * executor.submit(job, false);   // speculation: background, behind everything a draw asked for
     * }</pre>
     *
     * <p>A drawn submission of a job already queued as a warm one promotes it: a copy is queued in the drawn lane and
     * whichever a worker reaches first runs it, once. One already running or generated is left alone.</p>
     *
     * @param drawn whether something on screen waits for this glyph
     * @return false when the job has failed before or the queue is full
     */
    boolean submit(final CgGlyphGenerationJob job, boolean drawn) {
        if (shutdown || failedJobs.containsKey(job)) return false;
        if (job.isShadowCell()) lastWanted.put(job, frame);
        if (pendingJobs.putIfAbsent(job, Boolean.TRUE) != null) {
            if (drawn) {
                CgTrace.add(CgChannels.TEXT, "glyph.drawnAlreadyPending", 1);
                promote(job);
                hurry(job);
            }
            return true;
        }
        if (queuedTasks.get() >= MAX_PENDING_TASKS) {
            pendingJobs.remove(job);
            lastWanted.remove(job);
            return false;
        }
        AtomicBoolean claim = new AtomicBoolean();
        if (!drawn) queuedWarm.put(job, claim);
        execute(new GlyphTask(job, drawn, claim));
        return true;
    }

    /** Which lane a job runs in: the table in the class javadoc. */
    private static CgJobLane laneOf(CgGlyphGenerationJob job, boolean drawn) {
        if (!drawn || job.isDistanceField()) return CgJobLane.BACKGROUND;
        return job.isShadowCell() ? CgJobLane.LATENCY : CgJobLane.INTERACTIVE;
    }

    /** Queues a drawn copy of a still-queued warm job; whichever copy a worker reaches first runs it. */
    private void promote(CgGlyphGenerationJob job) {
        // A distance field's drawn lane is its warm one, so a copy would only queue behind the original.
        if (laneOf(job, true) == CgJobLane.BACKGROUND) return;
        AtomicBoolean claim = queuedWarm.remove(job);
        if (claim == null || claim.get()) return;
        execute(new GlyphTask(job, true, claim));
        CgTrace.add(CgChannels.TEXT, "glyph.promoted", 1);
    }

    /** Moves a finished, uncommitted warm result to the front of the commit. */
    private void hurry(CgGlyphGenerationJob job) {
        CgGlyphGenerationResult result = completedWarm.remove(job);
        if (result != null && warmResults.remove(result)) {
            drawnResults.add(result);
            CgTrace.add(CgChannels.TEXT, "glyph.hurried", 1);
        }
    }

    private void execute(GlyphTask task) {
        queuedTasks.incrementAndGet();
        if (task.job.isDistanceField()) queuedMsdf.incrementAndGet();
        if (DETERMINISTIC) task.run();
        else CgJob.executor(laneOf(task.job, task.drawn)).execute(task);
    }

    private void run(CgGlyphGenerationJob job, boolean drawn) {
        if (job.isShadowCell() && isAbandoned(job)) {
            lastWanted.remove(job);
            pendingJobs.remove(job);
            return;
        }
        CgGlyphGenerationResult result = null;
        try {
            result = generate(job);
            if (result != null) {
                // Un-marked at POLL time, not here -- see completedJobs.
                completedJobs.put(result, job);
                if (drawn) {
                    drawnResults.add(result);
                } else {
                    // Queued before it is findable: whatever hurry() finds is in the queue, or already polled.
                    warmResults.add(result);
                    completedWarm.put(job, result);
                }
            } else {
                failedJobs.put(job, Boolean.TRUE);
            }
        } catch (Throwable throwable) {
            failedJobs.put(job, Boolean.TRUE);
            LOGGER.log(Level.WARNING, "Glyph generation failed for " + job, throwable);
        } finally {
            // A generated-but-unpolled job stays pending; only failures release here.
            if (result == null) {
                pendingJobs.remove(job);
                lastWanted.remove(job);
            }
        }
    }

    /**
     * One queued glyph job. Two tasks may share a job and its claim, when a draw promoted a warm one; the first to
     * claim it runs it and the other does nothing.
     */
    private final class GlyphTask implements Runnable {

        private final CgGlyphGenerationJob job;
        private final boolean drawn;
        private final AtomicBoolean claim;
        private final long queuedNanos = System.nanoTime();

        GlyphTask(CgGlyphGenerationJob job, boolean drawn, AtomicBoolean claim) {
            this.job = job;
            this.drawn = drawn;
            this.claim = claim;
        }

        @Override
        public void run() {
            queuedTasks.decrementAndGet();
            if (job.isDistanceField()) queuedMsdf.decrementAndGet();
            // Counted before the check, so shutdown() sees every task that may still reach a context.
            running.incrementAndGet();
            try {
                if (shutdown || !claim.compareAndSet(false, true)) return;
                queuedWarm.remove(job, claim);
                String wait = job.isDistanceField()
                        ? (drawn ? WAIT_MSDF_DRAWN : WAIT_MSDF_WARM)
                        : (drawn ? WAIT_BITMAP_DRAWN : WAIT_BITMAP_WARM);
                CgTrace.counter(CgChannels.ASYNC, wait, (System.nanoTime() - queuedNanos) / 1_000L);
                CgGlyphGenerationExecutor.this.run(job, drawn);
            } finally {
                running.decrementAndGet();
                if (shutdown) closeContextsIfIdle();
            }
        }
    }

    private boolean isAbandoned(CgGlyphGenerationJob job) {
        Long wanted = lastWanted.get(job);
        return wanted != null && frame - wanted > SHADOW_CELL_ABANDONED_FRAMES;
    }

    /**
     * Results generated but not yet handed to the render thread, mapped back to the job that
     * produced them so {@link #pollCompleted} can release the dedup entry at the right moment.
     *
     * <h4>Why the release moved out of the worker</h4>
     * <p>{@code pendingJobs} is the "somebody is already making this glyph" guard, and it used to
     * be cleared the instant generation <em>finished</em>. But a finished glyph is not a usable
     * glyph: it sits in {@link #drawnResults} or {@link #warmResults} until {@code drainCompletedGlyphs} commits it
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
        return drawnResults.size() + warmResults.size();
    }

    /** The next finished result to commit: everything a draw waits on first. */
    CgGlyphGenerationResult pollCompleted() {
        CgGlyphGenerationResult result = drawnResults.poll();
        if (result != null) CgTrace.add(CgChannels.TEXT, "asyncCommit.drawn", 1);
        else result = warmResults.poll();
        if (result != null) {
            // Release the dedup entry here rather than when generation finished -- see
            // completedJobs. The caller commits this within the same drain iteration, so the
            // window in which a re-submission could slip through is microseconds instead of the
            // many frames a budget-limited drain can take.
            CgGlyphGenerationJob job = completedJobs.remove(result);
            if (job != null) {
                pendingJobs.remove(job);
                lastWanted.remove(job);
                completedWarm.remove(job, result);
            }
        }
        return result;
    }

    boolean hasPendingWork() {
        return !pendingJobs.isEmpty() || !drawnResults.isEmpty() || !warmResults.isEmpty();
    }

    int getPendingJobCount() {
        return pendingJobs.size();
    }

    /**
     * Distance fields queued and not yet started, which is not what {@link #getPendingJobCount} counts.
     *
     * <p>{@code pendingJobs} spans both kinds, and a cold page puts thousands of bitmap fallbacks in it
     * -- so anything throttled on that count is really throttled on bitmap work in another lane. Gated
     * on it, the ASCII warm did not run at all until a cold page's bitmap fallbacks had drained.</p>
     */
    int msdfQueueDepth() {
        return queuedMsdf.get();
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

    /**
     * Stops taking work and frees every worker's font state, without waiting. Irreversible: the registry replaces this
     * executor.
     *
     * <p>The workers are the engine's, so nothing is shut down: queued tasks return at once when they are reached. A
     * context is never closed under a task using it: with one still running, the last to finish closes them.</p>
     */
    void shutdown() {
        if (shutdown) return;

        shutdown = true;
        closeContextsIfIdle();
        pendingJobs.clear();
        lastWanted.clear();
        queuedWarm.clear();
        // Results generated but never polled would otherwise keep their jobs pending forever.
        completedJobs.clear();
        failedJobs.clear();
        drawnResults.clear();
        warmResults.clear();
        completedWarm.clear();
    }

    /**
     * Closes every worker's context once shut down with no task running; exactly once. A task counts itself running
     * before it reads {@link #shutdown} and touches no context after reading it set, so none is in use here.
     */
    private void closeContextsIfIdle() {
        if (running.get() > 0 || !contextsClosed.compareAndSet(false, true)) return;
        for (CgWorkerFontContext context : workerContexts) context.close();
        workerContexts.clear();
    }

    private CgGlyphGenerationResult generate(CgGlyphGenerationJob job) {
        CgWorkerFontContext context = threadLocalContext.get();
        if (job.isDistanceField()) return context.generateMsdf(job);
        return job.isShadowCell() ? context.generateShadowCell(job) : context.generateBitmap(job);
    }

    private void clearMatchingJobs(Map<CgGlyphGenerationJob, Boolean> jobs, CgFontKey fontKey) {
        List<CgGlyphGenerationJob> snapshot = new ArrayList<>(jobs.keySet());
        for (CgGlyphGenerationJob job : snapshot)
            if (job.getSourceFontKey().equals(fontKey)) jobs.remove(job);

    }

    private void clearCompletedResults(CgFontKey fontKey) {
        clearCompletedResults(drawnResults, fontKey);
        clearCompletedResults(warmResults, fontKey);
        completedWarm.values().removeIf(result -> result.getSourceFontKey().equals(fontKey));
    }

    private static void clearCompletedResults(ConcurrentLinkedQueue<CgGlyphGenerationResult> results,
                                              CgFontKey fontKey) {
        ConcurrentLinkedQueue<CgGlyphGenerationResult> retained = new ConcurrentLinkedQueue<>();
        while (true) {
            CgGlyphGenerationResult result = results.poll();
            if (result == null) break;
            if (!result.getSourceFontKey().equals(fontKey)) retained.add(result);
        }
        results.addAll(retained);
    }
}
