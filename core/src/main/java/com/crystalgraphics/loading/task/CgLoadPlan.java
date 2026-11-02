package com.crystalgraphics.loading.task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.variable.CgVar;

/**
 * What loads behind the loading screen, as named tasks: work on the job workers, a render-thread part in slices under a
 * per-frame budget, an order between tasks. Every task is tracked ({@link CgLoadTracker}), keeps the screen up while it
 * runs, and moves the bar. Forge's parallel mod loading with its {@code enqueueWork}, under Unreal's slow-task progress.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new CgLifecycleListener() {
 *     @Override public void onLoad(CgLoadPlan plan) {                       // the graphics start, render thread
 *         plan.task("mymod:icons")
 *                 .work(ctx -> decodeIcons(ctx))                          // a worker: no GL, no game state
 *                 .render(task -> uploadNext())                          // render thread, a slice a call until true
 *                 .start();
 *         plan.task("mymod:recipes").after("mymod:icons").work(this::indexRecipes).start();   // after icons
 *         plan.track("mymod:kit", registry.scanAsync("mymod", root, CgAssetSource.DEV));     // work already started
 *     }
 * });
 *
 * try (CgLoadTask join = CgLoadPlan.get().begin("mymod:join")) { ... }   // work started and ended in code
 * }</pre>
 *
 * <ul>
 *   <li><b>Never do the work in {@code onLoad} itself</b>: it runs on the render thread, and the screen does not draw
 *       until it returns. Name it as a task; the watchdog ({@code crystalgraphics.loading.stallMs}) names whoever holds
 *       the render thread.</li>
 *   <li>Ids are {@code <owner>:<name>}, the same every run: the bar weighs a task by its last run's time under its id.</li>
 *   <li>{@code after} names tasks of any owner; one never added by the time every listener's {@code onLoad} has run
 *       fails the task waiting on it. A failed task fails every task after it, logged with the cause.</li>
 *   <li>A task with no work and no render part ends at once; one with both runs the work, then the render part.</li>
 *   <li>Tasks start from any thread; their work starts on the render thread at its next frame, and each job's result
 *       comes back at that thread's next tick.</li>
 *   <li>A dedicated server has no screen and no plan: its loading stays in its phases.</li>
 * </ul>
 */
public final class CgLoadPlan {

    private static final CgVar.Num RENDER_BUDGET = CgVar.number("crystalgraphics.loading.renderBudget", 6f, 0f, 1000f,
            "Milliseconds a frame load tasks' render-thread parts take; one slice always runs. 0 for one slice a frame.");
    private static final Logger LOGGER = LogManager.getLogger(CgLoadPlan.class);
    private static final CgLoadPlan PLAN = new CgLoadPlan();

    private final ConcurrentLinkedQueue<Entry> added = new ConcurrentLinkedQueue<>();
    /** Waiting for their tasks before; render thread only. */
    private final List<Entry> waiting = new ArrayList<>();
    /** In their render part; render thread only. */
    private final List<Entry> rendering = new ArrayList<>();
    private volatile boolean sealed;

    private CgLoadPlan() {
    }

    /** The engine's plan: the one every listener's {@code onLoad} is handed. */
    public static CgLoadPlan get() {
        return PLAN;
    }

    /** A task named {@code id} ({@code <owner>:<name>}), started with {@link Builder#start()}. */
    public Builder task(String id) {
        return new Builder(checked(id));
    }

    /**
     * Tracks work already started elsewhere (a scan, a download) as {@code id}: running until {@code reply} settles,
     * failed if it fails. Render thread, or the thread that owns the reply.
     */
    public CgLoadTask track(String id, CgReply<?> reply) {
        CgLoadTask task = CgLoadTracker.add(checked(id), Collections.emptyList());
        task.started(System.nanoTime());
        if (reply instanceof CgJob<?> job) task.job = job;
        reply.then(() -> task.ended(true, System.nanoTime()))
                .onError(e -> fail(task, e.detail(), e.cause()));
        return task;
    }

    /**
     * Work started and ended in code: running from now until the returned span closes. Any thread.
     *
     * <pre>{@code
     * try (CgLoadPlan.Span building = CgLoadPlan.get().begin("mymod:arena")) {
     *     building.task().progress(0.5f);
     * }
     * }</pre>
     */
    public Span begin(String id) {
        CgLoadTask task = CgLoadTracker.add(checked(id), Collections.emptyList());
        if (task.done()) task.reopen();
        else task.started(System.nanoTime());
        return new Span(task);
    }

    /** Whether any task of the plan has not ended. */
    public boolean busy() {
        return !added.isEmpty() || !waiting.isEmpty() || !rendering.isEmpty() || CgLoadTracker.busy();
    }

    /** Every listener has had {@code onLoad}: a task waiting on one never added fails from now. The lifecycle's. */
    public void seal() {
        sealed = true;
    }

