package com.crystalgraphics.gl.shader;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Compiles and links programs on a thread of its own, in a GL context sharing the render thread's objects: Dolphin's
 * shared-context shader compiler, and the reason Unreal compiles GL programs outside its RHI thread
 * (OpenGLShaders.cpp:2743, "precaching on the RHIT will cause severe hitching"). Under a threaded driver every status
 * query waits for the whole of its context's command queue, so a link submitted on the render thread makes its next
 * query, and every query after it, wait for every compile queued before. Here they queue on the worker's context, and
 * the render thread asks only a flag. Where the driver gives program binaries it compiles in a second context sharing
 * nothing ({@link #isolated()}) and moves each program over as a binary: NVIDIA holds the render thread's GL calls
 * while a context sharing its objects compiles.
 *
 * <pre>{@code
 * CgLinkWorker.start();                       // the graphics start, render thread
 * if (!CgLinkWorker.submit(job)) runHere();   // false: no worker, link on the calling thread
 * if (job.isDone()) use(job);                 // later frames: a flag, no GL
 * CgLinkWorker.stop();                        // context teardown, render thread
 * }</pre>
 *
 * <ul>
 *   <li>{@code CgShaderProgram} is its one user: every fresh link goes through it, so nothing else names it.</li>
 *   <li>A job's {@link Job#start}, {@link Job#linked} and {@link Job#finish} run on the worker, with its context current:
 *       program and shader objects only, never state the render thread's shadow keeps, and never
 *       {@code CgGL.glDeleteProgram}, which updates it: a program nobody takes is deleted on the render thread.</li>
 *   <li>Absent where the backend gives no worker context (the tracked backend links on a worker of its own) or under
 *       {@code -Dcrystalgraphics.shader.linkWorker=false}; then {@link #submit} answers false.</li>
 * </ul>
 */
public final class CgLinkWorker {

    private static final CgVar.Bool ENABLED = CgVar.bool("crystalgraphics.shader.linkWorker", true, CgVar.DEV | CgVar.RESTART,
            "Compile and link programs on a worker thread in a shared GL context; false links on the render thread.");
    /** How long the worker sleeps between polls while links are in flight. */
    private static final long POLL_NANOS = 250_000L;
    private static final long FENCE_TIMEOUT_NANOS = 10_000_000_000L;

    /**
     * Links handed to the driver at once. NVIDIA compiles them one after another, on one context or several (measured:
     * 2 and 4 worker contexts, no faster cold), so more in flight only lengthens what a {@link #boost}ed one waits behind.
     */
    private static final int MAX_IN_FLIGHT = CgVar.integer("crystalgraphics.shader.linksInFlight", 4, 1, 16,
            CgVar.DEV | CgVar.RESTART, "Links the worker hands the GL driver at once.").get();

    private static volatile CgLinkWorker running;
    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    /**
     * A link the worker carries out. Its three steps run on the worker, in order: {@link #start} once, {@link #linked}
     * until true, {@link #finish} once. The caller reads its result once {@link #isDone()}.
     */
    public abstract static class Job {
        private volatile boolean done;
        private boolean discarded;
        private String failure;

        /** What threw on the worker, or null: read once {@link #isDone()}. */
        public final String failure() {
            return failure;
        }

        /** Submits the GL work; true when it is finished already (a binary loaded). Worker. */
        protected abstract boolean start();

        /** Whether the driver has finished it. Worker; only where the driver links on threads of its own. */
        protected abstract boolean linked();

        /** Reads its status and frees what the link no longer needs. Worker. */
        protected abstract void finish();

        /**
         * Where the worker compiles on a context of its own ({@link #isolated()}): moves what {@link #finish} made into the
         * render thread's objects, with the shared context current. Nothing by default. Worker.
         */
        protected void land() {
        }

        /** The program it made, 0 for none: what the render thread deletes when nobody took it. */
        protected abstract int program();

        /** Whether its result is ready: a flag, no GL. Any thread. */
        public final boolean isDone() {
            return done;
        }

        /** Waits until {@link #isDone()}. */
        public final void await() {
            synchronized (this) {
                while (!done) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /**
         * Gives it up: the worker frees it when it ends. Answers true when it is done already, and what it made is the
         * caller's to free.
         */
        public final synchronized boolean discard() {
            if (done) return true;
            discarded = true;
            return false;
        }

        /** Marks it done; answers whether it was discarded, so the worker frees it. */
        private synchronized boolean complete() {
            done = true;
            notifyAll();
            return discarded;
        }
    }

    private final CgGLWorkerContext context;
    /** A context sharing nothing, where the worker compiles; null where it compiles on {@link #context}. */
    @Nullable private final CgGLWorkerContext isolatedContext;
    private final boolean parallel;
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final Thread thread;
    private volatile boolean stopping;
    /** Links handed to the driver and not yet finished: while any is, the driver holds what the render thread would ask. */
    private volatile int inFlightCount;

    private CgLinkWorker(CgGLWorkerContext context, @Nullable CgGLWorkerContext isolatedContext, boolean parallel) {
        this.context = context;
        this.isolatedContext = isolatedContext;
        this.parallel = parallel;
        this.thread = new Thread(this::run, "crystalgraphics-link");
        thread.setDaemon(true);
    }

    private static final CgVar.Bool ISOLATED = CgVar.bool("crystalgraphics.shader.isolatedCompile", true,
            CgVar.DEV | CgVar.RESTART, "Compile programs in a context sharing nothing with the render thread's, each"
                    + " moved over as a binary: NVIDIA holds the render thread's calls while a shared context compiles.");

    /** Starts the worker, where the backend gives a worker context. Render thread, its context current. */
    public static void start() {
        if (running != null || !ENABLED.get()) return;
        CgGLWorkerContext context = CgGL.createWorkerContext();
        if (context == null) return;
        CgCapabilities caps = CgCapabilities.detect();
        // A binary is how a program compiled apart reaches the render thread's objects: none where the driver lists no
        // format (macOS)
        CgGLWorkerContext isolated = ISOLATED.get() && caps.getProgramBinaryFormats() > 0
                ? CgGL.createIsolatedWorkerContext() : null;
        CgLinkWorker worker = new CgLinkWorker(context, isolated, caps.isParallelShaderCompile());
        worker.thread.start();
        running = worker;
        LOG.info("[cg] link worker: compiles {}", isolated != null ? "in a context of its own, moved over as binaries"
                : "in a context sharing the render thread's");
    }

    /** Whether fresh links go to the worker. */
    public static boolean on() {
        return running != null;
    }

    /** Whether the worker compiles on a context of its own, each job {@link Job#land landing} its program after. */
    public static boolean isolated() {
        CgLinkWorker worker = running;
        return worker != null && worker.isolatedContext != null;
    }

    /**
     * Whether the worker has links with the driver or waiting: while it has, NVIDIA makes a program call on the render
     * thread (a uniform query, a block's wiring) wait for the link in progress, up to hundreds of milliseconds. Work that
     * can wait for a frame between links does. Any thread.
     */
    public static boolean linking() {
        CgLinkWorker worker = running;
        return worker != null && (worker.inFlightCount > 0 || !worker.queue.isEmpty());
    }

    /** Hands {@code job} to the worker; false where there is none, and the caller links on its own thread. */
    public static boolean submit(Job job) {
        CgLinkWorker worker = running;
        if (worker == null) return false;
        reap();
        worker.queue.add(job);
        return true;
    }

    /**
     * Moves {@code job} to the front of what waits, if the driver has not been handed it yet: a caller about to wait
     * for it waits behind the links in flight alone. Unreal's {@code CheckPSOPrecachingAndBoostPriority}
     * (PSOPrecacheComponent.h:94). Any thread.
     */
    public static void boost(Job job) {
        CgLinkWorker worker = running;
        if (worker != null && worker.queue.remove(job)) worker.queue.addFirst(job);
    }

    /** Finishes what is in flight and waiting, frees its context and stops the worker. Render thread, at teardown. */
    public static void stop() {
        CgLinkWorker worker = running;
        if (worker == null) return;
        running = null;
        worker.stopping = true;
        worker.thread.interrupt();
        try {
            worker.thread.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!worker.thread.isAlive()) {
            worker.context.destroy();
            if (worker.isolatedContext != null) worker.isolatedContext.destroy();
        }
        reap();
    }

    private void run() {
        CgGLWorkerContext compiling = isolatedContext != null ? isolatedContext : context;
        compiling.makeCurrent();
        try {
            if (parallel) CgGL.glMaxShaderCompilerThreads(0xFFFFFFFF);
            List<Job> inFlight = new ArrayList<>(), finished = new ArrayList<>();
            while (!stopping || !inFlight.isEmpty() || !queue.isEmpty()) {
                Job next = inFlight.isEmpty() && !stopping ? take() : null;
                if (next == null && inFlight.size() < MAX_IN_FLIGHT) next = queue.poll();
                while (next != null) {
                    if (startJob(next)) finished.add(next);
                    else inFlight.add(next);
                    next = inFlight.size() < MAX_IN_FLIGHT ? queue.poll() : null;
                }
                inFlightCount = inFlight.size();
                for (int i = inFlight.size() - 1; i >= 0; i--) {
                    Job job = inFlight.get(i);
                    if (parallel && !stopping && !job.linked()) continue;
                    finishJob(job);
                    finished.add(job);
                    inFlight.remove(i);
                }
                inFlightCount = inFlight.size();
                if (!finished.isEmpty()) {
                    if (isolatedContext != null) {
                        context.makeCurrent();
                        for (int i = 0; i < finished.size(); i++) landJob(finished.get(i));
                    }
                    publish(finished);
                    if (isolatedContext != null) isolatedContext.makeCurrent();
                    finished.clear();
                } else if (!inFlight.isEmpty()) {
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, POLL)) {
                        LockSupport.parkNanos(POLL_NANOS);
                    }
                }
            }
        } finally {
            compiling.release();
        }
    }

    private static void landJob(Job job) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, LAND)) {
            job.land();
        } catch (RuntimeException e) {
            job.failure = "link failed to land: " + e;
        }
    }

    private Job take() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, IDLE)) {
            return queue.take();
        } catch (InterruptedException e) {
            return null;   // stop() woke it
        }
    }

    private static final int IDLE = CgTrace.waitName("shader.link.idle"), START = CgTrace.name("shader.link.start"),
            FINISH = CgTrace.name("shader.link.finish"), POLL = CgTrace.waitName("shader.link.poll"),
            LAND = CgTrace.name("shader.link.land");

    private static boolean startJob(Job job) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, START)) {
            return job.start();
        } catch (RuntimeException e) {
            job.failure = "link failed to start: " + e;
            return true;
        }
    }

    private static void finishJob(Job job) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
            job.finish();
        } catch (RuntimeException e) {
            job.failure = "link failed to finish: " + e;
        }
    }

    /** Waits for the worker's context to complete what the jobs made, then hands them over. */
    private static void publish(List<Job> finished) {
        // A program another context changed is complete for the render thread only once this one completed it.
        long fence = CgGL.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        CgGL.glClientWaitSync(fence, CgGL.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NANOS);
        CgGL.glDeleteSync(fence);
        for (int i = 0; i < finished.size(); i++) {
            Job job = finished.get(i);
            if (job.complete() && job.program() != 0) ORPHANS.add(job.program());
        }
    }

    /** Programs of discarded jobs, deleted on the render thread: a delete updates its state shadow. */
    private static final ConcurrentLinkedQueue<Integer> ORPHANS = new ConcurrentLinkedQueue<>();

    /** Deletes what discarded jobs made. Render thread. */
    private static void reap() {
        for (Integer program; (program = ORPHANS.poll()) != null; ) CgGL.glDeleteProgram(program);
    }
}
