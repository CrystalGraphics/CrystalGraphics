package com.crystalgraphics.loading.task;

import com.crystalgraphics.loading.CgLoadingProcess;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFiles;

/**
 * Everything loading behind the screen, and how far along it is: every {@link CgLoadPlan} task, the work it adopted, and
 * each reason a {@link CgLoadingProcess} gives. The record behind the bar, the screen's task lines and the log; Unreal's
 * slow-task stack and Forge's startup progress meters, as one list.
 *
 * <pre>{@code
 * for (CgLoadTask t : CgLoadTracker.running()) {          // what loads now, oldest first: in parallel when several
 *     line(t.owner() + "  " + t.id() + "  " + t.detail());
 * }
 * float bar = CgLoadTracker.progress(System.nanoTime());
 * CgLoadTracker.key("skinned-play");                      // a host, before the graphics start: whose history the bar reads
 * }</pre>
 *
 * <ul>
 *   <li><b>The bar is time done over time expected</b>: an ended task counts what it took; a waiting one, what it took in
 *       the last load with the same {@link #key} (kept in the cache directory, {@code loading/<key>.txt}), else
 *       {@link #DEFAULT_MILLIS}; tasks of that load not seen yet count too, so the bar does not reach the end while work
 *       is still coming. A running task counts its time so far against its whole: the whole its own progress implies,
 *       trusted over its last time as it goes (all of it at 100%); with no progress, its last time, and once past that
 *       its time so far plus the overrun again: work running long holds the bar rather than racing it to the end.</li>
 *   <li>At the hide the timeline is logged: each task's start and end since the screen showed, by owner, so what ran in
 *       parallel and what waited is plain.</li>
 *   <li>Any thread reads; the plan and the screen write, on the render thread.</li>
 * </ul>
 */
public final class CgLoadTracker {

    /** What a task with no history weighs, in milliseconds. */
    public static final long DEFAULT_MILLIS = 1000L;
    /** The share of its whole a running task with no report of its own is taken to have done, at most. */
    private static final float UNREPORTED_CAP = 0.9f;

    private static final Logger LOGGER = LogManager.getLogger(CgLoadTracker.class);

    private static final Map<String, CgLoadTask> TASKS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static String key = "default";
    @Nullable private static Map<String, Long> history;
    private static long shownAt = System.nanoTime();

    private CgLoadTracker() {
    }

    /**
     * Names this load for its history: a harness scene's id, a game's {@code "minecraft"}. Tasks differ from load to
     * load, so the bar weighs each by the last load of the same key. Before the graphics start; any thread.
     */
    public static synchronized void key(String name) {
        key = name.replaceAll("[^A-Za-z0-9._-]", "_");
        history = null;
    }

    /** Every task of this load, in the order they were made. */
    public static List<CgLoadTask> all() {
        synchronized (TASKS) {
            return new ArrayList<>(TASKS.values());
        }
    }

    /** The tasks running now, oldest first: more than one is work in parallel. */
    public static List<CgLoadTask> running() {
        List<CgLoadTask> out = new ArrayList<>();
        synchronized (TASKS) {
            for (CgLoadTask t : TASKS.values()) {
                if (t.state() == CgLoadTask.State.RUNNING || t.state() == CgLoadTask.State.RENDER) out.add(t);
            }
        }
        out.sort((a, b) -> Long.compare(a.startedAt(), b.startedAt()));
        return out;
    }

    /** The task {@code id}, or null. */
    @Nullable
    public static CgLoadTask get(String id) {
        return TASKS.get(id);
    }

    /** Whether any task is not ended. */
    public static boolean busy() {
        synchronized (TASKS) {
            for (CgLoadTask t : TASKS.values()) {
                if (!t.done()) return true;
            }
        }
        return false;
    }

