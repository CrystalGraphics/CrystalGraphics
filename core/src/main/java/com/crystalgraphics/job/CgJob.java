package com.crystalgraphics.job;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Work that runs on a worker thread and hands its result back to the thread that started it: a simulation too heavy
 * for a tick, run beside the game, its result applied by the game's own thread.
 *
 * <pre>{@code
 * CgCollisionWorld area = CgCollisionWorld.of(level).snapshot(x, y, z, 24);   // this thread: blocks and entities, frozen
 * List<Shard> shards = copyOf(this.shards);
 *
 * CgJob.run(() -> simulate(area, shards))           // a worker
 *      .then(hits -> hits.forEach(this::hurt));     // back on this thread, at its next tick
 * }</pre>
 *
 * A simulation that runs again and again, its state handed round the loop: read fresh on this thread, stepped on a
 * worker, kept when it comes back. One run at a time, at most one a tick.
 *
 * <pre>{@code
 * CgJob.repeat(() -> CgCollisionWorld.of(level).snapshot(arena))   // this thread, before each run
 *      .work(area -> debris.stepped(area))                          // a worker: returns the next state
 *      .then(next -> debris = next)                                 // this thread
 *      .named("debris");
 * }</pre>
 *
 * Work split across the threads, within this tick: returns once every item has run.
 *
 * <pre>{@code
 * CgJob.forEach(shards, s -> s.step(area));
 * }</pre>
 *
 * <ul>
 *   <li><b>A worker sees only what you hand it.</b> Copy what the work needs before {@code run}, or in
 *       {@code repeat}'s read: a snapshot of the world, your own values. Never touch Minecraft from the work: its level,
 *       entities and chunks are its thread's. {@code CgCollisionWorld.of(level)} called on a worker throws.</li>
 *   <li><b>What you hand a worker is its until {@code then} runs.</b> Don't change it meanwhile; return the next state
 *       from the work rather than editing the old one, so this thread keeps drawing the last state it was handed.</li>
 *   <li>{@code then} and {@code failed} run on the thread that started the job, at its next tick after the work is done:
 *       Minecraft's server and client threads drain jobs every tick. Anything else calls {@link #drain()}.</li>
 *   <li>A job ends with its world: leaving it, or the server stopping, cancels every job, and nothing of theirs runs.</li>
 *   <li>A failure is never swallowed: {@code failed} hears it, or it is logged. A repeating job stops at one.</li>
 *   <li>Start a job from the game's thread, never from inside a job's work: split that with {@link #forEach}.</li>
 * </ul>
 *
 * Unity's batched queries and Minecraft's chunk meshing split work the same way: copy on the owner, compute on a
 * worker, apply on the owner.
 */
public final class CgJob<T> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final AtomicIntegerFieldUpdater<CgJob> STATE = AtomicIntegerFieldUpdater.newUpdater(CgJob.class, "state");

    static final int READING = 0, QUEUED = 1, RUNNING = 2, DONE = 3, FAILED = 4, CANCELLED = 5, FINISHED = 6;

    private volatile int state;
    private final int session = CgJobLoop.session();
    private final Supplier<?> read;
    private final Function<Object, ? extends T> work;
    /** The caller's lambda, which names it in the log. */
    private final Object origin;
    private final Runnable runWork = this::runWork;
    private Object input;
    private T result;
    private Throwable failure;
    private Consumer<? super T> then;
    private Consumer<? super Throwable> failed;
    private String name;

    @SuppressWarnings("unchecked")
    private CgJob(Supplier<?> read, Function<?, ? extends T> work, Object origin, int state) {
        this.read = read;
        this.work = (Function<Object, ? extends T>) work;
        this.origin = origin;
        this.state = state;
    }

    /** Runs {@code work} on a worker now; its result goes to {@link #then} on this thread. */
    public static <T> CgJob<T> run(Supplier<? extends T> work) {
        Objects.requireNonNull(work, "work");
        return submitted(new CgJob<>(null, ignored -> work.get(), work, QUEUED));
    }

    /** Runs {@code work} on a worker now; {@link #then(Runnable)} hears when it is done. */
    public static CgJob<Void> run(Runnable work) {
        Objects.requireNonNull(work, "work");
        return submitted(new CgJob<Void>(null, ignored -> {
            work.run();
            return null;
        }, work, QUEUED));
    }

    private static <T> CgJob<T> submitted(CgJob<T> job) {
        job.start();
        CgJobPool.submit(job.runWork);
        return job;
    }

    /**
     * A job run again and again: {@code read} on this thread, then the work on a worker, then {@code then} here, from
     * the next tick until it is cancelled or fails.
     */
    public static <S> Repeat<S> repeat(Supplier<? extends S> read) {
        return new Repeat<>(Objects.requireNonNull(read, "read"));
    }

    /** A repeating job, given its work: {@link #repeat}. */
    public static final class Repeat<S> {
        private final Supplier<? extends S> read;

        private Repeat(Supplier<? extends S> read) {
            this.read = read;
        }

        /** What each run does on a worker with what {@code read} returned; the job starts at this thread's next tick. */
        public <T> CgJob<T> work(Function<? super S, ? extends T> work) {
            CgJob<T> job = new CgJob<>(read, Objects.requireNonNull(work, "work"), read, READING);
            job.start();
            return job;
        }
    }

    private void start() {
        if (isWorkerThread()) {
            throw new IllegalStateException("a job starts on the game's thread, which hands its result back; "
                    + "inside a job's work, split it with CgJob.forEach");
        }
        CgJobLoop.mine().add(this);
    }

    /** What to do with each run's result, on the thread that started the job. */
    public CgJob<T> then(Consumer<? super T> apply) {
        then = Objects.requireNonNull(apply, "apply");
        return this;
    }

    /** What to do once the work is done, on the thread that started the job. */
    public CgJob<T> then(Runnable apply) {
        Objects.requireNonNull(apply, "apply");
        return then(ignored -> apply.run());
    }

    /** Hears a failure of the work, of {@code read} or of {@code then}, on the thread that started the job. */
    public CgJob<T> failed(Consumer<? super Throwable> handler) {
        failed = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** What the log and a failure call it. */
    public CgJob<T> named(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    /** Stops it: nothing of it runs from now, though work already running finishes unseen. Any thread. */
    public void cancel() {
        for (int s = state; s != FINISHED && s != CANCELLED; s = state) {
            if (STATE.compareAndSet(this, s, CANCELLED)) return;
        }
    }

    /** Whether it has ended: applied, failed or cancelled. A repeating job ends only when cancelled or failing. */
    public boolean isDone() {
        int s = state;
        return s == FINISHED || s == CANCELLED;
    }

    // ---- across threads

    /**
     * Runs {@code each} for {@code 0..count-1} on the calling thread and the workers together; returns once all have
     * run. Items run in any order and on any thread, so one never reads what another writes. Allocates nothing.
     *
     * <pre>{@code
     * private final IntConsumer stepEach = i -> shards[i].step(area);   // held: a lambda made per call allocates
     * CgJob.forEach(shards.length, stepEach);
     * }</pre>
     */
    public static void forEach(int count, IntConsumer each) {
        CgWorkers.mine().run(count, Objects.requireNonNull(each, "each"));
    }

    /** {@link #forEach(int, IntConsumer)} over a list's items. */
    public static <E> void forEach(List<? extends E> items, Consumer<? super E> each) {
        CgWorkers.mine().run(items, Objects.requireNonNull(each, "each"));
    }

    /**
     * Applies what this thread's jobs have finished, in the order they were started, and starts repeating jobs' next
     * runs. Minecraft's server and client threads call it every tick; a harness or a test calls it itself.
     */
    public static void drain() {
        CgJobLoop.mine().drain();
    }

    /** Whether this thread is one of the engine's workers, where a job's work or a {@link #forEach} item runs. */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof CgWorkerThread;
    }

    // ---- the job's own steps

    /** On a worker. */
    private void runWork() {
        if (!STATE.compareAndSet(this, QUEUED, RUNNING)) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.ASYNC, "job.work")) {
            result = work.apply(input);
            STATE.compareAndSet(this, RUNNING, DONE);
        } catch (Throwable e) {
            failure = e;
            STATE.compareAndSet(this, RUNNING, FAILED);
        }
    }

    /** On the starting thread, at its drain: whether to keep it. */
    boolean step() {
        if (session != CgJobLoop.session()) cancel();
        switch (state) {
            case READING:
                return readAndSubmit();
            case DONE:
                return apply();
            case FAILED:
                report(failure);
                return false;
            case CANCELLED:
            case FINISHED:
                return false;
            default:
                return true;
        }
    }

    private boolean readAndSubmit() {
        try {
            input = read.get();
        } catch (RuntimeException e) {
            report(e);
            return false;
        }
        if (!STATE.compareAndSet(this, READING, QUEUED)) return false;
        CgJobPool.submit(runWork);
        return true;
    }

    private boolean apply() {
        T r = result;
        result = null;
        input = null;
        if (then != null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.ASYNC, "job.then")) {
                then.accept(r);
            } catch (RuntimeException e) {
                report(e);
                return false;
            }
        }
        if (read != null && STATE.compareAndSet(this, DONE, READING)) return readAndSubmit();
        STATE.compareAndSet(this, DONE, FINISHED);
        return false;
    }

    private void report(Throwable e) {
        state = FINISHED;
        if (failed != null) {
            try {
                failed.accept(e);
                return;
            } catch (RuntimeException inHandler) {
                e.addSuppressed(inHandler);
            }
        }
        LOGGER.error("[cg-job] {} failed", describe(), e);
    }

    private String describe() {
        if (name != null) return name;
        String type = origin.getClass().getName();
        int lambda = type.indexOf("$$Lambda");
        return "a job from " + (lambda < 0 ? type : type.substring(0, lambda));
    }
}
