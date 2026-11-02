package com.crystalgraphics.loading.task;

import com.crystalgraphics.loading.CgLoadingProcess;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.progress.CgProgressState;

/**
 * One piece of loading, as the loading screen tracks it: who owns it, what state it is in, how far along it is. Made by a
 * {@link CgLoadPlan} (its tasks, and work it adopts) and by the screen for each reason a {@link CgLoadingProcess} gives.
 *
 * <pre>{@code
 * CgLoadTask icons = plan.task("mymod:icons").work(this::decodeIcons).start();
 * icons.done();                                // any thread, any frame
 * for (CgLoadTask t : CgLoadTracker.running()) log(t.id() + " " + t.fraction(now));
 * }</pre>
 *
 * <ul>
 *   <li>Its id is {@code <owner>:<name>}: the owner (a kit's or a mod's namespace: {@code spzero_kit:compile}) is what
 *       the screen and the log group by, and the id is what the bar's history is kept under, so keep it the same from
 *       run to run.</li>
 *   <li>What ran in parallel and what in sequence is in the record: {@link #after()} says what it waited for, and two
 *       tasks whose {@link #startedAt()}..{@link #endedAt()} spans overlap ran together.</li>
 *   <li>{@link #progress(float)} and {@link #detail(String)} are the task's own report, from any thread; work run as a job
 *       reports through its context instead ({@code ctx.progress()}).</li>
 * </ul>
 */
public final class CgLoadTask {

    /** Where a task is. */
    public enum State {
        /** Waiting for the tasks it comes after. */
        WAITING,
        /** Its work running on a worker, or tracked work not settled. */
        RUNNING,
        /** Its render-thread part running in slices. */
        RENDER,
        DONE,
        FAILED;

        /** Whether it has ended, either way. */
        public boolean ended() {
            return this == DONE || this == FAILED;
        }
    }

    private final String id;
    private final List<String> after;
    private volatile State state = State.WAITING;
    private volatile long startedAt, endedAt;
    private volatile float progress = Float.NaN;
    @Nullable private volatile String detail;
    @Nullable volatile CgJob<?> job;

    CgLoadTask(String id, List<String> after) {
        this.id = id;
        this.after = after;
    }

    /** {@code <owner>:<name>}. */
    public String id() {
        return id;
    }

    /** The tasks it waits for: what makes it sequential after them. Empty for one that starts at once. */
    public List<String> after() {
        return after;
    }

    /** When it started, in {@code System.nanoTime()}; 0 while waiting. Two tasks whose spans overlap ran in parallel. */
    public long startedAt() {
        return startedAt;
    }

    /** When it ended, in {@code System.nanoTime()}; 0 while it runs. */
    public long endedAt() {
        return endedAt;
    }

    /** What owns it: the id's namespace, or the whole id without one. */
    public String owner() {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(0, colon);
    }

    public State state() {
        return state;
    }

    /** Whether it ended, done or failed. */
    public boolean done() {
        return state.ended();
    }

    /** Its own report of how far along it is, 0 to 1, from any thread. */
    public void progress(float fraction) {
        progress = fraction;
    }

    /** What it is on now ("Goku's mesh"), shown beside its name; from any thread. */
    public void detail(@Nullable String now) {
        detail = now;
    }

    @Nullable
    public String detail() {
        CgJob<?> j = job;
        CgProgressState p = j != null ? j.progress() : null;
        return p != null && p.detail() != null ? p.detail() : detail;
    }

    /** How far along it says it is, 0 to 1, or NaN when it says nothing. */
    public float reported() {
        if (state.ended()) return 1f;
        CgJob<?> j = job;
        CgProgressState p = j != null ? j.progress() : null;
        if (p != null && !p.isIndeterminate()) return Math.max(0f, Math.min(1f, p.fraction()));
        float own = progress;
        return Float.isNaN(own) ? Float.NaN : Math.max(0f, Math.min(1f, own));
    }

    /** Milliseconds it has run, or ran: 0 while waiting. */
    public long elapsedMillis(long nowNanos) {
        if (startedAt == 0L) return 0L;
        return ((endedAt != 0L ? endedAt : nowNanos) - startedAt) / 1_000_000L;
    }

    void started(long now) {
        if (startedAt == 0L) startedAt = now;
        state = State.RUNNING;
    }

    /** A process's reason seen again after it ended: running again, its span stretched over the gap. */
    void reopen() {
        endedAt = 0L;
        state = State.RUNNING;
    }

    void render() {
        state = State.RENDER;
    }

    void ended(boolean ok, long now) {
        if (state.ended()) return;
        if (startedAt == 0L) startedAt = now;
        endedAt = now;
        state = ok ? State.DONE : State.FAILED;
    }

    @Override
    public String toString() {
        return id + " " + state;
    }
}