    /**
     * The bar, 0 to 1: the tasks' expected time done over all expected time, this load's tasks and those of the last
     * load not seen yet. Not clamped to rise: the screen keeps it from going back.
     */
    public static float progress(long now) {
        Map<String, Long> past = history();
        List<CgLoadTask> tasks = all();
        double pace = pace(tasks, past, now);
        double total = 0, done = 0;
        for (CgLoadTask t : tasks) {
            // Ended before the screen's first frame: nobody saw a bar move for it
            if (t.done() && t.endedAt() < shownAt) continue;
            Long ms = past.get(t.id());
            float own = t.reported();
            boolean reports = !Float.isNaN(own) && own > 0.05f;
            // Scaled by the pace only with no progress of its own: that progress is its pace already
            double expected = Math.max(1, ms != null ? ms * (reports ? 1 : pace) : DEFAULT_MILLIS);
            double elapsed = t.elapsedMillis(now);
            switch (t.state()) {
                case DONE, FAILED -> {          // what it took this time
                    total += Math.max(1, elapsed);
                    done += Math.max(1, elapsed);
                }
                case WAITING -> total += expected;
                default -> {
                    // Its own progress says how long the whole will take, trusted over last time as it goes: a warm load
                    // after a cold one would otherwise lag. Without one, running longer than last time stretches the
                    // total, so the bar slows rather than pegging
                    // Past its last time with no report, it is taken as half done: the bar holds rather than racing
                    // Trusted no further than its share of the time expected: a total still growing (pipelines asked
                    // for as assets load) reads half done a few milliseconds in
                    double whole = reports
                            ? expected + (elapsed / own - expected) * Math.min(Math.min(1f, own), elapsed / expected)
                            : Math.max(elapsed / UNREPORTED_CAP, elapsed <= expected ? expected : 2 * elapsed - expected);
                    total += Math.max(elapsed / UNREPORTED_CAP, whole);
                    done += elapsed;
                }
            }
        }
        for (Map.Entry<String, Long> e : past.entrySet()) {
            if (!TASKS.containsKey(e.getKey())) total += Math.max(1, e.getValue() * pace);
        }
        return total == 0 ? 0f : (float) Math.min(1.0, done / total);
    }

    /**
     * How much slower than last load this one runs: the time this load's ended tasks took over their last times (the
     * engine's start among them, before the screen shows), and a running task reporting progress its time so far over
     * that share of its last time, drawn toward 1 by {@link #PACE_PRIOR_MILLIS} of evidence. A busy machine slows every
     * task alike, so what is still to come is scaled by it. A running task's overrun with no progress is its own
     * estimate's, not this.
     */
    static double pace(List<CgLoadTask> tasks, Map<String, Long> past, long now) {
        double took = PACE_PRIOR_MILLIS, expected = PACE_PRIOR_MILLIS;
        for (CgLoadTask t : tasks) {
            Long ms = past.get(t.id());
            if (ms == null || t.state() == CgLoadTask.State.WAITING) continue;
            if (t.done()) {
                took += t.elapsedMillis(now);
                expected += ms;
                continue;
            }
            // Running with progress of its own: what its share took against that share of last time
            float own = t.reported();
            if (!Float.isNaN(own) && own > 0.05f) {
                took += t.elapsedMillis(now);
                expected += ms * Math.min(1f, own);
            }
        }
        return Math.max(1.0 / MAX_PACE, Math.min(MAX_PACE, took / expected));
    }

    /** How much evidence of last load's pace {@link #pace} starts from, in milliseconds. */
    private static final double PACE_PRIOR_MILLIS = 500;
    private static final double MAX_PACE = 3;

    // -- Written by the plan and the screen -----------------------------------------------------------------------

    /** A new task, or the one already under {@code id}. */
    static CgLoadTask add(String id, List<String> after) {
        synchronized (TASKS) {
            CgLoadTask t = TASKS.get(id);
            if (t == null) {
                t = new CgLoadTask(id, after);
                TASKS.put(id, t);
            }
            return t;
        }
    }

    /**
     * A loading process's reason, given this frame, as the task {@code id}: started, or running again, with the
     * process's {@code progress} (NaN for none). The loading screen's.
     */
    public static void reason(String id, float progress, long now) {
        CgLoadTask task = add(id, Collections.emptyList());
        if (task.done()) task.reopen();
        else if (task.state() == CgLoadTask.State.WAITING) task.started(now);
        task.progress(progress);
    }

