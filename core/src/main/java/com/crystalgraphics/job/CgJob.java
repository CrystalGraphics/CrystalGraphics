package com.crystalgraphics.job;

import com.crystalgraphics.job.progress.CgProgressState;
import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
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
 * A job is a {@link CgReply}: it composes with other answers, and hears failure and cancellation.
 *
 * <pre>{@code
 * CgJob.run(() -> load(file))
 *      .then(editor::show)
 *      .onError(e -> status.show(e.detail()))       // failed, or cancelled (e.is(CgReplyError.CANCELLED))
 *      .always(spinner::hide);
 * }</pre>
 *
 * Long work takes a {@link CgJobContext}, to stop when nobody wants it and to report progress. Work that keeps being
 * asked for again (a reparse per keystroke) is keyed and debounced: the last one asked for wins.
 *
 * <pre>{@code
 * CgJob.keyed(document, "reparse")                  // a newer reparse of this document replaces this one
 *      .debounce(300)                               // starts 300 ms after the last keystroke
 *      .lane(CgJobLane.LATENCY)
 *      .run(ctx -> parse(text, ctx))                // ctx.throwIfCancelled() inside
 *      .then(tree -> document.setTree(tree));
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
 *   <li>{@code then}, {@code onError} and {@code always} run on the thread that started the job, at its loop's next
 *       drain after the work is done: Minecraft's server and client threads drain every tick. Anything else calls
 *       {@link #drain()}, or drains its own {@link CgJobLoop}.</li>
 *   <li>A job ends with its world: leaving it, or the server stopping, cancels every job a game thread started.</li>
 *   <li>A failure is never swallowed: {@code onError} hears it, or it is logged. A repeating job stops at one.</li>
 *   <li>A superseded keyed job never settles: wait on the newer one.</li>
 *   <li>Start a job from the thread it reports to, never from inside a job's work: split that with {@link #forEach}.</li>
 * </ul>
 *
 * Unity's batched queries and Minecraft's chunk meshing split work the same way: copy on the owner, compute on a
 * worker, apply on the owner.
 */
public final class CgJob<T> extends CgJobBase implements CgReply<T> {

    private static final int SUPPLIER = 0, RUNNABLE = 1, WORK = 2;

    /** Work that takes its job's context, and may throw. */
    @FunctionalInterface
    public interface Work<T> {
        T run(CgJobContext context) throws Exception;
    }

    private final Object work;
    private final int kind;
    @Nullable
    private final CgJobContext context;
    private final CgPendingReply<T> reply = new CgPendingReply<>(this::markCancelled);
    private T result;

    private CgJob(CgJobLoop loop, Object work, int kind, CgJobLane lane) {
        super(loop, work, lane, WAITING);
        this.work = work;
        this.kind = kind;
        this.context = kind == WORK ? new CgJobContext(loop.clockMillis) : null;
    }

    // ---- starting

    /** Runs {@code work} on a worker now; its result goes to {@link #then} on this thread. */
    public static <T> CgJob<T> run(Supplier<? extends T> work) {
        return started(new CgJob<T>(CgJobLoop.current(), Objects.requireNonNull(work, "work"), SUPPLIER, CgJobLane.LATENCY), 0);
    }

    /** Runs {@code work} on a worker now; {@link #then(Runnable)} hears when it is done. */
    public static CgJob<Void> run(Runnable work) {
        return started(new CgJob<Void>(CgJobLoop.current(), Objects.requireNonNull(work, "work"), RUNNABLE, CgJobLane.LATENCY), 0);
    }

    /** Runs {@code work} on a worker now, handing it the job's context. */
    public static <T> CgJob<T> run(Work<? extends T> work) {
        return started(new CgJob<T>(CgJobLoop.current(), Objects.requireNonNull(work, "work"), WORK, CgJobLane.LATENCY), 0);
    }

    /** A job keyed to {@code owner}: starting the same key again replaces this one. */
    public static Builder keyed(Object owner, String kind) {
        return new Builder().keyed(owner, kind);
    }

    /** A job in {@code lane}. */
    public static Builder lane(CgJobLane lane) {
        return new Builder().lane(lane);
    }

    /** A job reporting back to {@code loop} rather than the current one. */
    public static Builder on(CgJobLoop loop) {
        return new Builder().on(loop);
    }

    private static <T> CgJob<T> started(CgJob<T> job, long debounceMillis) {
        job.loop.start(job, debounceMillis);
        return job;
    }

    /**
     * How a job starts: {@link #keyed}, {@link #lane}, {@link #on}, {@link #debounce}, then {@code run}. Reusable:
     * each {@code run} starts a job with these settings.
     */
    public static final class Builder {
        @Nullable
        private CgJobLoop loop;
        @Nullable
        private CgJobKey key;
        private CgJobLane lane = CgJobLane.LATENCY;
        private long debounceMillis;
        @Nullable
        private String name;

        private Builder() {
        }

        /** Starting the same owner and kind again replaces a waiting job and supersedes a running one. */
        public Builder keyed(Object owner, String kind) {
            key = CgJobKey.of(owner, kind);
            return this;
        }

        public Builder keyed(CgJobKey key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        public Builder lane(CgJobLane lane) {
            this.lane = Objects.requireNonNull(lane, "lane");
            return this;
        }

        public Builder on(CgJobLoop loop) {
            this.loop = Objects.requireNonNull(loop, "loop");
            return this;
        }

        /** Starts this long after the last start of its key, checked at the loop's drain. */
        public Builder debounce(long millis) {
            debounceMillis = Math.max(0, millis);
            return this;
        }

        public Builder named(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public <T> CgJob<T> run(Supplier<? extends T> work) {
            return start(new CgJob<T>(loop(), Objects.requireNonNull(work, "work"), SUPPLIER, lane));
        }

        public CgJob<Void> run(Runnable work) {
            return start(new CgJob<Void>(loop(), Objects.requireNonNull(work, "work"), RUNNABLE, lane));
        }

        public <T> CgJob<T> run(Work<? extends T> work) {
            return start(new CgJob<T>(loop(), Objects.requireNonNull(work, "work"), WORK, lane));
        }

        private CgJobLoop loop() {
            return loop != null ? loop : CgJobLoop.current();
        }

        private <T> CgJob<T> start(CgJob<T> job) {
            job.key = key;
            job.name = name;
            return started(job, debounceMillis);
        }
    }

    // ---- the reply

    @Override
    public CgJob<T> then(Consumer<? super T> onResult) {
        reply.then(Objects.requireNonNull(onResult, "onResult"));
        return this;
    }

    @Override
    public CgJob<T> then(Runnable onDone) {
        Objects.requireNonNull(onDone, "onDone");
        return then(ignored -> onDone.run());
    }

    @Override
    public CgJob<T> onError(Consumer<? super CgReplyError> onError) {
        reply.onError(Objects.requireNonNull(onError, "onError"));
        return this;
    }

    @Override
    public CgJob<T> always(Runnable whenSettled) {
        reply.always(Objects.requireNonNull(whenSettled, "whenSettled"));
        return this;
    }

    /** Hears what the work threw, not a cancellation. */
    public CgJob<T> failed(Consumer<? super Throwable> handler) {
        Objects.requireNonNull(handler, "handler");
        return onError(e -> {
            if (!e.is(CgReplyError.CANCELLED)) handler.accept(e.cause() != null ? e.cause() : new IllegalStateException(e.toString()));
        });
    }

    @Override
    public <U> CgReply<U> map(Function<? super T, ? extends U> mapper) {
        return reply.map(mapper);
    }

    /** What the log and a failure call it. */
    public CgJob<T> named(String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    /**
     * Stops it: nothing of it lands, and work polling its context stops. On its loop's thread it settles cancelled
     * now; from another thread, at the loop's next drain.
     */
    @Override
    public void cancel() {
        if (loop.isCurrent()) {
            reply.cancel();   // marks it through the reply's onCancel, then settles
        } else {
            markCancelled();
        }
    }

    @Override
    public boolean isDone() {
        return reply.isDone();
    }

    @Override
    @Nullable
    public T result() {
        return reply.result();
    }

    @Override
    @Nullable
    public CgReplyError error() {
        return reply.error();
    }

    /** Whether its work is on a worker now: still true for a cancelled job whose work has not yet returned. Any thread. */
    public boolean isRunning() {
        return state == RUNNING || inFlight() && state != QUEUED;
    }

    /** The work's latest progress, or null if it reported none. Any thread. */
    @Nullable
    public CgProgressState progress() {
        return context == null ? null : context.progressState();
    }

    @Override
    public String toString() {
        return "CgJob(" + describe() + ", " + reply + ")";
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
     * The job pool as an {@link Executor}, for work that reports nothing back: a cache warmed, a file written. Its
     * tasks queue in {@code lane} beside every job's.
     *
     * <pre>{@code
     * CgJob.executor(CgJobLane.BACKGROUND).execute(() -> sheet.parseEveryValue());
     * }</pre>
     *
     * A task that throws is logged. Anything whose result the caller needs is a {@link #run} job instead.
     */
    public static Executor executor(CgJobLane lane) {
        return EXECUTORS[lane.ordinal()];
    }

    private static final Executor[] EXECUTORS = new Executor[CgJobLane.values().length];

    static {
        for (CgJobLane lane : CgJobLane.values()) EXECUTORS[lane.ordinal()] = task -> CgJobPool.submit(new Task(lane, task));
    }

    /** One {@link #executor} task in the pool's queue. */
    private static final class Task extends CgJobQueue.Entry {
        private final Runnable task;

        Task(CgJobLane lane, Runnable task) {
            this.lane = lane;
            this.task = Objects.requireNonNull(task, "task");
        }

        @Override
        void runQueued() {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.ASYNC, "job.task")) {
                task.run();
            }
        }
    }

    /** Drains the {@linkplain CgJobLoop#current() current} loop. Minecraft's server and client threads call it every tick. */
    public static void drain() {
        CgJobLoop.current().drain();
    }

    /** Whether this thread is one of the engine's workers, where a job's work or a {@link #forEach} item runs. */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof CgWorkerThread;
    }

    // ---- the steps

    @Override
    @SuppressWarnings("unchecked")
    void compute() throws Exception {
        switch (kind) {
            case SUPPLIER:
                result = ((Supplier<? extends T>) work).get();
                break;
            case RUNNABLE:
                ((Runnable) work).run();
                break;
            default:
                result = ((Work<? extends T>) work).run(context);
        }
    }

    @Override
    void stopWork() {
        if (context != null) context.cancel();
    }

    @Override
    boolean deliver() {
        if (!finishFrom(DONE)) return true;   // cancelled since: settled at the next drain
        T r = result;
        result = null;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.ASYNC, "job.then")) {
            reply.resolve(r);
        } catch (RuntimeException e) {
            LOGGER.error("[cg-job] {}: a listener threw", describe(), e);
        }
        return false;
    }

    @Override
    void deliverFailure(Throwable e) {
        if (!finishFrom(FAILED)) return;
        try {
            if (!reply.fail(CgReplyError.failed(e))) LOGGER.error("[cg-job] {} failed", describe(), e);
        } catch (RuntimeException inListener) {
            e.addSuppressed(inListener);
            LOGGER.error("[cg-job] {} failed, and a listener threw", describe(), e);
        }
    }

    @Override
    void deliverCancelled() {
        finishFrom(CANCELLED);
        if (reply.isDone()) return;
        try {
            reply.fail(CgReplyError.cancelled());
        } catch (RuntimeException e) {
            LOGGER.error("[cg-job] {}: a listener threw", describe(), e);
        }
    }

    // ---- repeating

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
        public <T> Repeating<T> work(Function<? super S, ? extends T> work) {
            Repeating<T> job = new Repeating<>(CgJobLoop.current(), read, Objects.requireNonNull(work, "work"));
            job.loop.start(job, 0);
            return job;
        }
    }

    /** A running {@link #repeat}: {@code then} hears each run's result. */
    public static final class Repeating<T> extends CgJobBase {
        private final Supplier<?> read;
        private final Function<Object, ? extends T> work;
        private Object input;
        private T result;
        @Nullable
        private Consumer<? super T> then;
        @Nullable
        private Consumer<? super Throwable> failed;

        @SuppressWarnings("unchecked")
        private Repeating(CgJobLoop loop, Supplier<?> read, Function<?, ? extends T> work) {
            super(loop, read, CgJobLane.LATENCY, READING);
            this.read = read;
            this.work = (Function<Object, ? extends T>) work;
        }

        /** What to do with each run's result, on the thread that started it. */
        public Repeating<T> then(Consumer<? super T> apply) {
            then = Objects.requireNonNull(apply, "apply");
            return this;
        }

        public Repeating<T> then(Runnable apply) {
            Objects.requireNonNull(apply, "apply");
            return then(ignored -> apply.run());
        }

        /** Hears a failure of the work, of {@code read} or of {@code then}, which ends it. */
        public Repeating<T> failed(Consumer<? super Throwable> handler) {
            failed = Objects.requireNonNull(handler, "handler");
            return this;
        }

        public Repeating<T> named(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        /** Stops it: nothing of it runs from now, though a run already going finishes unseen. Any thread. */
        @Override
        public void cancel() {
            markCancelled();
        }

        /** Whether it has ended: cancelled or failed. */
        public boolean isDone() {
            return state >= CANCELLED;
        }

        @Override
        void compute() {
            result = work.apply(input);
        }

        @Override
        boolean read() {
            try {
                input = read.get();
                return true;
            } catch (RuntimeException e) {
                report(e);
                return false;
            }
        }

        @Override
        boolean deliver() {
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
            return againFrom(DONE) && read() && submitFrom(READING);
        }

        @Override
        void deliverFailure(Throwable e) {
            report(e);
        }

        @Override
        void deliverCancelled() {
            finishFrom(CANCELLED);
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
    }
}
