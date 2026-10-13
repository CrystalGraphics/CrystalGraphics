package com.crystalgraphics.job;

import com.crystalgraphics.job.progress.CgProgressState;
import com.crystalgraphics.net.CgNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Where jobs report back: the jobs started on it, stepped in start order at each {@link #drain()}, where their
 * {@code then}s run. Every thread has its own, so most code never names one: {@code CgJob.run} uses the
 * {@linkplain #current() current} loop, and Minecraft's server and client threads drain theirs every tick.
 *
 * <p>A loop of your own, for work that runs as a sequence of tasks on whichever pool thread is free (CrystalGUI's
 * {@code UiSequence}): enter it around each task, so jobs started there report back to it, and drain it in the
 * sequence's frame.</p>
 *
 * <pre>{@code
 * CgJobLoop jobs = CgJobLoop.create("desktop");
 * sequence.execute(() -> {
 *     try (CgJobLoop.Scope ignored = jobs.enter()) {
 *         jobs.drain();                                 // then()s of finished jobs run here, on the sequence
 *         document.frame(delta, w, h);                  // CgJob.run inside reports back to `jobs`
 *     }
 * });
 * }</pre>
 *
 * A test steps a loop by hand, with work run inline and a clock it turns:
 *
 * <pre>{@code
 * long[] now = {0};
 * CgJobLoop loop = CgJobLoop.create("test", Runnable::run, () -> now[0]);
 * CgJob.on(loop).keyed(doc, "parse").debounce(300).run(() -> parse(doc)).then(trees::add);
 * now[0] += 300;
 * loop.drain();                                         // due: runs inline
 * loop.drain();                                         // delivers
 * }</pre>
 *
 * <ul>
 *   <li>Start, drain and cancel on the loop's own thread or sequence, never two threads at once.</li>
 *   <li>A thread's own loop ends its jobs when the game session ends (leaving a world, the server stopping); a loop
 *       from {@link #create} does not, and ends them at {@link #close()}.</li>
 *   <li>A loop with its own executor runs every job there and ignores lanes.</li>
 * </ul>
 */
public final class CgJobLoop {

    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);
    /** Bumped when this process leaves its world: a thread's own loop cancels the jobs from before. */
    private static final AtomicInteger SESSION = new AtomicInteger();

    static {
        CgNetwork.onServerTick(CgJob::drain);
        CgNetwork.onClientTick(CgJob::drain);
        CgNetwork.onSessionEnd(SESSION::incrementAndGet);
    }

    /** A thread's own loop, made when first asked for, and the loop entered over it. */
    private static final class Slot {
        CgJobLoop own;
        CgJobLoop entered;
    }

    private final String name;
    /** Null for the shared pool. */
    @Nullable
    private final Executor executor;
    final LongSupplier clockMillis;
    final boolean endsWithSession;
    private final ArrayList<CgJobBase> pending = new ArrayList<>();
    /** Each key's latest job, until it ends. */
    @Nullable
    private HashMap<CgJobKey, CgJobBase> byKey;
    private boolean draining;
    private boolean closed;

    private CgJobLoop(String name, @Nullable Executor executor, LongSupplier clockMillis, boolean endsWithSession) {
        this.name = name;
        this.executor = executor;
        this.clockMillis = clockMillis;
        this.endsWithSession = endsWithSession;
    }

    /** A loop on the shared pool and a monotonic clock. */
    public static CgJobLoop create(String name) {
        return new CgJobLoop(Objects.requireNonNull(name, "name"), null, CgJobLoop::monotonicMillis, false);
    }

    /** A loop whose jobs run on {@code executor}, with debounces measured on {@code clockMillis}: for tests. */
    public static CgJobLoop create(String name, Executor executor, LongSupplier clockMillis) {
        return new CgJobLoop(Objects.requireNonNull(name, "name"), Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(clockMillis, "clockMillis"), false);
    }

    /** The loop entered on this thread, else the thread's own. */
    public static CgJobLoop current() {
        Slot slot = SLOT.get();
        if (slot.entered != null) return slot.entered;
        if (slot.own == null) {
            slot.own = new CgJobLoop(Thread.currentThread().getName(), null, CgJobLoop::monotonicMillis, true);
        }
        return slot.own;
    }

    /** Makes this the calling thread's {@linkplain #current() current} loop until the scope closes. */
    public Scope enter() {
        Slot slot = SLOT.get();
        Scope scope = new Scope(slot, slot.entered);
        slot.entered = this;
        return scope;
    }

    /** Ends an {@link #enter()}, restoring the loop current before it. */
    public static final class Scope implements AutoCloseable {
        private final Slot slot;
        @Nullable
        private final CgJobLoop previous;

        private Scope(Slot slot, @Nullable CgJobLoop previous) {
            this.slot = slot;
            this.previous = previous;
        }

        @Override
        public void close() {
            slot.entered = previous;
        }
    }

    public boolean isCurrent() {
        return current() == this;
    }

    public String name() {
        return name;
    }

    /** This loop's clock: what debounces are measured on and a {@link CgProgressState#begunAtMillis()} is read against. */
    public long nowMillis() {
        return clockMillis.getAsLong();
    }

    /**
     * Steps each job once in start order: delivers what finished, starts what is due, settles what was cancelled.
     * A job started by a {@code then} waits for the next drain. Allocates nothing.
     */
    public void drain() {
        if (draining || pending.isEmpty()) return;
        draining = true;
        long now = clockMillis.getAsLong();
        int n = pending.size(), kept = 0;
        try {
            for (int i = 0; i < n; i++) {
                CgJobBase job = pending.get(i);
                pending.set(i, null);
                if (job.step(now)) pending.set(kept++, job);
                else forget(job);
            }
        } finally {
            // jobs started meanwhile follow the kept ones; a throw mid-loop keeps the unstepped ones
            int end = pending.size();
            for (int i = 0; i < end; i++) {
                CgJobBase job = pending.get(i);
                if (job != null && i >= kept) pending.set(kept++, job);
            }
            for (int i = end - 1; i >= kept; i--) pending.remove(i);
            draining = false;
        }
    }

    /** Cancels the job keyed {@code owner}/{@code kind}, waiting or running. */
    public void cancel(Object owner, String kind) {
        CgJobBase job = byKey == null ? null : byKey.get(CgJobKey.of(owner, kind));
        if (job != null) job.cancel();
    }

    /** Cancels every job keyed to {@code owner}: what closing a document calls. */
    public void cancelAll(Object owner) {
        for (int i = 0, n = pending.size(); i < n; i++) {
            CgJobBase job = pending.get(i);
            if (job != null && job.key != null && job.key.owner() == owner) job.cancel();
        }
    }

    /** Cancels every job and refuses new ones, which settle cancelled at once. */
    public void close() {
        closed = true;
        for (int i = 0, n = pending.size(); i < n; i++) {
            CgJobBase job = pending.get(i);
            if (job != null) job.cancel();
        }
        drain();
    }

    /** Jobs not yet ended. */
    public int pendingCount() {
        return pending.size();
    }

    @Override
    public String toString() {
        return "CgJobLoop(" + name + ")";
    }

    // ---- for CgJob

    static int session() {
        return SESSION.get();
    }

    /** Ends the session as leaving a world does; for tests. */
    static void endSession() {
        SESSION.incrementAndGet();
    }

    /** Whether it was taken; a closed loop settles it cancelled instead. */
    boolean start(CgJobBase job, long debounceMillis) {
        if (CgJob.isWorkerThread()) {
            throw new IllegalStateException("a job starts on the thread it reports back to; "
                    + "inside a job's work, split it with CgJob.forEach");
        }
        if (closed) {
            job.markCancelled();
            job.deliverCancelled();
            return false;
        }
        if (job.key != null) {
            if (byKey == null) byKey = new HashMap<>();
            CgJobBase previous = byKey.put(job.key, job);
            if (previous != null) {
                previous.supersede();
                if (previous.inFlight()) job.blocker = previous;   // never two in flight for one key
            }
        }
        pending.add(job);
        if (job.state == CgJobBase.WAITING) {
            if (debounceMillis <= 0 && job.blocker == null) job.submitFrom(CgJobBase.WAITING);
            else job.dueAtMillis = clockMillis.getAsLong() + debounceMillis;
        }
        return true;
    }

    void dispatch(CgJobBase job) {
        if (executor != null) executor.execute(job.runWork);
        else CgJobPool.submit(job);
    }

    private void forget(CgJobBase job) {
        if (job.key != null && byKey != null && byKey.get(job.key) == job) byKey.remove(job.key);
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }
}
