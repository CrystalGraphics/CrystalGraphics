package com.crystalgraphics.trace;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGlRecording;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *   <li>GL thread only, with a context current — {@link #begin} is where a query is issued.</li>
 *   <li>A frame with no GPU zone has no GPU figure: absent, not zero.</li>
 *   <li>Where the context has no timer queries, {@link #support()} says {@link Support#UNSUPPORTED} and
 *       nothing is recorded — a viewer must say so rather than draw zeros.</li>
 *   <li>One timer query can run at a time, so a zone opened inside another <b>pauses</b> it: the outer
 *       zone is timed in pieces around the inner one, the frame total stays right, and each pause counts
 *       under {@code gpu.nestedFlattened}.</li>
 *   <li>A zone opened while a {@link CgGlRecording} captures is not timed, since nothing reaches the GPU
 *       then; a zone around the replay times the work.</li>
 * </ul>
 *
 * <p>A zone opened with a budget slot ({@link #budgetSlot}), and every zone inside it, is timed whatever the
 * channel, and adds its nanoseconds to that slot under the budget frame it ran in ({@link #budgetFrame}): what
 * {@code CgGpuBudget} measures, frame by frame, as the GPU answers.</p>
 */
public final class CgGpuTrace {

    private CgGpuTrace() {}

    public static final CgTraceChannel GPU = CgTrace.channel("gpu");

    /** What counters and zone names carry, so a GPU figure is told apart from a CPU one by name alone. */
    public static final String PREFIX = "gpu:";

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

    /** A query in flight; pooled, since a budget times passes in every frame. */
    private static final class Pending {
        int query, nameId, generation, budget;
        long frame, budgetFrame;
        /** Opened with the channel on: its time goes to the trace as well as to its budget. */
        boolean traced;
    }

    // All GL-thread only.
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Pending> SPARE = new ArrayDeque<>();
    private static int[] freeQueries = new int[16];
    private static int freeCount;
    private static int[] open = new int[8];
    private static int[] openBudget = new int[8];
    private static int openDepth;
    private static boolean running;

    /** Per slot: the nanoseconds of every budget frame before {@link #BUDGET_THROUGH}, all of which have come back. */
    private static final long[] BUDGET_NANOS = new long[BUDGETS];
    private static final long[] BUDGET_THROUGH = new long[BUDGETS];
    /** Per slot: the budget frame whose results are still coming back, -1 for none yet, and their sum so far. */
    private static final long[] BUDGET_AT = new long[BUDGETS];
    private static final long[] BUDGET_SUM = new long[BUDGETS];
    private static int budgetSlots;
    private static long budgetFrame;

    /** Resolved but not yet written: frame index to (name id to nanos). */
    private static final Map<Long, Map<Integer, Long>> RESOLVED = new LinkedHashMap<>();
    private static int resolvedGeneration;

    /** Since {@link #resetTotals()}: name id to {nanos, count}. */
    private static final Map<Integer, long[]> TOTALS = new LinkedHashMap<>();

    public static Support support() {
        return support;
    }

    /** Asks the current context now rather than at the first zone. GL thread. */
    public static Support probe() {
        supported();
        return support;
    }

    /** For a test with no context to ask. */
    static void assumeSupport(Support answer) {
        support = answer;
    }

    /**
     * Whether a zone opened now would be timed: the channel is on, the context can, and no
     * {@link CgGlRecording} is capturing — nothing recorded reaches the GPU until replay.
     */
    public static boolean isMeasuring() {
        return CgTrace.isEnabled(GPU) && support != Support.UNSUPPORTED && !CgGL.isRecording();
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
        if (budget == NO_BUDGET && openDepth > 0) budget = openBudget[openDepth - 1];
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

    /** Closes the innermost zone, resuming the one it paused. */
    public static void end() {
        if (openDepth == 0) return;
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
        // Queries complete in issue order: the first unfinished one ends the scan.
        while (!PENDING.isEmpty() && CgGL.glIsQueryResultAvailable(PENDING.peekFirst().query)) {
            Pending done = PENDING.pollFirst();
            long nanos = CgGL.glGetQueryResultNanos(done.query);
            release(done.query);
            if (done.budget != NO_BUDGET) charge(done.budget, done.budgetFrame, nanos);
            if (done.traced) {
                long[] total = TOTALS.computeIfAbsent(done.nameId, k -> new long[2]);
                total[0] += nanos;
                total[1]++;
                if (done.generation == generation && done.frame >= 0L) {
                    RESOLVED.computeIfAbsent(done.frame, k -> new HashMap<>()).merge(done.nameId, nanos, Long::sum);
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
                sum += zone.getValue();
                CgTrace.counterAt(GPU, zone.getKey(), index, zone.getValue());
            }
            CgTrace.setGpu(index, sum);
            it.remove();
        }
    }

    /**
     * Before a frame boundary, which no GPU zone may span: one still open was leaked by a paint that
     * threw, and is closed here — counted against the frame it leaked from — rather than left running
     * into every frame after.
     */
    static void closeLeaked() {
        if (openDepth == 0) return;
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

    /** Releases every query object. GL thread, with the context still current. */
    public static void dispose() {
        for (Pending p : PENDING) CgGL.glDeleteQuery(p.query);
        for (int i = 0; i < freeCount; i++) CgGL.glDeleteQuery(freeQueries[i]);
        PENDING.clear();
        RESOLVED.clear();
        TOTALS.clear();
        freeCount = 0;
        openDepth = 0;
        running = false;
        support = Support.UNKNOWN;
    }

    private static boolean supported() {
        if (support == Support.UNKNOWN) {
            support = Support.SUPPORTED;   // core in GL 3.3, the floor
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
        return (budget != NO_BUDGET || CgTrace.isEnabled(GPU)) && supported() && !CgGL.isRecording();
    }

    private static void startQuery(int nameId, int budget) {
        if (PENDING.size() >= MAX_IN_FLIGHT) {
            // Skipped rather than waited for: this must never be what stalls a frame.
            count(DROPPED);
            return;
        }
        int query = freeCount > 0 ? freeQueries[--freeCount] : CgGL.glGenQuery();
        CgGL.glBeginTimeElapsedQuery(query);
        Pending p = SPARE.isEmpty() ? new Pending() : SPARE.pollFirst();
        p.query = query;
        p.nameId = nameId;
        p.budget = budget;
        p.budgetFrame = budgetFrame;
        p.traced = CgTrace.isEnabled(GPU);
        p.frame = CgTrace.currentFrameIndex();
        p.generation = CgTrace.generation();
        PENDING.addLast(p);
        running = true;
    }

    private static void stopQuery() {
        if (!running) return;
        CgGL.glEndTimeElapsedQuery();
        running = false;
    }

    private static void release(int query) {
        if (freeCount == freeQueries.length) freeQueries = Arrays.copyOf(freeQueries, freeCount * 2);
        freeQueries[freeCount++] = query;
    }

    private static void count(int nameId) {
        CgTrace.add(GPU, nameId, 1L);
    }
}