    /** A reason no longer given: its task ends. The loading screen's. */
    public static void reasonEnded(String id, long now) {
        CgLoadTask task = TASKS.get(id);
        if (task != null) task.ended(true, now);
    }

    /**
     * The screen's first frame: the timeline's zero, and the bar's, which leaves out work ended before it (nobody saw a
     * bar for it). The loading screen's.
     */
    public static void since(long shownNanos) {
        shownAt = shownNanos;
    }

    /** The screen showed: a new load's record. Tasks of the last one that still run carry over. The loading screen's. */
    public static void shown(long now) {
        shownAt = now;
        synchronized (TASKS) {
            TASKS.values().removeIf(CgLoadTask::done);
        }
    }

    /** The screen hid: the timeline logged and each ended task's time kept for the next load's bar. The loading screen's. */
    public static void hidden(long now) {
        List<CgLoadTask> tasks = all();
        logTimeline(tasks, now);
        // Each time averaged with the last, so one cold or busy load moves the next one's bar halfway, not all the way
        Map<String, Long> past = history(), times = new LinkedHashMap<>();
        for (CgLoadTask t : tasks) {
            if (!t.done()) continue;
            long took = Math.max(1L, t.elapsedMillis(now));
            Long before = past.get(t.id());
            times.put(t.id(), before == null ? took : (before + took + 1) / 2);
        }
        if (times.isEmpty()) return;
        String name = key;
        history = times;
        CgJob.executor(CgJobLane.BACKGROUND).execute(() -> save(name, times));
    }

    /** Each task's span since the screen showed, by owner: what overlapped ran in parallel. */
    private static void logTimeline(List<CgLoadTask> tasks, long now) {
        if (tasks.isEmpty()) return;
        Map<String, List<CgLoadTask>> byOwner = new LinkedHashMap<>();
        for (CgLoadTask t : tasks) byOwner.computeIfAbsent(t.owner(), o -> new ArrayList<>()).add(t);
        StringBuilder s = new StringBuilder("[loading] timeline (ms since shown):");
        for (Map.Entry<String, List<CgLoadTask>> e : byOwner.entrySet()) {
            s.append("\n  ").append(e.getKey());
            for (CgLoadTask t : e.getValue()) {
                long from = t.startedAt() == 0L ? -1 : (t.startedAt() - shownAt) / 1_000_000L;
                long to = t.endedAt() == 0L ? -1 : (t.endedAt() - shownAt) / 1_000_000L;
                s.append("\n    ").append(t.id()).append("  ").append(from).append(" .. ").append(to)
                        .append("  (").append(t.elapsedMillis(now)).append(" ms").append(t.state() == CgLoadTask.State.FAILED ? ", FAILED" : "")
                        .append(t.after().isEmpty() ? "" : ", after " + String.join(", ", t.after())).append(')');
            }
        }
        LOGGER.info(s.toString());
    }

    // -- History ------------------------------------------------------------------------------------------------------

    private static synchronized Map<String, Long> history() {
        if (history == null) history = load(key);
        return history;
    }

    private static final CgCodec<Map<String, Long>> HISTORY = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.LONG);

    @Nullable
    private static Path file(String name) {
        Path dir = CgCacheDirectory.of("loading");
        return dir == null ? null : dir.resolve(name + ".json");
    }

    private static Map<String, Long> load(String name) {
        Map<String, Long> out = new HashMap<>();
        try {
            Path f = file(name);
            Map<String, Long> read = f == null ? null : CgFiles.JSON.read(f, HISTORY);
            if (read != null) out.putAll(read);
        } catch (IOException | RuntimeException unreadable) {
            // No game directory, or a file somebody damaged: a bar with no history estimates, and the next save replaces it
            out.clear();
        }
        return out;
    }

    private static void save(String name, Map<String, Long> times) {
        try {
            Path f = file(name);
            if (f != null) CgFiles.JSON.write(f, HISTORY, times);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[loading] the bar's history could not be kept: {}", e.toString());
        }
    }

    /** Back to no tasks and no history. Tests only. */
    static void resetForTesting(@Nullable Map<String, Long> past) {
        TASKS.clear();
        history = past == null ? new HashMap<>() : new HashMap<>(past);
        shownAt = System.nanoTime();
    }
}
