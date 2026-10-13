package com.crystalgraphics.job;

import com.crystalgraphics.job.progress.CgProgress;
import com.crystalgraphics.job.progress.CgProgressState;
import com.crystalgraphics.job.progress.CgProgressTracker;

import javax.annotation.Nullable;
import java.util.function.LongSupplier;

/**
 * Handed to a job's work while it runs: whether anyone still wants the answer, and where to report progress.
 *
 * <pre>{@code
 * CgJob.run(ctx -> {
 *     for (Path file : files) {
 *         ctx.throwIfCancelled();          // cancelled or superseded: stop here, nothing is reported
 *         index(file);
 *     }
 *     return index;
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Cancelling is cooperative: poll at loop heads and between phases. Work that never polls still has its result
 *       dropped; it only runs to the end for nothing.</li>
 *   <li>Once cancelled it stays cancelled.</li>
 *   <li>{@link #progress()} is never null; reporting is the caller's choice ({@link CgProgress}).</li>
 * </ul>
 */
public final class CgJobContext {

    private final LongSupplier clockMillis;
    private volatile boolean cancelled;
    @Nullable
    private volatile CgProgressTracker progress;

    CgJobContext(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** Whether the answer is no longer wanted. Poll it; never cache it. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Throws {@link CancelledException} once cancelled: control flow, not a failure, so nothing is logged. */
    public void throwIfCancelled() {
        if (cancelled) throw new CancelledException();
    }

    /** Where the work reports how far along it is. */
    public CgProgress progress() {
        CgProgressTracker p = progress;
        if (p == null) progress = p = new CgProgressTracker(clockMillis);   // the work's thread alone writes it
        return p;
    }

    void cancel() {
        cancelled = true;
    }

    @Nullable
    CgProgressState progressState() {
        CgProgressTracker p = progress;
        return p == null ? null : p.state();
    }

    /** What {@link #throwIfCancelled()} throws. No stack trace: it is expected. */
    public static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("job cancelled", null, false, false);
        }
    }
}
