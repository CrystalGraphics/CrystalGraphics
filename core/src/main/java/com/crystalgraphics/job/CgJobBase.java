package com.crystalgraphics.job;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * The state a job moves through, shared by {@link CgJob} and {@link CgJob.Repeating}: the worker runs
 * {@link #compute}, the loop's drain {@link #step}s it. Only {@link #state} and {@link #working} cross threads.
 */
abstract class CgJobBase extends CgJobQueue.Entry {

    static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final AtomicIntegerFieldUpdater<CgJobBase> STATE = AtomicIntegerFieldUpdater.newUpdater(CgJobBase.class, "state");

    /** Ordered: from {@link #CANCELLED} on, the work's result is never used. */
    static final int WAITING = 0, READING = 1, QUEUED = 2, RUNNING = 3, DONE = 4, FAILED = 5,
            CANCELLED = 6, SUPERSEDED = 7, FINISHED = 8;

    private static final int WORK = CgTrace.name("job.work");
    /** A job's zone inside {@code job.work}, named by the class its lambda was written in: {@code job.CgAssetLoader}. */
    private static final ClassValue<Integer> WORK_NAMES = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            String n = type.getName();
            int lambda = n.indexOf("$$Lambda");
            String declared = lambda >= 0 ? n.substring(0, lambda) : n;
            return CgTrace.name("job." + declared.substring(declared.lastIndexOf('.') + 1));
        }
    };

    /** The trace name of {@code work}'s zone. */
    static int nameOf(Object work) {
        return WORK_NAMES.get(work.getClass());
    }

    volatile int state;
    /** Set before claiming the run, cleared after it: a superseded job still running blocks its key's successor. */
    private volatile boolean working;
    final CgJobLoop loop;
    private final int session = CgJobLoop.session();
    /** The caller's lambda, which names it in the log. */
    private final Object origin;
    final Runnable runWork = this::runWork;
    @Nullable
    CgJobKey key;
    @Nullable
    String name;
    long dueAtMillis;
    /** The same key's previous job, superseded but still running. */
    @Nullable
    CgJobBase blocker;
    Throwable failure;

    CgJobBase(CgJobLoop loop, Object origin, CgJobLane lane, int state) {
        this.loop = loop;
        this.origin = origin;
        this.lane = lane;
        this.state = state;
    }

    /** On a worker: runs the work and keeps its result. */
    abstract void compute() throws Exception;

    /** At the drain, once {@link #DONE}: hands the result over; whether to keep the job. */
    abstract boolean deliver();

    /** At the drain, once {@link #FAILED}. Ends the job. */
    abstract void deliverFailure(Throwable e);

    /** At the drain, once {@link #CANCELLED}. Ends the job. */
    abstract void deliverCancelled();

    /** For a repeating job, its read before each run, which submits the work when there is any; whether to keep it. */
    boolean read() {
        return submitFrom(READING);
    }

    /** Tells the running work to stop. */
    void stopWork() {
    }

    abstract void cancel();

    final boolean markCancelled() {
        return endAs(CANCELLED);
    }

    /** Its key started again: nothing of it lands, and it never settles. */
    final void supersede() {
        endAs(SUPERSEDED);
    }

    private boolean endAs(int end) {
        for (int s = state; s < CANCELLED; s = state) {
            if (STATE.compareAndSet(this, s, end)) {
                stopWork();
                return true;
            }
        }
        return false;
    }

    final boolean inFlight() {
        return state == QUEUED || working;
    }

    final boolean submitFrom(int from) {
        if (STATE.compareAndSet(this, from, QUEUED)) loop.dispatch(this);
        return true;   // a cancel that won the race is settled at the next drain
    }

    final boolean finishFrom(int from) {
        return STATE.compareAndSet(this, from, FINISHED);
    }

    final boolean againFrom(int from) {
        return STATE.compareAndSet(this, from, READING);
    }

    @Override
    final void runQueued() {
        runWork();
    }

    private void runWork() {
        working = true;
        try {
            if (!STATE.compareAndSet(this, QUEUED, RUNNING)) return;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.ASYNC, WORK);
                 CgTrace.Zone named = origin != null ? CgTrace.zone(CgChannels.ASYNC, nameOf(origin)) : null) {
                compute();
                STATE.compareAndSet(this, RUNNING, DONE);
            } catch (CgJobContext.CancelledException e) {
                STATE.compareAndSet(this, RUNNING, CANCELLED);
            } catch (Throwable e) {
                failure = e;
                STATE.compareAndSet(this, RUNNING, FAILED);
            }
        } finally {
            working = false;
        }
    }

    /** At the loop's drain: whether to keep it. */
    final boolean step(long nowMillis) {
        if (loop.endsWithSession && session != CgJobLoop.session()) markCancelled();
        switch (state) {
            case WAITING:
                if (nowMillis < dueAtMillis || (blocker != null && blocker.inFlight())) return true;
                blocker = null;
                return submitFrom(WAITING);
            case READING:
                return read();
            case QUEUED:
            case RUNNING:
                return true;
            case DONE:
                return deliver();
            case FAILED: {
                Throwable e = failure;
                failure = null;
                deliverFailure(e);
                return false;
            }
            case CANCELLED:
                deliverCancelled();
                return false;
            default:
                return false;
        }
    }

    final String describe() {
        if (name != null) return name;
        if (key != null) return "job " + key;
        String type = origin.getClass().getName();
        int lambda = type.indexOf("$$Lambda");
        return "a job from " + (lambda < 0 ? type : type.substring(0, lambda));
    }
}