    /**
     * Once a frame: starts each task whose tasks before have ended, then runs render parts in slices while the budget
     * lasts. The frame tick calls it inside its host section; nothing else should. Render thread.
     */
    public void frame() {
        for (Entry e; (e = added.poll()) != null; ) waiting.add(e);
        for (int i = 0; i < waiting.size(); i++) {
            Entry e = waiting.get(i);
            String blocked = blocked(e);
            if (blocked == null) {
                waiting.remove(i--);
                launch(e);
            } else if (blocked.startsWith("!")) {
                waiting.remove(i--);
                fail(e.task, blocked.substring(1), null);
            }
        }
        long budget = (long) (RENDER_BUDGET.get() * 1_000_000L), start = System.nanoTime();
        boolean first = true;
        while (!rendering.isEmpty() && (first || System.nanoTime() - start < budget)) {
            first = false;
            for (int i = 0; i < rendering.size() && (i == 0 || System.nanoTime() - start < budget); i++) {
                Entry e = rendering.get(i);
                boolean finished;
                try {
                    finished = e.render.step(e.task);
                } catch (Exception thrown) {
                    rendering.remove(i--);
                    fail(e.task, thrown.toString(), thrown);
                    continue;
                }
                if (finished) {
                    rendering.remove(i--);
                    e.task.ended(true, System.nanoTime());
                }
            }
        }
    }

    /** Null when every task before has ended; {@code "!why"} when one failed or never came. */
    @Nullable
    private String blocked(Entry e) {
        for (String id : e.task.after()) {
            CgLoadTask before = CgLoadTracker.get(id);
            if (before == null) {
                if (sealed) return "!" + id + ", which it comes after, was never added";
                return id;
            }
            if (before.state() == CgLoadTask.State.FAILED) return "!" + id + ", which it comes after, failed";
            if (before.state() != CgLoadTask.State.DONE) return id;
        }
        return null;
    }

    private void launch(Entry e) {
        e.task.started(System.nanoTime());
        if (e.work == null) {
            afterWork(e);
            return;
        }
        CgJob<?> job = CgJob.run(e.work).named(e.task.id());
        e.task.job = job;
        job.then(() -> afterWork(e)).onError(err -> fail(e.task, err.detail(), err.cause()));
    }

    private void afterWork(Entry e) {
        if (e.render == null) {
            e.task.ended(true, System.nanoTime());
        } else {
            e.task.render();
            rendering.add(e);
        }
    }

    private static void fail(CgLoadTask task, String why, @Nullable Throwable cause) {
        task.ended(false, System.nanoTime());
        if (cause != null) {
            LOGGER.error("[loading] {} failed: {}", task.id(), why, cause);
        } else {
            LOGGER.error("[loading] {} failed: {}", task.id(), why);
        }
    }

    private static String checked(String id) {
        Objects.requireNonNull(id, "id");
        if (id.indexOf(':') <= 0) throw new IllegalArgumentException("a load task's id is <owner>:<name>: " + id);
        return id;
    }

    private record Entry(CgLoadTask task, @Nullable CgJob.Work<?> work, @Nullable CgLoadStep render) {
    }

    /** A task being described; nothing runs until {@link #start()}. */
    public final class Builder {
        private final String id;
        private List<String> after = Collections.emptyList();
        @Nullable private CgJob.Work<?> work;
        @Nullable private CgLoadStep render;

        private Builder(String id) {
            this.id = id;
        }

        /** Starts once every task named here has ended; any owner's. */
        public Builder after(String... ids) {
            after = Collections.unmodifiableList(Arrays.asList(ids.clone()));
            return this;
        }

        /** The work, on a job worker: no GL, no game state. {@code ctx.progress()} moves its share of the bar. */
        public Builder work(CgJob.Work<?> work) {
            this.work = Objects.requireNonNull(work, "work");
            return this;
        }

        /** The work, on a job worker. */
        public Builder work(Runnable work) {
            Objects.requireNonNull(work, "work");
            this.work = ctx -> {
                work.run();
                return null;
            };
            return this;
        }

        /** The render-thread part, in slices after the work: {@link CgLoadStep}. */
        public Builder render(CgLoadStep render) {
            this.render = Objects.requireNonNull(render, "render");
            return this;
        }

        /** Adds it to the plan: tracked now, started at the render thread's next frame once its tasks before have ended. */
        public CgLoadTask start() {
            CgLoadTask task = CgLoadTracker.add(id, after);
            added.add(new Entry(task, work, render));
            return task;
        }
    }

    /** Work started and ended in code: {@link #close()} ends it, done. */
    public static final class Span implements AutoCloseable {
        private final CgLoadTask task;

        private Span(CgLoadTask task) {
            this.task = task;
        }

        public CgLoadTask task() {
            return task;
        }

        /** Ends it as failed, logging why. */
        public void fail(String why) {
            CgLoadPlan.fail(task, why, null);
        }

        @Override
        public void close() {
            task.ended(true, System.nanoTime());
        }
    }
}
