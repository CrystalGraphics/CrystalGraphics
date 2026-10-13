package com.crystalgraphics.job.progress;

import javax.annotation.Nullable;
import java.util.function.LongSupplier;

/**
 * A {@link CgProgress} that keeps the latest {@link CgProgressState} for readers on any thread, with an ETA. Every
 * job has one; make one for work that reports progress outside a job.
 *
 * <pre>{@code
 * CgProgressTracker progress = new CgProgressTracker(System::currentTimeMillis);
 * download(url, progress);                 // reports on its own thread
 * CgProgressState now = progress.state();  // read from the frame
 * }</pre>
 *
 * <ul>
 *   <li>One writer: report from one thread at a time. Readers are free.</li>
 *   <li>The rate is a ten-second window ({@link CgRateEstimator}); the ETA changes at most once a second, wget's
 *       rule, so it does not jitter. wget is GPL: its shape only, none of its code.</li>
 * </ul>
 */
public final class CgProgressTracker implements CgProgress {

    private static final long ESTIMATE_REFRESH_MILLIS = 1000L;

    private final LongSupplier clockMillis;
    @Nullable
    private volatile CgProgressState state;
    @Nullable
    private CgRateEstimator rate;
    private long estimateRefreshedAtMillis;
    private long secondsRemaining = -1L;

    public CgProgressTracker(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /** The latest reading, or null before {@link #begin}. */
    @Nullable
    public CgProgressState state() {
        return state;
    }

    @Override
    public void begin(String what, long total, Unit unit) {
        long now = clockMillis.getAsLong();
        if (rate == null) {
            rate = new CgRateEstimator(now);
            // the first estimate is due a full interval from now: one off a few milliseconds of data is wrong by far
            estimateRefreshedAtMillis = now;
        }
        state = new CgProgressState(what == null ? "" : what, "", 0L, total, now, now,
                unit == null ? Unit.ITEMS : unit, secondsRemaining);
    }

    @Override
    public void advance(long done) {
        CgProgressState current = state;
        if (current == null) return;
        long now = clockMillis.getAsLong();
        rate.increment(done - current.done(), now);
        if (now - estimateRefreshedAtMillis >= ESTIMATE_REFRESH_MILLIS) {
            estimateRefreshedAtMillis = now;
            long perSecond = rate.countPerSecond(now);
            long left = current.total() - done;
            secondsRemaining = perSecond > 0 && left > 0 ? (left + perSecond - 1) / perSecond : -1L;
        }
        state = new CgProgressState(current.what(), current.detail(), done, current.total(), current.begunAtMillis(),
                now, current.unit(), secondsRemaining);
    }

    @Override
    public void detail(String item) {
        CgProgressState current = state;
        if (current == null) return;
        state = new CgProgressState(current.what(), item == null ? "" : item, current.done(), current.total(),
                current.begunAtMillis(), current.updatedAtMillis(), current.unit(), current.secondsRemaining());
    }
}
