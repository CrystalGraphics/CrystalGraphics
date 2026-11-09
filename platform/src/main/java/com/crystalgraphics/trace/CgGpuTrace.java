package com.crystalgraphics.trace;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the GPU spent on a frame — timer queries issued against the frame they belong to, landing in
 * that frame's {@link CgFrameRecord#gpuNanos()} once the GPU has answered, one to three frames later.
 *
 * <pre>{@code
 * CgGpuTrace.begin("ui");      // GL thread, around GPU work
 * drawTheUi();
 * CgGpuTrace.end();
 *
 * frame.gpuNanos();            // -1 until every query of that frame has resolved
 * CgTrace.countersIn(frame);   // "gpu:ui" = its nanoseconds, one counter per zone name
 * }</pre>
 *
 * <p>Records only while the {@link #GPU} channel is on. Results are collected at each
 * {@link CgTrace#frameBegin()} without waiting on the GPU; a host with no frame loop calls
 * {@link #collect()} itself.</p>
 *
 * <h3>Easy to get wrong</h3>
 * <ul>
 *   <li>Render thread only, with a device installed — {@link #begin} records a timer on its command list.</li>
 *   <li>A frame with no GPU zone has no GPU figure: absent, not zero.</li>
 *   <li>Where the context has no timer queries, {@link #support()} says {@link Support#UNSUPPORTED} and
 *       nothing is recorded — a viewer must say so rather than draw zeros.</li>
 *   <li>One timer query can run at a time, so a zone opened inside another <b>pauses</b> it: the outer
 *       zone is timed in pieces around the inner one, the frame total stays right, and each pause counts
 *       under {@code gpu.nestedFlattened}.</li>
 * </ul>
 *
 * <h3>Groups inside a zone</h3>
 * Marks split a zone's time by label without pausing it: each mark starts a run charged to its label, and the next
 * mark, or the zone's end, closes it. A frame's runs land as counters {@code gpu:<zone>/<label>} beside the zone's own
 * figure, which stays whole and alone in {@link CgFrameRecord#gpuNanos()}.
 *
 * <pre>{@code
 * int opaque = CgGpuTrace.label("opaque"), glass = CgGpuTrace.label("glass");   // once
 * CgGpuTrace.begin("world");
 * CgGpuTrace.mark(opaque);
 * drawOpaque();
 * CgGpuTrace.mark(glass);
 * drawGlass();
 * CgGpuTrace.end();            // "gpu:world/opaque" and "gpu:world/glass", summing to "gpu:world"
 * }</pre>
 *
 * <ul>
 *   <li>A mark outside a measured zone does nothing; a zone opened inside a run is charged to that run too.</li>
 *   <li>A series belongs to the zone it began in: a mark in a zone nested inside it does nothing.</li>
 * </ul>
 *
 * <h3>Zones on a command list</h3>
 * Work recorded through {@code CgRHI} is timed on its command list, by the RHI's timer queries (Unreal's
 * {@code SCOPED_GPU_STAT(RHICmdList, ...)}). These nest without pausing: each zone has its own query.
 *
 * <pre>{@code
 * int shadows = CgGpuTrace.name("shadows");      // once
 * CgGpuTrace.begin(cmd, shadows);
 * ... passes recorded on cmd ...
 * CgGpuTrace.end(cmd);                           // "gpu:shadows", frames later
 * }</pre>
 *
 * <p>A zone opened with a budget slot ({@link #budgetSlot}), and every zone inside it, is timed whatever the
 * channel, and adds its nanoseconds to that slot under the budget frame it ran in ({@link #budgetFrame}): what
 * {@code CgGpuBudget} measures, frame by frame, as the GPU answers.</p>
 *
 * <h3>Slow zones</h3>
 * A zone the GPU ran longer than {@code crystalgraphics.gpu.slowZoneMillis} (100) is logged once by name: past about two
 * seconds Windows resets the driver and every monitor goes dark. {@link #WATCH} times a zone for that alone, whatever
 * the channel, its inner zones left to the channel; each stage firing is timed in it.
 *
 * <pre>{@code
 * CgGpuTrace.begin(stageName, CgGpuTrace.WATCH);   // timed always: a slow firing is named in the log
 * }</pre>
 */
public final class CgGpuTrace {

    private CgGpuTrace() {}

    public static final CgTraceChannel GPU = CgTrace.channel("gpu");

    /** What counters and zone names carry, so a GPU figure is told apart from a CPU one by name alone. */
    public static final String PREFIX = "gpu:";

    /** What parts a group's counter from its zone's name, which never holds one: {@code gpu:world.transparent/glass}. */
    public static final String GROUP = "/";

    public enum Support {
        /** No zone has been opened on a context yet, so nothing has been asked. */
        UNKNOWN,
        SUPPORTED,
        /** The context has no timer queries: there is no GPU track, and zero would be a lie. */
        UNSUPPORTED
    }

    /**
     * Queries unread before new zones are skipped rather than waited for. A frame timing each UI layer
     * issues a couple of hundred, and results land one to three frames late.
     */
    private static final int MAX_IN_FLIGHT = 2048;

    /** Pushed for a zone that issued no query, so its end has nothing to stop. */
    private static final int UNMEASURED = -1;

    /** A zone's budget when it names none: it takes its parent's. */
    public static final int NO_BUDGET = -1;

    /** Budget slots at most: one a consumer, defined once. */
    private static final int BUDGETS = 64;

    private static final int NESTED_FLATTENED = CgTraceNames.intern("gpu.nestedFlattened");
    private static final int DROPPED = CgTraceNames.intern("gpu.droppedInFlightFull");
    private static final int LEAKED = CgTraceNames.intern("gpu.leakedAcrossFrame");

    private static volatile Support support = Support.UNKNOWN;

    /** A {@link Pending#label} for a timer query; else the pending entry is a timestamp. */
    private static final int TIMER = -1;
    /** A timestamp closing its series: it ends the last run and starts none. */
    private static final int SERIES_END = -2;

    /**
     * A timer, or a timestamp of a mark series; pooled, since a budget times passes in every frame. {@code nameId} its
     * zone, {@code label} what the run a stamp starts is charged to, {@code restart} when a dropped stamp before it
     * means the gap since the last one is no run.
     */
    private static final class Pending {
        int nameId, generation, budget, label;
        CgTimerQuery timer;
        long frame, budgetFrame;
        /** Opened with the channel on: its time goes to the trace as well as to its budget. */
        boolean traced, restart;
    }

    // All render-thread only.
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Pending> SPARE = new ArrayDeque<>();
    /**
     * Timers kept in hand, made at a frame boundary in a batch: a new query waits for a threaded driver's queue, and a
     * frame with the GPU behind (results late, the pool empty) paid it per timer, 4 ms for 44 (T10).
     */
    private static final int TIMER_RESERVE = 256, STAMP_RESERVE = 64;
    /** Elapsed timers and timestamps apart: GL fixes a query's target at its first use. */
    private static final ArrayDeque<CgTimerQuery> FREE_TIMERS = new ArrayDeque<>(), FREE_STAMPS = new ArrayDeque<>();
    /** The zones open on command lists (null where one was not timed). */
    private static CgTimerQuery[] listOpen = new CgTimerQuery[8];
    private static int listDepth;
    /** The static zone's timer running now; null for none. */
    private static CgTimerQuery runningTimer;
    /** Whether a static zone or mark has been issued: only then are the pools kept topped up. */
    private static boolean zonesUsed;
    private static int[] open = new int[8];
    private static int[] openBudget = new int[8];
    private static int openDepth;
    private static boolean running;
    /** The label of the run a mark opened, at {@link #markDepth}, else {@link #SERIES_END}. */
    private static int markLabel = SERIES_END;
    private static int markDepth;
    private static boolean markDropped;

    /** The last resolved stamp of the open series: its zone, label (or {@link #SERIES_END}), clock and frame. */
    private static int stampZone, stampLabel = SERIES_END, stampGeneration;
    private static long stampNanos, stampFrame;
    /** {@code zone << 32 | label} to the counter {@code gpu:<zone>/<label>}, and those counters. */
    private static final Map<Long, Integer> GROUP_NAMES = new HashMap<>();
    private static final Set<Integer> GROUPS = new HashSet<>();

    /** Per slot: the nanoseconds of every budget frame before {@link #BUDGET_THROUGH}, all of which have come back. */
    private static final long[] BUDGET_NANOS = new long[BUDGETS];
    private static final long[] BUDGET_THROUGH = new long[BUDGETS];
    /** Per slot: the budget frame whose results are still coming back, -1 for none yet, and their sum so far. */
    private static final long[] BUDGET_AT = new long[BUDGETS];
    private static final long[] BUDGET_SUM = new long[BUDGETS];
    private static int budgetSlots;
    private static long budgetFrame;

    /** A slot timing its zone whatever the channel, for the slow-zone log alone: zones inside it are not charged to it. */
    public static final int WATCH = budgetSlot();
    private static final CgVar.Int SLOW_MILLIS = CgVar.integer("crystalgraphics.gpu.slowZoneMillis", 100, 0, 2000, CgVar.DEV,
            "A GPU zone that ran longer than this many milliseconds is logged once, by name: past about 2000 Windows resets"
                    + " the driver. 0 for none.");
    private static final Logger LOGGER = LogManager.getLogger("CgGpuTrace");
    private static final Set<Integer> SLOW_TOLD = new HashSet<>();
    private static long slowestNanos;
    private static int slowestId = -1;

    /** Resolved but not yet written: frame index to (name id to nanos). */
    private static final Map<Long, Map<Integer, Long>> RESOLVED = new LinkedHashMap<>();
    private static int resolvedGeneration;

    /** Since {@link #resetTotals()}: name id to {nanos, count}. */
    private static final Map<Integer, long[]> TOTALS = new LinkedHashMap<>();

    public static Support support() {
        return support;
    }

    /** Asks the installed device now rather than at the first zone. Render thread. */
    public static Support probe() {
        supported();
        return support;
    }

    /** For a test with no context to ask. */
    static void assumeSupport(Support answer) {
        support = answer;
    }

    /**
     * Whether a zone opened now would be timed: the channel is on and the context can.
     */
    public static boolean isMeasuring() {
        return CgTrace.isEnabled(GPU) && support != Support.UNSUPPORTED;
    }

    /**
     * The longest any timed zone ran on the GPU since {@link #forgetSlowZones}, in nanoseconds, and its name (null for
     * none): what a gate holds under its limit.
     *
     * <pre>{@code
     * CgGpuTrace.forgetSlowZones();        // once its pipelines are built
     * ... frames ...
     * assert CgGpuTrace.slowestNanos() < 500_000_000L : CgGpuTrace.slowestZone();
     * }</pre>
     */
    public static long slowestNanos() {
        return slowestNanos;
    }

    public static String slowestZone() {
        return slowestId < 0 ? null : CgTraceNames.nameOf(slowestId);
    }

    /**
     * Forgets the zones logged slow, so each is logged again, and the slowest: a gate's stretch starts here. A zone split
     * across submissions while the CPU builds a pipeline counts the GPU's wait for it, so a gate starts once they are built.
     */
    public static void forgetSlowZones() {
        SLOW_TOLD.clear();
        slowestNanos = 0L;
        slowestId = -1;
    }

    /** Interns a zone name, prefixed — the form a hot call site holds in a constant. */
    public static int name(String name) {
        return CgTraceNames.intern(PREFIX + name);
    }

    public static void begin(String name) {
        begin(CgTrace.isEnabled(GPU) ? name(name) : UNMEASURED);
    }

    /** Opens a GPU zone by an id from {@link #name(String)}. Pair with exactly one {@link #end()}. */
    public static void begin(int nameId) {
        begin(nameId, NO_BUDGET);
    }

    /**
     * Opens a GPU zone charged to {@code budget}, a slot from {@link #budgetSlot}, or to its parent's with
     * {@link #NO_BUDGET}. Timed while the channel is on, or whatever the channel when it has a budget. Pair with exactly
     * one {@link #end()}.
     */
    public static void begin(int nameId, int budget) {
        if (budget == NO_BUDGET && openDepth > 0 && openBudget[openDepth - 1] != WATCH) budget = openBudget[openDepth - 1];
        if (nameId != UNMEASURED && !measures(budget)) nameId = UNMEASURED;
        if (nameId != UNMEASURED && running) {
            stopQuery();
            count(NESTED_FLATTENED);
        }
        if (nameId != UNMEASURED) startQuery(nameId, budget);
        if (openDepth == open.length) {
            open = Arrays.copyOf(open, openDepth * 2);
            openBudget = Arrays.copyOf(openBudget, openDepth * 2);
        }
        open[openDepth] = nameId;
        openBudget[openDepth++] = budget;
    }

    /** Interns a mark's label, the form a call site holds. */
    public static int label(String label) {
        return CgTraceNames.intern(label);
    }

    /**
     * Starts a run charged to {@code label} (from {@link #label(String)}) in the innermost zone, closing the run
     * before it. GL thread.
     */
    public static void mark(int label) {
        if (openDepth == 0 || open[openDepth - 1] == UNMEASURED || !CgTrace.isEnabled(GPU)) return;
        if (markLabel != SERIES_END && (markDepth != openDepth || markLabel == label)) return;
        stamp(open[openDepth - 1], label);
        markLabel = label;
        markDepth = openDepth;
    }

    /** Closes the innermost zone's run, starting none: what follows in the zone is charged to no label. */
    public static void markEnd() {
        if (markLabel == SERIES_END || markDepth != openDepth) return;
        stamp(open[openDepth - 1], SERIES_END);
        markLabel = SERIES_END;
    }

    /** Closes the innermost zone, resuming the one it paused. */
    public static void end() {
        if (openDepth == 0) return;
        markEnd();
        int nameId = open[--openDepth];
        if (nameId == UNMEASURED) return;
        stopQuery();
        // The nearest timed zone resumes: an untimed one between them never stopped it.
        for (int i = openDepth - 1; i >= 0; i--) {
            if (open[i] == UNMEASURED) continue;
            if (measures(openBudget[i])) startQuery(open[i], openBudget[i]);
            return;
        }
    }

    /** A new budget slot. At most {@value #BUDGETS} in a process; any thread. */
    public static synchronized int budgetSlot() {
        if (budgetSlots == BUDGETS) throw new IllegalStateException("every one of " + BUDGETS + " budget slots is taken");
        BUDGET_AT[budgetSlots] = -1L;
        return budgetSlots++;
    }

    /**
     * The GPU nanoseconds of the slot's budget frames before {@link #budgetThrough}: each such frame has come back
     * whole, one with no zone of the slot as zero. GL thread.
     */
    public static long budgetNanos(int slot) {
        return BUDGET_NANOS[slot];
    }

    /** The budget frame before which the slot's frames have come back whole: a frame or three behind the work. */
    public static long budgetThrough(int slot) {
        return BUDGET_THROUGH[slot];
    }

    /** The budget frame a zone opened now is charged to. */
    public static long budgetFrame() {
        return budgetFrame;
    }

    /** Starts the next budget frame: {@code CgGpuBudget}'s, once per host frame. GL thread. */
    public static void nextBudgetFrame() {
        budgetFrame++;
    }

    /**
     * Reads every query the GPU has finished, without waiting, and writes each frame whose queries have
     * all come back. GL thread; called by {@link CgTrace#frameBegin()}.
     */
    public static void collect() {
        if (PENDING.isEmpty() && RESOLVED.isEmpty()) return;
        int generation = CgTrace.generation();
        if (generation != resolvedGeneration) {
            RESOLVED.clear();
            resolvedGeneration = generation;
        }
        long slow = SLOW_MILLIS.get() * 1_000_000L;
        // Queries complete in issue order: the first unfinished one ends the scan.
        while (!PENDING.isEmpty() && available(PENDING.peekFirst())) {
            Pending done = PENDING.pollFirst();
            long nanos = done.timer.resultNanos();
            if (done.label == TIMER && nanos > slowestNanos) {
                slowestNanos = nanos;
                slowestId = done.nameId;
            }
            if (slow > 0 && done.label == TIMER && nanos > slow && SLOW_TOLD.add(done.nameId)) {
                LOGGER.warn("[gpu] {} ran {} ms on the GPU: past about 2000 ms Windows resets the driver. Turn the gpu"
                        + " trace channel on to see which pass inside it; a frame split while a pipeline built counts that"
                        + " wait too", CgTraceNames.nameOf(done.nameId), nanos / 1_000_000L);
            }
            if (done.label != TIMER) {
                FREE_STAMPS.addLast(done.timer);
                done.timer = null;
                if (done.traced) resolveStamp(done, nanos, generation);
            } else {
                FREE_TIMERS.addLast(done.timer);
                done.timer = null;
                if (done.budget != NO_BUDGET) charge(done.budget, done.budgetFrame, nanos);
                if (done.traced) {
                    long[] total = TOTALS.computeIfAbsent(done.nameId, k -> new long[2]);
                    total[0] += nanos;
                    total[1]++;
                    if (done.generation == generation && done.frame >= 0L) {
                        RESOLVED.computeIfAbsent(done.frame, k -> new HashMap<>()).merge(done.nameId, nanos, Long::sum);
                    }
                }
            }
            SPARE.addLast(done);
        }
        long current = CgTrace.currentFrameIndex();
        for (Iterator<Map.Entry<Long, Map<Integer, Long>>> it = RESOLVED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Map<Integer, Long>> frame = it.next();
            long index = frame.getKey();
            // Only a frame that has closed: one still open may issue more.
            if (index >= current || stillPending(index, generation)) continue;
            long sum = 0L;
            for (Map.Entry<Integer, Long> zone : frame.getValue().entrySet()) {
                if (!GROUPS.contains(zone.getKey())) sum += zone.getValue();
                CgTrace.counterAt(GPU, zone.getKey(), index, zone.getValue());
            }
            CgTrace.setGpu(index, sum);
            it.remove();
        }
        reserve();
    }

    private static boolean available(Pending p) {
        return p.timer.resultNanos() >= 0;
    }

    /**
     * Opens a zone on {@code cmd}, timed by the RHI's timer queries; it may nest in any other. The thread recording
     * {@code cmd}; closed by {@link #end(CgCommandList)} on the same list.
     */
    public static void begin(CgCommandList cmd, int nameId) {
        if (listDepth == listOpen.length) listOpen = Arrays.copyOf(listOpen, listDepth * 2);
        CgTimerQuery timer = null;
        if (CgTrace.isEnabled(GPU) && nameId != UNMEASURED) {
            if (PENDING.size() >= MAX_IN_FLIGHT) {
                count(DROPPED);              // skipped rather than waited for, as a GL zone is
            } else {
                timer = FREE_TIMERS.isEmpty() ? CgRHI.get().createTimerQuery("gpu zone") : FREE_TIMERS.pollFirst();
                Pending p = pending(timer, nameId, TIMER);
                PENDING.addLast(p);
                cmd.beginTimer(timer);
            }
        }
        listOpen[listDepth++] = timer;
    }

    /** Closes the zone {@link #begin(CgCommandList, int)} opened last on {@code cmd}. */
    public static void end(CgCommandList cmd) {
        if (listDepth == 0) throw new IllegalStateException("CgGpuTrace.end(cmd) with no zone open on a command list");
        CgTimerQuery timer = listOpen[--listDepth];
        listOpen[listDepth] = null;
        if (timer != null) cmd.endTimer(timer);
    }

    /** Tops the pools up to their reserve once either is below a quarter of it: one wait for the queue, not one a timer. */
    private static void reserve() {
        if (!zonesUsed) return;
        if (4 * FREE_TIMERS.size() >= TIMER_RESERVE && 4 * FREE_STAMPS.size() >= STAMP_RESERVE) return;
        CgRHI rhi = CgRHI.get();
        while (FREE_TIMERS.size() < TIMER_RESERVE) FREE_TIMERS.addLast(rhi.createTimerQuery("gpu zone"));
        while (FREE_STAMPS.size() < STAMP_RESERVE) FREE_STAMPS.addLast(rhi.createTimerQuery("gpu mark"));
    }

    /**
     * Before a frame boundary, which no GPU zone may span: one still open was leaked by a paint that
     * threw, and is closed here — counted against the frame it leaked from — rather than left running
     * into every frame after.
     */
    static void closeLeaked() {
        if (openDepth == 0) return;
        if (markLabel != SERIES_END) stamp(open[markDepth - 1], SERIES_END);
        markLabel = SERIES_END;
        stopQuery();
        openDepth = 0;
        count(LEAKED);
    }

    /** GPU nanoseconds and count per zone name since {@link #resetTotals()}, names without the prefix. */
    public static Map<String, long[]> totals() {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, long[]> e : TOTALS.entrySet()) {
            String name = CgTraceNames.nameOf(e.getKey());
            out.put(name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name, e.getValue().clone());
        }
        return out;
    }

    public static void resetTotals() {
        TOTALS.clear();
    }

    /** Releases every timer. Render thread, with the device still installed. */
    public static void dispose() {
        if (CgRHI.installed()) {
            CgRHI rhi = CgRHI.get();
            for (Pending p : PENDING) if (p.timer != null) rhi.release(p.timer);
            for (CgTimerQuery t : FREE_TIMERS) rhi.release(t);
            for (CgTimerQuery t : FREE_STAMPS) rhi.release(t);
        }
        FREE_TIMERS.clear();
        FREE_STAMPS.clear();
        PENDING.clear();
        RESOLVED.clear();
        TOTALS.clear();
        openDepth = 0;
        running = false;
        runningTimer = null;
        zonesUsed = false;
        markLabel = SERIES_END;
        stampLabel = SERIES_END;
        markDropped = false;
        support = Support.UNKNOWN;
    }

    private static boolean supported() {
        if (support == Support.UNKNOWN && CgRHI.installed()) {
            support = CgRHI.get().globals().timestamps() ? Support.SUPPORTED : Support.UNSUPPORTED;
        }
        return support == Support.SUPPORTED;
    }

    private static boolean stillPending(long index, int generation) {
        for (Pending p : PENDING) {
            if (p.traced && p.generation == generation && p.frame == index) return true;
        }
        return false;
    }

    /** Queries answer in issue order, so the first of a later frame closes the frame before it. */
    private static void charge(int slot, long frame, long nanos) {
        if (frame != BUDGET_AT[slot]) {
            if (BUDGET_AT[slot] >= 0L) BUDGET_NANOS[slot] += BUDGET_SUM[slot];
            BUDGET_THROUGH[slot] = frame;
            BUDGET_AT[slot] = frame;
            BUDGET_SUM[slot] = 0L;
        }
        BUDGET_SUM[slot] += nanos;
    }

    /** Whether a zone opened now with {@code budget} would be timed. */
    private static boolean measures(int budget) {
        return (budget != NO_BUDGET || CgTrace.isEnabled(GPU)) && supported();
    }

    private static void startQuery(int nameId, int budget) {
        if (PENDING.size() >= MAX_IN_FLIGHT) {
            // Skipped rather than waited for: this must never be what stalls a frame.
            count(DROPPED);
            return;
        }
        zonesUsed = true;
        CgTimerQuery timer = FREE_TIMERS.isEmpty() ? CgRHI.get().createTimerQuery("gpu zone") : FREE_TIMERS.pollFirst();
        CgRHI.get().encoder().beginTimer(timer);
        Pending p = pending(timer, nameId, TIMER);
        p.budget = budget;
        p.budgetFrame = budgetFrame;
        PENDING.addLast(p);
        runningTimer = timer;
        running = true;
    }

    private static void stamp(int zone, int label) {
        if (PENDING.size() >= MAX_IN_FLIGHT) {
            count(DROPPED);
            markDropped = true;
            return;
        }
        zonesUsed = true;
        CgTimerQuery stamp = FREE_STAMPS.isEmpty() ? CgRHI.get().createTimerQuery("gpu mark") : FREE_STAMPS.pollFirst();
        CgRHI.get().encoder().timestamp(stamp);
        Pending p = pending(stamp, zone, label);
        p.restart = markDropped;
        PENDING.addLast(p);
        markDropped = false;
    }

    private static Pending pending(CgTimerQuery timer, int nameId, int label) {
        Pending p = SPARE.isEmpty() ? new Pending() : SPARE.pollFirst();
        p.timer = timer;
        p.nameId = nameId;
        p.label = label;
        p.budget = NO_BUDGET;
        p.budgetFrame = 0L;
        p.restart = false;
        p.traced = CgTrace.isEnabled(GPU);
        p.frame = CgTrace.currentFrameIndex();
        p.generation = CgTrace.generation();
        return p;
    }

    /** Charges the run the last stamp opened, up to this one, to that stamp's label and frame. */
    private static void resolveStamp(Pending done, long nanos, int generation) {
        if (stampLabel != SERIES_END && !done.restart && nanos >= stampNanos) {
            Integer name = groupName(stampZone, stampLabel);
            long[] total = TOTALS.computeIfAbsent(name, k -> new long[2]);
            total[0] += nanos - stampNanos;
            total[1]++;
            if (stampGeneration == generation && stampFrame >= 0L) {
                RESOLVED.computeIfAbsent(stampFrame, k -> new HashMap<>()).merge(name, nanos - stampNanos, Long::sum);
            }
        }
        stampZone = done.nameId;
        stampLabel = done.label;
        stampNanos = nanos;
        stampFrame = done.frame;
        stampGeneration = done.generation;
    }

    private static Integer groupName(int zone, int label) {
        long key = (long) zone << 32 | (label & 0xFFFFFFFFL);
        Integer name = GROUP_NAMES.get(key);
        if (name == null) {
            name = CgTraceNames.intern(CgTraceNames.nameOf(zone) + GROUP + CgTraceNames.nameOf(label));
            GROUP_NAMES.put(key, name);
            GROUPS.add(name);
        }
        return name;
    }

    private static void stopQuery() {
        if (!running) return;
        CgRHI.get().encoder().endTimer(runningTimer);
        runningTimer = null;
        running = false;
    }

    private static void count(int nameId) {
        CgTrace.add(GPU, nameId, 1L);
    }
}
