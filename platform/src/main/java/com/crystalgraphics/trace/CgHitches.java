package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Finds the hitches in a run and says what each was: the rule, the ranking of causes and the tail percentiles.
 *
 * <pre>{@code
 * CgTraceSnapshot snap = CgTrace.snapshot();
 * for (CgHitch hitch : CgHitches.find(snap, 16_700_000L)) {
 *     System.out.println(hitch.frame().index() + " " + (hitch.explained() ? hitch.top().detail() : "unexplained"));
 * }
 * long p999 = CgHitches.percentile(walls, 999);   // nearest rank, exact
 * }</pre>
 *
 * <p>A frame is a hitch when its wall time is strictly over {@code max(2 x the median of the previous}
 * {@link #WINDOW} {@code frames, target + 4 ms)}, and no frame is judged before {@link #MIN_HISTORY} frames exist.
 * This is Unreal's hitch test ({@code FPerformanceTrackingSystem::AnalyzeFrame}, ChartCreation.cpp) with the previous
 * frame replaced by the median, so two slow frames in a row are both found, and without its debounce. A run that stays
 * slow stops hitching once the median catches up.</p>
 *
 * <p>Causes come from the trace's own evidence, never a guess. A stopping cause (pause, safepoint, class load) leads
 * only when its overlap is at least a tenth of the frame's excess over its median; below that it is context behind the
 * untimed causes. Easy to get wrong: compilations run beside the frame thread, so they are listed last and never explain
 * a hitch alone.</p>
 */
public final class CgHitches {

    /** Frames of history the rolling median is taken over. */
    public static final int WINDOW = 120;
    /** Frames needed before one is judged. */
    public static final int MIN_HISTORY = 20;
    /** What the target is lifted by to make the absolute floor. */
    public static final long MARGIN_NANOS = 4_000_000L;
    /** A frame thread allocation this large is a burst. */
    public static final long ALLOCATION_BURST_BYTES = 8L << 20;

    private static final String[] WAIT_SUFFIXES = {".compile-waits", ".sync-loads"};

    private CgHitches() {
    }

    /** The wall time a frame must strictly exceed to be a hitch. */
    public static long thresholdNanos(long medianNanos, long targetNanos) {
        return Math.max(2L * medianNanos, targetNanos + MARGIN_NANOS);
    }

    /**
     * The {@code permille}/1000 percentile by nearest rank: the value at 1-based rank
     * {@code ceil(permille x n / 1000)} of the sorted values, in integers. {@code values} is not changed.
     *
     * @return {@link CgFrameRecord#ABSENT} for no values
     * @throws IllegalArgumentException unless {@code permille} is in 1..1000
     */
    public static long percentile(long[] values, int permille) {
        if (permille < 1 || permille > 1000) throw new IllegalArgumentException("permille must be 1..1000: " + permille);
        if (values.length == 0) return CgFrameRecord.ABSENT;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        long rank = ((long) permille * sorted.length + 999L) / 1000L;
        return sorted[(int) (rank - 1L)];
    }

    /** The hitches of {@code snapshot}, oldest first, each with its causes. */
    public static List<CgHitch> find(CgTraceSnapshot snapshot, long targetNanos) {
        List<CgFrameRecord> frames = snapshot.frames();
        Thread recorder = CgTrace.frameThread();
        String frameThread = recorder == null ? null : recorder.getName();
        List<CgHitch> out = new ArrayList<>();
        long[] window = new long[WINDOW];
        for (int i = MIN_HISTORY; i < frames.size(); i++) {
            int from = Math.max(0, i - WINDOW);
            int n = i - from;
            for (int k = 0; k < n; k++) window[k] = frames.get(from + k).wallNanos();
            Arrays.sort(window, 0, n);
            long median = window[n / 2];
            CgFrameRecord frame = frames.get(i);
            if (frame.wallNanos() <= thresholdNanos(median, targetNanos)) continue;

            long grouped = 0L;
            if (frameThread != null) {
                for (CgTraceSnapshot.AllocationView view : snapshot.allocationsIn(frame)) {
                    if (view.thread().equals(frameThread)) grouped += view.bytes();
                }
            }
            out.add(new CgHitch(frame, median, boundOf(frame), causesOf(frame, median,
                    snapshot.gcPausesOverlapping(frame), snapshot.jvmEventsOverlapping(frame),
                    snapshot.countersIn(frame), grouped)));
        }
        return out;
    }

    static CgHitchBound boundOf(CgFrameRecord frame) {
        if (frame.hasCpu() && frame.hasGpu()) {
            return frame.gpuNanos() > frame.cpuNanos() ? CgHitchBound.GPU : CgHitchBound.FRAME_THREAD;
        }
        if (frame.hasCpu()) return CgHitchBound.FRAME_THREAD;
        return frame.hasGpu() ? CgHitchBound.GPU : CgHitchBound.UNKNOWN;
    }

    /**
     * The causes in a frame, ranked: stopping causes at or over the floor by overlap, then {@code ENGINE_WAIT},
     * {@code ALLOCATION}, {@code DEOPTIMISATION}, then stopping causes under the floor by overlap, then
     * {@code COMPILATION}. One entry per kind; ties by kind order.
     *
     * @param medianNanos  the median the frame was judged against; the floor is a tenth of {@code wall - median}
     * @param pauses       collections that may overlap the frame; one outside it counts for nothing
     * @param jvmEvents    likewise for the JVM's events; a deoptimisation counts when it starts inside the frame
     * @param counters     the frame's counters
     * @param groupedBytes frame-thread bytes allocated inside named allocation groups
     */
    public static List<CgHitch.Cause> causesOf(CgFrameRecord frame, long medianNanos,
                                               List<CgTraceSnapshot.GcPauseView> pauses,
                                               List<CgTraceSnapshot.JvmEventView> jvmEvents,
                                               List<CgTraceSnapshot.CounterView> counters, long groupedBytes) {
        long excess = Math.max(0L, frame.wallNanos() - medianNanos);
        long[] overlap = new long[CgHitchCause.values().length];
        int[] count = new int[overlap.length];
        String[] lead = new String[overlap.length];
        long[] leadOverlap = new long[overlap.length];

        for (CgTraceSnapshot.GcPauseView pause : pauses) {
            long part = overlapOf(frame, pause.startNanos(), pause.durationNanos());
            if (part <= 0L) continue;
            note(CgHitchCause.GC_PAUSE, part, pause.collector() + " " + pause.action() + " cause " + pause.cause(),
                    overlap, count, lead, leadOverlap);
        }
        for (CgTraceSnapshot.JvmEventView event : jvmEvents) {
            CgHitchCause kind = switch (event.kind()) {
                case SAFEPOINT -> CgHitchCause.SAFEPOINT;
                case CLASS_LOAD -> CgHitchCause.CLASS_LOAD;
                case COMPILATION -> CgHitchCause.COMPILATION;
                case DEOPTIMIZATION -> CgHitchCause.DEOPTIMISATION;
            };
            long part;
            if (kind == CgHitchCause.DEOPTIMISATION) {
                if (!frame.contains(event.startNanos())) continue;
                part = 0L;
            } else {
                part = overlapOf(frame, event.startNanos(), event.durationNanos());
                if (part <= 0L) continue;
            }
            String what = event.subject().isEmpty() ? event.detail() : event.subject() + (event.detail().isEmpty() ? "" : " " + event.detail());
            note(kind, part, what, overlap, count, lead, leadOverlap);
        }
        // Threads overlap each other: a kind's total is capped at the frame, never more time than it had.
        for (int i = 0; i < overlap.length; i++) overlap[i] = Math.min(overlap[i], frame.wallNanos());
        StringBuilder waits = new StringBuilder();
        for (CgTraceSnapshot.CounterView counter : counters) {
            if (counter.value() <= 0L || !isWait(counter.name())) continue;
            if (waits.length() > 0) waits.append(", ");
            waits.append(counter.name()).append(' ').append(counter.value());
        }
        List<CgHitch.Cause> passing = new ArrayList<>();
        List<CgHitch.Cause> untimed = new ArrayList<>();
        List<CgHitch.Cause> under = new ArrayList<>();
        List<CgHitch.Cause> compilations = new ArrayList<>();
        for (CgHitchCause kind : new CgHitchCause[] {CgHitchCause.GC_PAUSE, CgHitchCause.SAFEPOINT, CgHitchCause.CLASS_LOAD}) {
            int at = kind.ordinal();
            if (count[at] == 0) continue;
            CgHitch.Cause cause = new CgHitch.Cause(kind, overlap[at], false, describe(lead[at], count[at]));
            (overlap[at] * 10L >= excess ? passing : under).add(cause);
        }
        passing.sort((a, b) -> Long.compare(b.overlapNanos(), a.overlapNanos()));
        under.sort((a, b) -> Long.compare(b.overlapNanos(), a.overlapNanos()));
        if (waits.length() > 0) untimed.add(new CgHitch.Cause(CgHitchCause.ENGINE_WAIT, 0L, true, waits.toString()));
        if (frame.hasAllocation() && frame.allocatedBytes() >= ALLOCATION_BURST_BYTES) {
            untimed.add(new CgHitch.Cause(CgHitchCause.ALLOCATION, 0L, groupedBytes >= ALLOCATION_BURST_BYTES,
                    String.format(Locale.ROOT, "%.1f MiB on the frame thread, %.1f MiB of it in named groups",
                            frame.allocatedBytes() / 1048576d, groupedBytes / 1048576d)));
        }
        if (count[CgHitchCause.DEOPTIMISATION.ordinal()] > 0) {
            int at = CgHitchCause.DEOPTIMISATION.ordinal();
            untimed.add(new CgHitch.Cause(CgHitchCause.DEOPTIMISATION, 0L, false, describe(lead[at], count[at])));
        }
        int jit = CgHitchCause.COMPILATION.ordinal();
        if (count[jit] > 0) {
            compilations.add(new CgHitch.Cause(CgHitchCause.COMPILATION, overlap[jit], false, describe(lead[jit], count[jit])));
        }
        List<CgHitch.Cause> out = new ArrayList<>(passing);
        out.addAll(untimed);
        out.addAll(under);
        out.addAll(compilations);
        return out;
    }

    private static boolean isWait(String counter) {
        for (String suffix : WAIT_SUFFIXES) if (counter.endsWith(suffix)) return true;
        return false;
    }

    private static long overlapOf(CgFrameRecord frame, long start, long duration) {
        long from = Math.max(start, frame.beginNanos());
        long to = Math.min(start + duration, frame.endNanos());
        return Math.max(0L, to - from);
    }

    private static void note(CgHitchCause kind, long part, String what, long[] overlap, int[] count, String[] lead,
                             long[] leadOverlap) {
        int at = kind.ordinal();
        overlap[at] += part;
        count[at]++;
        if (lead[at] == null || part > leadOverlap[at]) {
            lead[at] = what;
            leadOverlap[at] = part;
        }
    }

    private static String describe(String lead, int count) {
        return count == 1 ? lead : lead + " (+" + (count - 1) + " more)";
    }
}
