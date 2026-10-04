package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The trace as text — <b>the surface an agent reads</b>, and a human in a terminal.
 *
 * <pre>{@code
 * String report  = CgTraceReport.of(CgTrace.snapshot()).breakdown();
 * String verdict = CgTraceReport.of(snapshot).verdict();
 * String one     = CgTraceReport.of(snapshot).frame(412);
 * String where   = CgTraceReport.of(snapshot).zone("paint:tree");
 * String delta   = CgTraceReport.of(snapshot).compare(0, 299, 300, 599);
 * }</pre>
 *
 * <h3>What a report says, in order</h3>
 *
 * <ol>
 *   <li><b>What the trace is</b> — frames, channels recording and NOT recording, whether every frame
 *       still holds its zones and counters. A partial trace cannot read as a complete one.</li>
 *   <li><b>The verdict and the frame times</b> — median, p90 and max of wall, CPU, idle (wall − CPU), GPU
 *       and unzoned time, each max with the frame it came from.</li>
 *   <li><b>Where the time goes, per frame</b> — the frame thread's phases, then every zone by its own
 *       body. Every figure is milliseconds <em>per frame</em>. Zones declared as waits
 *       ({@link CgTrace#waitName}) are listed apart and are in no cost table.</li>
 *   <li><b>What it cannot explain</b> — {@code unzoned} time and {@code GAP}s: zones whose children leave
 *       most of them unnamed. Missing instrumentation shows in the run that lacks it.</li>
 *   <li><b>Counters, markers, hints</b> — in full; nothing is truncated.</li>
 *   <li><b>Three frames in detail</b> — the slowest by CPU, the slowest by wall (when different) and a
 *       typical one, each labelled by why it was chosen.</li>
 * </ol>
 *
 * <p><b>Deterministic</b>, so two runs {@code diff}: sorted by cost then name, fixed precision, no clock
 * time anywhere. <b>Jumpable</b>: every zone ends in the {@code File.java:line} that named it, rewritten by
 * {@link #sources} when the caller knows where the sources live.</p>
 */
public final class CgTraceReport {

    /** How much of the trace a reader is asking for. */
    public enum Tier {
        /** About 25 lines: the header, the frame times, the top phases, counters and hints. */
        VERDICT,
        /** Every section, and the slowest, the idlest and a typical frame in detail. */
        BREAKDOWN,
        /** As the breakdown, unlimited, plus the five slowest frames and a line per frame. */
        FULL
    }

    private final CgTraceSnapshot snapshot;
    private double budgetMillis = 1000d / 60d;
    private Function<String, String> sources = Function.identity();
    private Analysis analysis;

    private CgTraceReport(CgTraceSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static CgTraceReport of(CgTraceSnapshot snapshot) {
        return new CgTraceReport(snapshot);
    }

    /** What counts as a missed frame. 60Hz unless a host says otherwise. */
    public CgTraceReport budget(double millis) {
        if (millis > 0d) budgetMillis = millis;
        return this;
    }

    /**
     * Rewrites every source location before it is printed — a harness maps
     * {@code com/x/Y.java:12} to the file's path in the repository.
     */
    public CgTraceReport sources(Function<String, String> resolve) {
        if (resolve != null) sources = resolve;
        return this;
    }

    // ── The queries ─────────────────────────────────────────────────────────────────────────

    public String verdict() {
        return render(Tier.VERDICT);
    }

    public String breakdown() {
        return render(Tier.BREAKDOWN);
    }

    /** The whole report at {@code tier}. */
    public String render(Tier tier) {
        StringBuilder out = new StringBuilder(8192);
        Analysis a = analysis();
        header(out, a);
        if (a.frames.size() < 2) {
            out.append("\nVERDICT   no frames recorded\n");
            return out.toString();
        }
        boolean full = tier == Tier.FULL;
        boolean verdictOnly = tier == Tier.VERDICT;
        verdict(out, a);
        perFrame(out, a);
        phases(out, a, verdictOnly ? 5 : full ? Integer.MAX_VALUE : 25);
        if (verdictOnly) {
            counterLine(out, a);
            hints(out, a, 3);
            return out.toString();
        }
        waits(out, a);
        selfTime(out, a, full ? Integer.MAX_VALUE : 15);
        counters(out, a);
        gpuGroups(out, a);
        markers(out, a, full ? Integer.MAX_VALUE : 8);
        hints(out, a, Integer.MAX_VALUE);
        slowest(out, a, full ? 10 : 5);

        int slowCpu = a.worstByCpu();
        int slowWall = a.worstByWall();
        frameBlock(out, a, slowCpu, "slowest by cpu", full ? 200 : 40);
        if (slowWall != slowCpu) frameBlock(out, a, slowWall, "slowest by wall", full ? 200 : 40);
        int typical = a.typical();
        if (typical != slowCpu && typical != slowWall) frameBlock(out, a, typical, "typical: the median cpu", full ? 200 : 40);
        if (full) {
            List<Integer> byCpu = a.byCpuDescending();
            for (int i = 1; i < Math.min(5, byCpu.size()); i++) {
                int at = byCpu.get(i);
                if (at != slowWall && at != typical) frameBlock(out, a, at, "slow by cpu #" + (i + 1), 200);
            }
            out.append("\nEVERY FRAME  wall / cpu / gpu ms, and the frame's largest GPU zone\n");
            for (int i = 0; i < a.frames.size(); i++) {
                CgFrameRecord frame = a.frames.get(i);
                out.append(String.format(Locale.ROOT, "  #%-7d %8.2f %8s %8s  %s%n", frame.index(), frame.wallMillis(),
                        frame.hasCpu() ? String.format(Locale.ROOT, "%.2f", frame.cpuMillis()) : "absent",
                        a.gpu[i] < 0 ? "absent" : String.format(Locale.ROOT, "%.2f", a.gpu[i] / 1e6),
                        largestGpuZone(a.countersByFrame.get(i))));
            }
        }
        return out.toString();
    }

    /** The frame's costliest {@code gpu:} counter as {@code name ms}, or empty when it has none. */
    private static String largestGpuZone(Map<String, Long> counters) {
        String name = null;
        long most = -1L;
        for (Map.Entry<String, Long> counter : counters.entrySet()) {
            if (counter.getKey().startsWith(CgGpuTrace.PREFIX) && counter.getValue() > most) {
                name = counter.getKey();
                most = counter.getValue();
            }
        }
        return name == null ? "" : String.format(Locale.ROOT, "%s %.2f", name, most / 1e6);
    }

    /** One frame in full — its tree, counters, markers and hints. */
    public String frame(long index) {
        StringBuilder out = new StringBuilder(2048);
        Analysis a = analysis();
        header(out, a);
        int at = a.positionOf(index);
        if (at < 0) {
            out.append("\nframe ").append(index).append(" is not in the ring\n");
            return out.toString();
        }
        frameBlock(out, a, at, "asked for", 200);
        return out.toString();
    }

    /** The {@code count} slowest frames by CPU, each in full. */
    public String worst(int count) {
        StringBuilder out = new StringBuilder(4096);
        Analysis a = analysis();
        header(out, a);
        List<Integer> byCpu = a.byCpuDescending();
        for (int i = 0; i < Math.min(Math.max(1, count), byCpu.size()); i++) {
            frameBlock(out, a, byCpu.get(i), "slow by cpu #" + (i + 1), 40);
        }
        return out.toString();
    }

    /** Every instance of one zone across the ring: how many, how long, how it is spread. */
    public String zone(String name) {
        StringBuilder out = new StringBuilder(512);
        Analysis a = analysis();
        header(out, a);
        Acc acc = a.zones.get(name);
        if (acc == null) {
            out.append("\nno zone named ").append(name).append(" in the ring\n");
            return out.toString();
        }
        double[] perFrame = acc.perFrameTotal(a.heldCount);
        out.append(String.format(Locale.ROOT,
                "%n%s%s%n  count %d   total %.2fms   self %.2fms   per frame: mean %.3fms  p90 %.3fms  max %.3fms"
                        + "   calls/frame %.1f%n  %s%n",
                name, CgTrace.isWait(name) ? "  (a wait)" : "", acc.calls, acc.total / 1e6d, acc.self / 1e6d,
                mean(perFrame), pct(perFrame, 0.9d), max(perFrame), acc.calls / (double) Math.max(1, a.heldCount),
                src(acc.source)));
        return out.toString();
    }

    /**
     * Two frame ranges of ONE ring, per zone, in ms per frame, sorted by the size of the change — what an
     * agent runs after an edit. The same process and JIT state, which is what makes it honest.
     */
    public String compare(long fromA, long toA, long fromB, long toB) {
        Map<String, double[]> before = perFrameOver(fromA, toA);
        Map<String, double[]> after = perFrameOver(fromB, toB);
        StringBuilder out = new StringBuilder(2048);
        header(out, analysis());
        out.append(String.format(Locale.ROOT, "%nCOMPARE   ms per frame, frames %d..%d (before) against %d..%d (after); waits left out%n",
                fromA, toA, fromB, toB));
        List<String> names = new ArrayList<>(before.keySet());
        for (String name : after.keySet()) if (!names.contains(name)) names.add(name);
        List<Object[]> rows = new ArrayList<>();
        for (String name : names) {
            double b = before.containsKey(name) ? before.get(name)[0] : 0d;
            double f = after.containsKey(name) ? after.get(name)[0] : 0d;
            rows.add(new Object[] {name, b, f, f - b, sourceOf(name)});
        }
        rows.sort((x, y) -> {
            int byDelta = Double.compare(Math.abs((Double) y[3]), Math.abs((Double) x[3]));
            return byDelta != 0 ? byDelta : ((String) x[0]).compareTo((String) y[0]);
        });
        int width = "zone".length();
        for (int i = 0; i < Math.min(25, rows.size()); i++) width = Math.max(width, ((String) rows.get(i)[0]).length());
        out.append(String.format(Locale.ROOT, "  %-" + width + "s %10s %10s %10s   %s%n", "zone", "before", "after", "delta", "source"));
        for (int i = 0; i < Math.min(25, rows.size()); i++) {
            Object[] row = rows.get(i);
            out.append(String.format(Locale.ROOT, "  %-" + width + "s %10.3f %10.3f %+10.3f   %s%n",
                    row[0], row[1], row[2], row[3], src((String) row[4])));
        }
        return out.toString();
    }

    /** {@link #compare} filtered to what got worse — the one an agent wants after an edit. */
    public String regressions(long fromA, long toA, long fromB, long toB) {
        String all = compare(fromA, toA, fromB, toB);
        StringBuilder out = new StringBuilder(all.length());
        boolean table = false;
        for (String line : all.split("\n", -1)) {
            if (line.startsWith("  zone")) table = true;
            if (!table || line.startsWith("  zone") || line.contains(" +")) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Total ms per frame for each zone over the frames in {@code [from, to]}; waits left out. */
    private Map<String, double[]> perFrameOver(long from, long to) {
        Analysis a = analysis();
        Map<String, double[]> out = new LinkedHashMap<>();
        int frames = 0;
        for (int i = 0; i < a.frames.size(); i++) {
            long index = a.frames.get(i).index();
            if (index < from || index > to || !a.held[i]) continue;
            frames++;
            for (CgTraceAggregate.Stat stat : CgTraceAggregate.byCost(a.zonesByFrame.get(i))) {
                if (CgTrace.isWait(stat.name())) continue;
                out.computeIfAbsent(stat.name(), k -> new double[1])[0] += stat.totalNanos() / 1e6d;
            }
        }
        if (frames > 0) for (double[] each : out.values()) each[0] /= frames;
        return out;
    }

    // ── The analysis, done once ─────────────────────────────────────────────────────────────

    private Analysis analysis() {
        if (analysis == null) analysis = new Analysis(snapshot);
        return analysis;
    }

    /** One zone name, rolled up over the frames that still hold their zones. */
    private static final class Acc {
        String source;
        long total;
        long self;
        int calls;
        boolean parent;
        /** Per held frame, by position in {@link Analysis#heldOrder}. */
        long[] frameTotal;
        long[] frameSelf;

        void add(int heldPosition, int heldCount, long duration, long selfNanos, String where, boolean hasChildren) {
            if (frameTotal == null) {
                frameTotal = new long[heldCount];
                frameSelf = new long[heldCount];
            }
            if (source == null) source = where;
            total += duration;
            self += selfNanos;
            calls++;
            parent |= hasChildren;
            frameTotal[heldPosition] += duration;
            frameSelf[heldPosition] += selfNanos;
        }

        double[] perFrameTotal(int heldCount) {
            return millis(frameTotal, heldCount);
        }

        double[] perFrameSelf(int heldCount) {
            return millis(frameSelf, heldCount);
        }

        private static double[] millis(long[] nanos, int heldCount) {
            double[] out = new double[heldCount];
            if (nanos != null) for (int i = 0; i < heldCount; i++) out[i] = nanos[i] / 1e6d;
            return out;
        }
    }

    /** Per-frame values of one counter: the frame's sum, and how many values made it. */
    private static final class CounterAcc {
        final List<Long> sums = new ArrayList<>();
        final List<Integer> writes = new ArrayList<>();
        long firstFrame = -1L;
    }

    private final class Analysis {
        final List<CgFrameRecord> frames;
        final List<List<CgTraceSnapshot.ZoneView>> zonesByFrame = new ArrayList<>();
        final List<List<CgTraceAggregate.Node>> trees = new ArrayList<>();
        final List<Map<String, Long>> countersByFrame = new ArrayList<>();
        final List<Map<String, Integer>> counterWrites = new ArrayList<>();
        final List<List<CgTraceSnapshot.MarkerView>> markersByFrame = new ArrayList<>();
        final boolean[] held;
        final boolean[] countersHeld;
        final int heldCount;
        final List<Integer> heldOrder = new ArrayList<>();
        final String frameThread;
        final long[] unzoned;
        final long[] gpu;
        /** Frame-thread zone time after the frame's cpu ended: the part of idle something named. */
        final long[] idleCovered;
        final Map<String, Acc> zones = new LinkedHashMap<>();
        final Map<String, Acc> phases = new LinkedHashMap<>();
        final Map<String, Acc> waits = new LinkedHashMap<>();
        final Map<String, CounterAcc> counters = new LinkedHashMap<>();
        final Map<String, List<Object[]>> hintsByCode = new LinkedHashMap<>();
        final Map<Long, Integer> positionByIndex = new HashMap<>();

        Analysis(CgTraceSnapshot snapshot) {
            frames = snapshot.frames();
            int n = frames.size();
            for (int i = 0; i < n; i++) {
                positionByIndex.put(frames.get(i).index(), i);
                zonesByFrame.add(new ArrayList<>());
                countersByFrame.add(new LinkedHashMap<>());
                counterWrites.add(new LinkedHashMap<>());
                markersByFrame.add(new ArrayList<>());
            }
            // ZONES BY FRAME IN ONE PASS: both lists are in time order, so a walking pointer replaces a
            // scan of every zone per frame -- which at 300 frames of a dense channel was minutes.
            int f = 0;
            for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
                if (zone.isOpen()) continue;
                while (f < n && frames.get(f).endNanos() <= zone.startNanos()) f++;
                if (f >= n) break;
                if (frames.get(f).contains(zone.startNanos())) zonesByFrame.get(f).add(zone);
            }
            List<CgTraceSnapshot.MarkerView> markers = new ArrayList<>(snapshot.markers());
            markers.sort(Comparator.comparingLong(CgTraceSnapshot.MarkerView::nanos));
            f = 0;
            for (CgTraceSnapshot.MarkerView marker : markers) {
                while (f < n && frames.get(f).endNanos() <= marker.nanos()) f++;
                if (f >= n) break;
                if (frames.get(f).contains(marker.nanos())) markersByFrame.get(f).add(marker);
            }
            long countersFrom = Long.MAX_VALUE;
            for (CgTraceSnapshot.CounterView counter : snapshot.counters()) {
                Integer at = positionByIndex.get(counter.frameIndex());
                if (at == null) continue;
                countersFrom = Math.min(countersFrom, counter.frameIndex());
                countersByFrame.get(at).merge(counter.name(), counter.value(), Long::sum);
                counterWrites.get(at).merge(counter.name(), 1, Integer::sum);
            }

            frameThread = frameThreadOf(snapshot.zones());
            long heldFrom = Long.MAX_VALUE;
            for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
                if (zone.thread().equals(frameThread)) heldFrom = Math.min(heldFrom, zone.startNanos());
            }
            held = new boolean[n];
            countersHeld = new boolean[n];
            unzoned = new long[n];
            gpu = new long[n];
            idleCovered = new long[n];
            Arrays.fill(unzoned, -1L);
            int count = 0;
            for (int i = 0; i < n; i++) {
                CgFrameRecord frame = frames.get(i);
                held[i] = heldFrom == Long.MAX_VALUE || frame.endNanos() > heldFrom;
                countersHeld[i] = countersFrom == Long.MAX_VALUE || frame.index() >= countersFrom;
                gpu[i] = frame.hasGpu() ? frame.gpuNanos() : -1L;
                List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zonesByFrame.get(i));
                trees.add(roots);
                idleCovered[i] = idleCoveredOf(frame, roots);
                if (!held[i]) continue;
                count++;
                heldOrder.add(i);
                if (!roots.isEmpty()) unzoned[i] = unzonedOf(frame, roots);
            }
            heldCount = count;
            for (int position = 0; position < heldOrder.size(); position++) {
                int i = heldOrder.get(position);
                for (CgTraceAggregate.Node root : trees.get(i)) {
                    boolean onFrameThread = root.thread().equals(frameThread);
                    if (onFrameThread && !CgTrace.isWait(root.name())) {
                        phases.computeIfAbsent(root.name(), k -> new Acc()).add(position, heldCount,
                                root.durationNanos(), root.selfNanos(), root.source(), !root.children().isEmpty());
                    }
                    walk(root, position, onFrameThread);
                }
                CgFrameRecord frame = frames.get(i);
                for (CgTraceHints.Hint hint : CgTraceHints.forFrame(frame, trees.get(i), countersByFrame.get(i))) {
                    hintsByCode.computeIfAbsent(hint.code(), k -> new ArrayList<>())
                            .add(new Object[] {frame.index(), hint});
                }
            }
            for (int i = 0; i < n; i++) {
                for (Map.Entry<String, Long> each : countersByFrame.get(i).entrySet()) {
                    CounterAcc acc = counters.computeIfAbsent(each.getKey(), k -> new CounterAcc());
                    acc.sums.add(each.getValue());
                    acc.writes.add(counterWrites.get(i).get(each.getKey()));
                    if (acc.firstFrame < 0L) acc.firstFrame = frames.get(i).index();
                }
            }
        }

        private void walk(CgTraceAggregate.Node node, int position, boolean onFrameThread) {
            Map<String, Acc> into = CgTrace.isWait(node.name()) ? (onFrameThread ? waits : null) : zones;
            if (into != null) {
                into.computeIfAbsent(node.name(), k -> new Acc()).add(position, heldCount,
                        node.durationNanos(), node.selfNanos(), node.source(), !node.children().isEmpty());
            }
            for (CgTraceAggregate.Node child : node.children()) walk(child, position, onFrameThread);
        }

        /** Frame-thread CPU time no zone covers: the roots, clipped to the frame's CPU window. */
        private long unzonedOf(CgFrameRecord frame, List<CgTraceAggregate.Node> roots) {
            long from = frame.beginNanos();
            long to = frame.hasCpu() ? from + frame.cpuNanos() : frame.endNanos();
            long covered = 0L;
            for (CgTraceAggregate.Node root : roots) {
                if (!root.thread().equals(frameThread)) continue;
                long start = Math.max(from, root.startNanos());
                long end = Math.min(to, root.endNanos());
                if (end > start) covered += end - start;
            }
            return Math.max(0L, (to - from) - covered);
        }

        /** Frame-thread time between the cpu mark and the frame's end that a root zone covers. */
        private long idleCoveredOf(CgFrameRecord frame, List<CgTraceAggregate.Node> roots) {
            if (!frame.hasCpu()) return 0L;
            long from = frame.beginNanos() + frame.cpuNanos();
            long covered = 0L;
            for (CgTraceAggregate.Node root : roots) {
                if (!root.thread().equals(frameThread)) continue;
                long start = Math.max(from, root.startNanos());
                long end = Math.min(frame.endNanos(), root.endNanos());
                if (end > start) covered += end - start;
            }
            return covered;
        }

        int positionOf(long index) {
            Integer at = positionByIndex.get(index);
            return at == null ? -1 : at;
        }

        int worstByCpu() {
            List<Integer> order = byCpuDescending();
            for (int at : order) if (held[at]) return at;
            return order.get(0);
        }

        int worstByWall() {
            int worst = 0;
            for (int i = 1; i < frames.size(); i++) {
                if (frames.get(i).wallNanos() > frames.get(worst).wallNanos()) worst = i;
            }
            return worst;
        }

        /** The held frame whose CPU is the median — what a steady-state question is about. */
        int typical() {
            List<Integer> order = new ArrayList<>(heldOrder.isEmpty() ? allPositions() : heldOrder);
            order.sort(Comparator.comparingLong((Integer i) -> cpuOrWall(frames.get(i))).thenComparing(i -> i));
            return order.get(order.size() / 2);
        }

        List<Integer> byCpuDescending() {
            List<Integer> order = allPositions();
            order.sort(Comparator.comparingLong((Integer i) -> cpuOrWall(frames.get(i))).reversed()
                    .thenComparing(i -> i));
            return order;
        }

        private List<Integer> allPositions() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) out.add(i);
            return out;
        }
    }

    private static long cpuOrWall(CgFrameRecord frame) {
        return frame.hasCpu() ? frame.cpuNanos() : frame.wallNanos();
    }

    /** The recorder's thread, else the one that holds the most zone time. */
    private static String frameThreadOf(List<CgTraceSnapshot.ZoneView> zones) {
        Thread recorder = CgTrace.frameThread();
        Map<String, Long> byThread = new LinkedHashMap<>();
        for (CgTraceSnapshot.ZoneView zone : zones) {
            if (recorder != null && zone.thread().equals(recorder.getName())) return recorder.getName();
            byThread.merge(zone.thread(), zone.durationNanos(), Long::sum);
        }
        String best = recorder == null ? "" : recorder.getName();
        long most = -1L;
        for (Map.Entry<String, Long> each : byThread.entrySet()) {
            if (each.getValue() > most) {
                most = each.getValue();
                best = each.getKey();
            }
        }
        return best;
    }

    // ── Sections ────────────────────────────────────────────────────────────────────────────

    /** What this trace IS — so a partial one cannot be read as a complete one. */
    private void header(StringBuilder out, Analysis a) {
        List<CgFrameRecord> frames = a.frames;
        double span = frames.size() < 2 ? 0d
                : (frames.get(frames.size() - 1).endNanos() - frames.get(0).beginNanos()) / 1e9d;
        out.append(String.format(Locale.ROOT, "PROFILE   %d frames%s over %.2fs, frame thread '%s'%n", frames.size(),
                frames.isEmpty() ? "" : ", #" + frames.get(0).index() + "-#" + frames.get(frames.size() - 1).index(),
                span, a.frameThread));
        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (CgTraceChannel channel : CgTrace.channels()) (channel.isEnabled() ? on : off).add(channel.name());
        out.append("channels  recording: ").append(on.isEmpty() ? "none" : String.join(", ", on));
        if (!off.isEmpty()) out.append("  |  NOT recording: ").append(String.join(", ", off));
        out.append('\n');
        int countersMissing = 0;
        for (boolean each : a.countersHeld) if (!each) countersMissing++;
        out.append(String.format(Locale.ROOT, "data      zones held for %d/%d frames, counters for %d/%d, %d zones dropped%n",
                a.heldCount, frames.size(), frames.size() - countersMissing, frames.size(), snapshot.droppedZones()));
        if (snapshot.droppedZones() > 0) {
            out.append("WARNING   zones were dropped: an arena overflowed, so a short frame may just be a truncated one."
                    + " Raise -Dcrystalgraphics.trace.zones\n");
        }
        int overwritten = frames.size() - a.heldCount;
        if (overwritten > 0) {
            long firstHeld = a.heldOrder.isEmpty() ? -1L : frames.get(a.heldOrder.get(0)).index();
            out.append("WARNING   ").append(overwritten).append(" of ").append(frames.size())
                    .append(" frames hold no zones -- the arena wrapped, or they came before the channels were on")
                    .append(firstHeld < 0L ? "" : "; zones are held from frame " + firstHeld)
                    .append(". Every table below covers only the frames that hold them."
                            + " Raise -Dcrystalgraphics.trace.zones, or CgTrace.configure(first, newest, zonesPerFrame)\n");
        }
        if (countersMissing > 0) {
            out.append("WARNING   ").append(countersMissing).append(" of ").append(frames.size())
                    .append(" frames hold no counters -- the counter ring wrapped, or they came before the channels"
                            + " were on. Raise -Dcrystalgraphics.trace.countersPerFrame\n");
        }
    }

    private void verdict(StringBuilder out, Analysis a) {
        double[] wall = new double[a.frames.size()];
        int overWall = 0;
        int overCpu = 0;
        for (int i = 0; i < wall.length; i++) {
            CgFrameRecord frame = a.frames.get(i);
            wall[i] = frame.wallMillis();
            if (frame.wallMillis() > budgetMillis) overWall++;
            if (frame.hasCpu() && frame.cpuMillis() > budgetMillis) overCpu++;
        }
        double median = pct(wall, 0.5d);
        out.append(String.format(Locale.ROOT, "%nVERDICT   %.0f fps median. Over the %.1fms budget: %d/%d frames by wall, %d by cpu.%n",
                median <= 0d ? 0d : 1000d / median, budgetMillis, overWall, wall.length, overCpu));
        int slowCpu = a.worstByCpu();
        int slowWall = a.worstByWall();
        CgFrameRecord cpu = a.frames.get(slowCpu);
        CgFrameRecord wallFrame = a.frames.get(slowWall);
        out.append(String.format(Locale.ROOT, "          Slowest by cpu #%d (%s cpu); slowest by wall #%d (%.2fms wall, %s cpu).%n",
                cpu.index(), cpu.hasCpu() ? String.format(Locale.ROOT, "%.2fms", cpu.cpuMillis()) : "absent",
                wallFrame.index(), wallFrame.wallMillis(),
                wallFrame.hasCpu() ? String.format(Locale.ROOT, "%.2fms", wallFrame.cpuMillis()) : "absent"));
        idleCulprit(out, a);
    }

    /**
     * For the frames over budget by wall and not by cpu: what filled their idle window, by name — a zone
     * that ran after the cpu mark, a wait, or nothing named at all.
     */
    private void idleCulprit(StringBuilder out, Analysis a) {
        Map<String, List<Long>> byName = new LinkedHashMap<>();
        int frames = 0;
        for (int i = 0; i < a.frames.size(); i++) {
            CgFrameRecord frame = a.frames.get(i);
            if (!frame.hasCpu() || !a.held[i] || frame.wallMillis() <= budgetMillis || frame.cpuMillis() > budgetMillis) continue;
            frames++;
            long mark = frame.beginNanos() + frame.cpuNanos();
            Map<String, Long> here = new LinkedHashMap<>();
            for (CgTraceAggregate.Node root : a.trees.get(i)) {
                if (!root.thread().equals(a.frameThread)) continue;
                long start = Math.max(mark, root.startNanos());
                long end = Math.min(frame.endNanos(), root.endNanos());
                if (end > start) here.merge(root.name(), end - start, Long::sum);
            }
            here.merge(UNEXPLAINED, Math.max(0L, frame.wallNanos() - frame.cpuNanos() - a.idleCovered[i]), Long::sum);
            for (Map.Entry<String, Long> each : here.entrySet()) {
                byName.computeIfAbsent(each.getKey(), k -> new ArrayList<>()).add(each.getValue());
            }
        }
        if (frames == 0) return;
        // BY SHARE AND MAX, never a median: a stall in three frames of sixteen has a median of nothing.
        long all = 0L;
        List<Object[]> ranked = new ArrayList<>();
        for (Map.Entry<String, List<Long>> each : byName.entrySet()) {
            long sum = 0L;
            long most = 0L;
            for (long v : each.getValue()) {
                sum += v;
                most = Math.max(most, v);
            }
            all += sum;
            ranked.add(new Object[] {each.getKey(), sum, most});
        }
        ranked.sort(Comparator.comparingLong((Object[] r) -> (Long) r[1]).reversed().thenComparing(r -> (String) r[0]));
        List<String> items = new ArrayList<>();
        for (int i = 0; i < Math.min(3, ranked.size()); i++) {
            String name = (String) ranked.get(i)[0];
            long sum = (Long) ranked.get(i)[1];
            if (i > 0 && sum * 10 < all) break;
            String kind = name.equals(UNEXPLAINED) ? "no zone, " : CgTrace.isWait(name) ? "a wait, " : "after the cpu mark, ";
            items.add(String.format(Locale.ROOT, "%s (%s%.0f%%, max %.2fms)", name, kind,
                    all == 0L ? 0d : sum * 100d / all, (Long) ranked.get(i)[2] / 1e6d));
        }
        out.append(String.format(Locale.ROOT, "          Over by wall alone: %d frames. Their idle: %s.%n", frames, String.join(", ", items)));
    }

    private static final String UNEXPLAINED = "(unexplained)";

    /** Median, p90 and max of each per-frame figure, and the frame each max came from. */
    private void perFrame(StringBuilder out, Analysis a) {
        out.append("\nPER FRAME  ms: median, p90, max and the frame of the max. idle = wall - cpu; unexplained = idle no\n")
                .append("  zone covers -- zone the host's loop until it is ~0, then what is left is the OS or the driver\n");
        out.append(String.format(Locale.ROOT, "  %-18s %9s %9s %9s  %s%n", "", "median", "p90", "max", "at"));
        int n = a.frames.size();
        double[] wall = new double[n];
        List<Double> cpu = new ArrayList<>();
        List<Double> idle = new ArrayList<>();
        List<Double> unexplained = new ArrayList<>();
        List<Double> gpu = new ArrayList<>();
        List<Double> unzoned = new ArrayList<>();
        long[] cpuAt = {-1, 0}, idleAt = {-1, 0}, gpuAt = {-1, 0}, unzonedAt = {-1, 0}, wallAt = {-1, 0},
                unexplainedAt = {-1, 0};
        for (int i = 0; i < n; i++) {
            CgFrameRecord frame = a.frames.get(i);
            wall[i] = frame.wallMillis();
            track(wallAt, frame.index(), frame.wallNanos());
            if (frame.hasCpu()) {
                cpu.add(frame.cpuMillis());
                track(cpuAt, frame.index(), frame.cpuNanos());
                long idleNanos = Math.max(0L, frame.wallNanos() - frame.cpuNanos());
                idle.add(idleNanos / 1e6d);
                track(idleAt, frame.index(), idleNanos);
                long other = Math.max(0L, idleNanos - a.idleCovered[i]);
                unexplained.add(other / 1e6d);
                track(unexplainedAt, frame.index(), other);
            }
            if (a.gpu[i] >= 0L) {
                gpu.add(a.gpu[i] / 1e6d);
                track(gpuAt, frame.index(), a.gpu[i]);
            }
            if (a.unzoned[i] >= 0L) {
                unzoned.add(a.unzoned[i] / 1e6d);
                track(unzonedAt, frame.index(), a.unzoned[i]);
            }
        }
        row(out, "wall", wall, wallAt);
        row(out, "cpu", toArray(cpu), cpuAt);
        row(out, "idle (wall - cpu)", toArray(idle), idleAt);
        row(out, "  unexplained", toArray(unexplained), unexplainedAt);
        row(out, "gpu", toArray(gpu), gpuAt);
        row(out, "unzoned (of cpu)", toArray(unzoned), unzonedAt);
    }

    private static void track(long[] at, long index, long nanos) {
        if (at[0] < 0L || nanos > at[1]) {
            at[0] = index;
            at[1] = nanos;
        }
    }

    private static void row(StringBuilder out, String label, double[] values, long[] at) {
        if (values.length == 0) {
            out.append(String.format(Locale.ROOT, "  %-18s %9s%n", label, "absent"));
            return;
        }
        out.append(String.format(Locale.ROOT, "  %-18s %9.2f %9.2f %9.2f  #%d%n",
                label, pct(values, 0.5d), pct(values, 0.9d), max(values), at[0]));
    }

    /** The frame thread's top-level zones — the frame's phases — in ms per frame. */
    private void phases(StringBuilder out, Analysis a, int limit) {
        out.append("\nPHASES  the frame thread's top-level zones, ms per frame over ").append(a.heldCount)
                .append(" frames; a frame without one counts 0\n");
        table(out, a, a.phases, limit, false);
    }

    private void waits(StringBuilder out, Analysis a) {
        if (a.waits.isEmpty()) return;
        out.append("\nWAITS  zones declared as waiting (a sleep holding the rate, a fence, a swap) -- never work, in no cost table\n");
        table(out, a, a.waits, Integer.MAX_VALUE, false);
    }

    /** Every zone by its own body — where the time no child names is. */
    private void selfTime(StringBuilder out, Analysis a, int limit) {
        out.append("\nSELF TIME  every zone by its own body, ms per frame, all threads. kind: GAP = its children leave\n")
                .append("  over 20% of it unnamed, so instrument inside it; leaf = no children, split it if it is large;\n")
                .append("  - = its children hold it, so read them\n");
        table(out, a, a.zones, limit, true);
    }

    private void table(StringBuilder out, Analysis a, Map<String, Acc> accs, int limit, boolean bySelf) {
        List<Map.Entry<String, Acc>> rows = new ArrayList<>(accs.entrySet());
        rows.sort((x, y) -> {
            long left = bySelf ? x.getValue().self : x.getValue().total;
            long right = bySelf ? y.getValue().self : y.getValue().total;
            int byCost = Long.compare(right, left);
            return byCost != 0 ? byCost : x.getKey().compareTo(y.getKey());
        });
        // ROWS TOO SMALL TO ACT ON are counted, not listed: under a microsecond-scale mean a row is noise.
        int tiny = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            Acc acc = rows.get(i).getValue();
            long nanos = bySelf ? acc.self : acc.total;
            if (nanos / 1e6d / Math.max(1, a.heldCount) < TINY_MS_PER_FRAME) {
                rows.remove(i);
                tiny++;
            }
        }
        int shown = Math.min(limit, rows.size());
        int width = "zone".length();
        for (int i = 0; i < shown; i++) width = Math.max(width, rows.get(i).getKey().length());
        if (bySelf) {
            out.append(String.format(Locale.ROOT, "  %-" + width + "s %9s %9s %9s %6s %5s %10s   %s%n",
                    "zone", "self", "p90", "max", "self%", "kind", "calls/fr", "source"));
        } else {
            out.append(String.format(Locale.ROOT, "  %-" + width + "s %9s %9s %9s %10s   %s%n",
                    "zone", "mean", "p90", "max", "calls/fr", "source"));
        }
        for (int i = 0; i < shown; i++) {
            String name = rows.get(i).getKey();
            Acc acc = rows.get(i).getValue();
            double[] values = bySelf ? acc.perFrameSelf(a.heldCount) : acc.perFrameTotal(a.heldCount);
            double calls = acc.calls / (double) Math.max(1, a.heldCount);
            if (bySelf) {
                String kind = !acc.parent || acc.self >= acc.total * LEAF_SHARE ? "leaf"
                        : acc.self >= acc.total * GAP_SHARE ? "GAP" : "-";
                out.append(String.format(Locale.ROOT, "  %-" + width + "s %9.3f %9.3f %9.3f %5.0f%% %5s %10.1f   %s%n",
                        name, mean(values), pct(values, 0.9d), max(values),
                        acc.total <= 0L ? 0d : acc.self * 100d / acc.total, kind, calls, src(acc.source)));
            } else {
                out.append(String.format(Locale.ROOT, "  %-" + width + "s %9.3f %9.3f %9.3f %10.1f   %s%n",
                        name, mean(values), pct(values, 0.9d), max(values), calls, src(acc.source)));
            }
        }
        if (rows.size() > shown) {
            Acc last = rows.get(shown - 1).getValue();
            out.append(String.format(Locale.ROOT, "  (+%d more, each under %.3f ms per frame)%n", rows.size() - shown,
                    (bySelf ? last.self : last.total) / 1e6d / Math.max(1, a.heldCount)));
        }
        if (tiny > 0) {
            out.append(String.format(Locale.ROOT, "  (+%d under %.3f ms per frame)%n", tiny, TINY_MS_PER_FRAME));
        }
    }

    /** A table row averaging less than this per frame is counted rather than listed. */
    private static final double TINY_MS_PER_FRAME = 0.005d;

    /** A counter's value as printed: a {@code gpu:} counter is nanoseconds, so it reads in ms. */
    private static String counterValue(String name, long value) {
        return name.startsWith(CgGpuTrace.PREFIX)
                ? String.format(Locale.ROOT, "%.2fms", value / 1e6d) : Long.toString(value);
    }

    /** The verdict's one look at the counters: medians, wrapped rather than cut. */
    private void counterLine(StringBuilder out, Analysis a) {
        if (a.counters.isEmpty()) return;
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, CounterAcc> each : a.counters.entrySet()) {
            items.add(each.getKey() + "=" + counterValue(each.getKey(), medianOf(each.getValue().sums)));
        }
        items.sort(String::compareTo);
        out.append("\nCOUNTERS  median per frame\n");
        wrap(out, "  ", items, 3);
    }

    private void counters(StringBuilder out, Analysis a) {
        if (a.counters.isEmpty()) return;
        out.append("\nCOUNTERS  per frame: the frame's sum; median and max over the frames that wrote it.\n")
                .append("  writes = values written per frame (median) -- above 1, the counter is a sum or a distribution\n");
        List<String> names = new ArrayList<>(a.counters.keySet());
        names.sort(String::compareTo);
        int width = "counter".length();
        for (String name : names) width = Math.max(width, name.length());
        out.append(String.format(Locale.ROOT, "  %-" + width + "s %12s %12s %9s %8s%n", "counter", "median", "max", "frames", "writes"));
        for (String name : names) {
            CounterAcc acc = a.counters.get(name);
            long most = Long.MIN_VALUE;
            for (long each : acc.sums) most = Math.max(most, each);
            List<Long> writes = new ArrayList<>();
            for (int each : acc.writes) writes.add((long) each);
            out.append(String.format(Locale.ROOT, "  %-" + width + "s %12s %12s %4d/%-4d %8d%n",
                    name, counterValue(name, medianOf(acc.sums)), counterValue(name, most), acc.sums.size(),
                    a.frames.size(), medianOf(writes)));
        }
    }

    /** Each GPU zone split into groups ({@code gpu:<zone>/<label>}): mean ms per measured frame, and share of the zone. */
    private void gpuGroups(StringBuilder out, Analysis a) {
        Map<String, List<String>> byZone = new TreeMap<>();
        for (String name : a.counters.keySet()) {
            int cut = name.indexOf(CgGpuTrace.GROUP);
            if (name.startsWith(CgGpuTrace.PREFIX) && cut > 0) {
                byZone.computeIfAbsent(name.substring(0, cut), k -> new ArrayList<>()).add(name);
            }
        }
        if (byZone.isEmpty()) return;
        int width = "(outside every group)".length();
        for (Map.Entry<String, List<String>> zone : byZone.entrySet()) {
            width = Math.max(width, zone.getKey().length() - 2);
            for (String group : zone.getValue()) width = Math.max(width, group.length() - zone.getKey().length() - 1);
        }
        String zoneRow = "  %-" + (width + 2) + "s %8.3f%n", groupRow = "    %-" + width + "s %8.3f %5.1f%%%n";
        out.append("\nGPU GROUPS  mean ms over the frames each zone was measured, and the group's share of it\n");
        for (Map.Entry<String, List<String>> zone : byZone.entrySet()) {
            CounterAcc whole = a.counters.get(zone.getKey());
            int frames = whole != null ? whole.sums.size() : 0;
            if (frames == 0) continue;
            double zoneMs = sumOf(whole.sums) / 1e6 / frames, grouped = 0;
            List<String> groups = zone.getValue();
            groups.sort((x, y) -> Long.compare(sumOf(a.counters.get(y).sums), sumOf(a.counters.get(x).sums)));
            out.append(String.format(Locale.ROOT, zoneRow, zone.getKey(), zoneMs));
            for (String group : groups) {
                double ms = sumOf(a.counters.get(group).sums) / 1e6 / frames;
                grouped += ms;
                out.append(String.format(Locale.ROOT, groupRow, group.substring(zone.getKey().length() + 1), ms,
                        100 * ms / zoneMs));
            }
            out.append(String.format(Locale.ROOT, groupRow, "(outside every group)", zoneMs - grouped,
                    100 * (zoneMs - grouped) / zoneMs));
        }
    }

    private static long sumOf(List<Long> values) {
        long sum = 0;
        for (long each : values) sum += each;
        return sum;
    }

    /** Instants, and for a blame marker the call sites it named. */
    private void markers(StringBuilder out, Analysis a, int detailsEach) {
        Map<String, Map<String, Integer>> byName = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (List<CgTraceSnapshot.MarkerView> frameMarkers : a.markersByFrame) {
            for (CgTraceSnapshot.MarkerView marker : frameMarkers) {
                totals.merge(marker.name(), 1, Integer::sum);
                if (marker.detail() != null) {
                    byName.computeIfAbsent(marker.name(), k -> new LinkedHashMap<>()).merge(marker.detail(), 1, Integer::sum);
                }
            }
        }
        if (totals.isEmpty()) return;
        out.append("\nMARKERS  instants, with the details they carried (for blame: the call sites)\n");
        List<String> names = new ArrayList<>(totals.keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            out.append(String.format(Locale.ROOT, "  %-24s x%d%n", name, totals.get(name)));
            Map<String, Integer> details = byName.get(name);
            if (details == null) continue;
            List<Map.Entry<String, Integer>> rows = new ArrayList<>(details.entrySet());
            rows.sort((x, y) -> y.getValue() != x.getValue().intValue()
                    ? Integer.compare(y.getValue(), x.getValue()) : x.getKey().compareTo(y.getKey()));
            for (int i = 0; i < Math.min(detailsEach, rows.size()); i++) {
                out.append(String.format(Locale.ROOT, "      x%-5d %s%n", rows.get(i).getValue(), rows.get(i).getKey()));
            }
            if (rows.size() > detailsEach) out.append("      (+").append(rows.size() - detailsEach).append(" more)\n");
        }
    }

    private void hints(StringBuilder out, Analysis a, int limit) {
        if (a.hintsByCode.isEmpty()) return;
        out.append("\nHINTS  rules over counters, run on every frame: how many frames each fired in, and the first\n");
        List<Map.Entry<String, List<Object[]>>> rows = new ArrayList<>(a.hintsByCode.entrySet());
        rows.sort((x, y) -> y.getValue().size() != x.getValue().size()
                ? Integer.compare(y.getValue().size(), x.getValue().size()) : x.getKey().compareTo(y.getKey()));
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            List<Object[]> fired = rows.get(i).getValue();
            CgTraceHints.Hint first = (CgTraceHints.Hint) fired.get(0)[1];
            out.append(String.format(Locale.ROOT, "  %-18s %d frames, first #%d: %s%s%n", rows.get(i).getKey(),
                    fired.size(), (Long) fired.get(0)[0], first.text(), first.link() == null ? "" : "  -> " + first.link()));
        }
    }

    private void slowest(StringBuilder out, Analysis a, int count) {
        out.append("\nSLOWEST BY CPU  ms; idle = wall - cpu; unexplained = idle no zone covers\n");
        List<Integer> byCpu = a.byCpuDescending();
        for (int i = 0; i < Math.min(count, byCpu.size()); i++) out.append("  ").append(headline(a, byCpu.get(i))).append('\n');
        out.append("SLOWEST BY WALL  high here and low above: it waited rather than worked. unexplained high: nothing named it\n");
        List<Integer> byWall = new ArrayList<>();
        for (int i = 0; i < a.frames.size(); i++) byWall.add(i);
        byWall.sort(Comparator.comparingLong((Integer i) -> a.frames.get(i).wallNanos()).reversed().thenComparing(i -> i));
        for (int i = 0; i < Math.min(count, byWall.size()); i++) out.append("  ").append(headline(a, byWall.get(i))).append('\n');
    }

    /** One frame's figures on one line. */
    private static String headline(Analysis a, int at) {
        CgFrameRecord frame = a.frames.get(at);
        long idle = frame.hasCpu() ? Math.max(0L, frame.wallNanos() - frame.cpuNanos()) : -1L;
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "#%-7d cpu %8s  wall %8.2f  idle %8s  unexplained %8s  gpu %7s",
                frame.index(),
                frame.hasCpu() ? String.format(Locale.ROOT, "%.2f", frame.cpuMillis()) : "absent",
                frame.wallMillis(),
                idle < 0L ? "-" : String.format(Locale.ROOT, "%.2f", idle / 1e6d),
                idle < 0L ? "-" : String.format(Locale.ROOT, "%.2f", Math.max(0L, idle - a.idleCovered[at]) / 1e6d),
                a.gpu[at] >= 0L ? String.format(Locale.ROOT, "%.2f", a.gpu[at] / 1e6d) : "absent"));
        if (frame.hadGc()) line.append("  GC ").append(frame.gcSummary());
        if (!a.held[at]) line.append("  (zones not held)");
        return line.toString();
    }

    // ── One frame ───────────────────────────────────────────────────────────────────────────

    /** A gap is reported when a zone's own body is at least this share of it... */
    private static final double GAP_SHARE = 0.2d;
    /** ...and at least this long: below it the gap is the zone's own bookkeeping. */
    private static final long GAP_FLOOR_NANOS = 100_000L;
    /** A row under this share of the frame is folded into its parent's "smaller" line... */
    private static final double FOLD_SHARE = 0.01d;
    /** ...or under this, whichever is larger. */
    private static final long FOLD_FLOOR_NANOS = 20_000L;

    private void frameBlock(StringBuilder out, Analysis a, int at, String why, int maxRows) {
        CgFrameRecord frame = a.frames.get(at);
        out.append("\nFRAME ").append(headline(a, at)).append("  -- ").append(why).append('\n');
        if (frame.leakedZones() > 0) {
            out.append("  note      ").append(frame.leakedZones())
                    .append(" zone(s) were still open at the frame boundary and were force-closed\n");
        }
        if (!a.held[at]) {
            out.append("  zones   none held -- older than every zone the frame thread still holds (overwritten, or "
                    + "recorded before its channels were on); its time is unknown, not unzoned\n");
            return;
        }
        List<CgTraceAggregate.Node> roots = a.trees.get(at);
        long base = Math.max(1L, cpuOrWall(frame));
        long floor = Math.max(FOLD_FLOOR_NANOS, (long) (base * FOLD_SHARE));
        if (roots.isEmpty()) {
            out.append("  (no zones recorded in this frame)\n");
        } else {
            out.append(String.format(Locale.ROOT, "  tree  ms and share of the frame's %s (idle: ran after the cpu mark);"
                    + " rows under %.2fms are folded%n",
                    frame.hasCpu() ? "cpu" : "wall", floor / 1e6d));
            Map<String, List<CgTraceAggregate.Node>> byThread = new LinkedHashMap<>();
            byThread.put(a.frameThread, new ArrayList<>());
            for (CgTraceAggregate.Node root : roots) byThread.computeIfAbsent(root.thread(), k -> new ArrayList<>()).add(root);
            List<String[]> rows = new ArrayList<>();
            int[] budget = {maxRows};
            for (Map.Entry<String, List<CgTraceAggregate.Node>> thread : byThread.entrySet()) {
                if (thread.getValue().isEmpty()) continue;
                rows.add(new String[] {"  [" + thread.getKey() + "]", null, null, null});
                treeRows(rows, mergeRoots(thread.getValue(),
                        frame.hasCpu() ? frame.beginNanos() + frame.cpuNanos() : Long.MAX_VALUE), 2, base, floor, budget);
            }
            int width = 0;
            for (String[] row : rows) if (row[1] != null) width = Math.max(width, row[0].length());
            for (String[] row : rows) {
                if (row[1] == null) {
                    out.append(row[0]).append('\n');
                } else {
                    out.append(String.format(Locale.ROOT, "%-" + width + "s %9s %4s   %s%n", row[0], row[1], row[2], row[3]));
                }
            }
            if (budget[0] <= 0) out.append("  (tree cut at ").append(maxRows).append(" rows; frame(").append(frame.index())
                    .append(") prints it whole)\n");
            if (a.unzoned[at] >= 0L) {
                out.append(String.format(Locale.ROOT,
                        "  unzoned   %.2fms of %.2fms %s (%.0f%%) -- frame-thread time no zone covers%n",
                        a.unzoned[at] / 1e6d, base / 1e6d, frame.hasCpu() ? "cpu" : "wall", a.unzoned[at] * 100d / base));
                stretch(out, byThread.get(a.frameThread), frame.beginNanos(),
                        frame.hasCpu() ? frame.beginNanos() + frame.cpuNanos() : frame.endNanos(),
                        "the frame's begin", frame.hasCpu() ? "the cpu mark" : "the frame's end");
            }
            if (frame.hasCpu()) {
                long idle = Math.max(0L, frame.wallNanos() - frame.cpuNanos());
                long unexplained = Math.max(0L, idle - a.idleCovered[at]);
                if (unexplained >= GAP_FLOOR_NANOS) {
                    out.append(String.format(Locale.ROOT,
                            "  unexplained %.2fms of %.2fms idle -- after the cpu mark, and no zone covers it%n",
                            unexplained / 1e6d, idle / 1e6d));
                    stretch(out, byThread.get(a.frameThread), frame.beginNanos() + frame.cpuNanos(), frame.endNanos(),
                            "the cpu mark", "the next frame's begin");
                }
            }
            List<Merged> gaps = new ArrayList<>();
            for (List<CgTraceAggregate.Node> thread : byThread.values()) {
                for (Merged root : merge(thread)) collectGaps(root, gaps);
            }
            gaps.sort(Comparator.comparingLong(Merged::self).reversed().thenComparing(m -> m.name));
            for (int i = 0; i < Math.min(5, gaps.size()); i++) {
                Merged gap = gaps.get(i);
                out.append(String.format(Locale.ROOT, "  GAP       %s%s: %.2fms of %.2fms is in none of its children   %s%n",
                        gap.name, gap.count > 1 ? " x" + gap.count : "", gap.self() / 1e6d, gap.total / 1e6d, src(gap.source)));
            }
        }
        Map<String, Long> counters = a.countersByFrame.get(at);
        if (!counters.isEmpty()) {
            // ONLY WHAT SETS THIS FRAME APART: a counter at its median says nothing the COUNTERS table did not.
            List<String> items = new ArrayList<>();
            for (Map.Entry<String, Long> each : counters.entrySet()) {
                CounterAcc acc = a.counters.get(each.getKey());
                long median = medianOf(acc.sums);
                boolean rare = acc.sums.size() * 2 < a.frames.size();
                if (each.getValue() == median && !rare) continue;
                items.add(each.getKey() + "=" + counterValue(each.getKey(), each.getValue())
                        + (rare ? " (in " + acc.sums.size() + "/" + a.frames.size() + " frames)"
                                : " (median " + counterValue(each.getKey(), median) + ")"));
            }
            items.sort(String::compareTo);
            if (items.isEmpty()) {
                out.append("  counters  every one at its median\n");
            } else {
                out.append("  counters  those off their median or written in few frames; the rest are at the median\n");
                wrap(out, "    ", items, Integer.MAX_VALUE);
            }
        }
        Map<String, Integer> markers = new LinkedHashMap<>();
        for (CgTraceSnapshot.MarkerView marker : a.markersByFrame.get(at)) {
            markers.merge(marker.detail() == null ? marker.name() : marker.name() + " " + marker.detail(), 1, Integer::sum);
        }
        if (!markers.isEmpty()) {
            out.append("  markers\n");
            for (Map.Entry<String, Integer> each : markers.entrySet()) {
                out.append("    x").append(each.getValue()).append(' ').append(each.getKey()).append('\n');
            }
        }
        for (CgTraceHints.Hint hint : CgTraceHints.forFrame(frame, roots, counters)) {
            out.append(String.format(Locale.ROOT, "  HINT      %s: %s%s%n",
                    hint.code(), hint.text(), hint.link() == null ? "" : "  -> " + hint.link()));
        }
    }

    /**
     * The longest stretch of {@code [from, to)} no root zone covers, and the zones either side of it —
     * so "unzoned" or "unexplained" says where to put the next zone.
     */
    private void stretch(StringBuilder out, List<CgTraceAggregate.Node> roots, long from, long to,
                         String fromLabel, String toLabel) {
        List<CgTraceAggregate.Node> sorted = new ArrayList<>(roots == null ? List.of() : roots);
        sorted.sort(Comparator.comparingLong(CgTraceAggregate.Node::startNanos));
        long longest = 0L;
        String before = fromLabel;
        String after = toLabel;
        String last = fromLabel;
        long cursor = from;
        for (CgTraceAggregate.Node root : sorted) {
            if (root.endNanos() <= from) {
                last = root.name();
                continue;
            }
            if (root.startNanos() >= to) break;
            long gap = Math.min(to, root.startNanos()) - cursor;
            if (gap > longest) {
                longest = gap;
                before = last;
                after = root.name();
            }
            cursor = Math.max(cursor, root.endNanos());
            last = root.name();
        }
        if (to - cursor > longest) {
            longest = to - cursor;
            before = last;
            after = toLabel;
        }
        if (longest < GAP_FLOOR_NANOS) return;
        out.append(String.format(Locale.ROOT, "            longest stretch %.2fms: after %s, before %s%n",
                longest / 1e6d, before, after));
    }

    /**
     * One name under one parent in one frame: its calls summed, their children merged the same way — so a
     * zone called fifty times reads as one row with {@code x50} rather than fifty rows.
     */
    private static final class Merged {
        final String name;
        final String source;
        long total;
        int count;
        /** A root that ran after the frame's cpu mark: host work in the idle window, not a share of cpu. */
        boolean afterCpu;
        final Map<String, Merged> children = new LinkedHashMap<>();

        Merged(String name, String source) {
            this.name = name;
            this.source = source;
        }

        long self() {
            long own = total;
            for (Merged child : children.values()) own -= child.total;
            return Math.max(0L, own);
        }
    }

    /** Siblings merged by name, costliest first; waits last. */
    private static List<Merged> merge(List<CgTraceAggregate.Node> nodes) {
        Map<String, Merged> byName = new LinkedHashMap<>();
        mergeInto(byName, nodes);
        return sortedMerged(byName.values());
    }

    private static void mergeInto(Map<String, Merged> into, List<CgTraceAggregate.Node> nodes) {
        for (CgTraceAggregate.Node node : nodes) {
            Merged merged = into.computeIfAbsent(node.name(), k -> new Merged(node.name(), node.source()));
            merged.total += node.durationNanos();
            merged.count++;
            mergeInto(merged.children, node.children());
        }
    }

    /** {@link #merge}, with the roots that started after {@code cpuMark} kept apart and marked. */
    private static List<Merged> mergeRoots(List<CgTraceAggregate.Node> roots, long cpuMark) {
        Map<String, Merged> byName = new LinkedHashMap<>();
        Map<String, Merged> after = new LinkedHashMap<>();
        for (CgTraceAggregate.Node root : roots) {
            mergeInto(root.startNanos() >= cpuMark ? after : byName, List.of(root));
        }
        for (Merged each : after.values()) each.afterCpu = true;
        List<Merged> all = new ArrayList<>(byName.values());
        all.addAll(after.values());
        return sortedMerged(all);
    }

    private static List<Merged> sortedMerged(Collection<Merged> merged) {
        List<Merged> sorted = new ArrayList<>(merged);
        sorted.sort(Comparator.comparing((Merged m) -> CgTrace.isWait(m.name))
                .thenComparing((Merged m) -> m.afterCpu)
                .thenComparing(Comparator.comparingLong((Merged m) -> m.total).reversed())
                .thenComparing(m -> m.name));
        return sorted;
    }

    private void treeRows(List<String[]> rows, List<Merged> nodes, int indent, long base, long floor, int[] budget) {
        int folded = 0;
        long foldedNanos = 0L;
        for (Merged node : nodes) {
            if (node.total < floor) {
                folded++;
                foldedNanos += node.total;
                continue;
            }
            if (budget[0]-- <= 0) return;
            boolean wait = CgTrace.isWait(node.name);
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < indent; i++) label.append("  ");
            label.append(node.name);
            if (node.count > 1) label.append(" x").append(node.count);
            if (wait) label.append("  [wait]");
            else if (node.afterCpu) label.append("  [after cpu]");
            rows.add(new String[] {label.toString(), String.format(Locale.ROOT, "%.2f", node.total / 1e6d),
                    wait ? "wait" : node.afterCpu ? "idle" : String.format(Locale.ROOT, "%.0f%%", node.total * 100d / base),
                    src(node.source)});
            treeRows(rows, sortedMerged(node.children.values()), indent + 1, base, floor, budget);
        }
        // A FOLDED LINE ONLY WHEN IT CARRIES TIME: "+2 smaller, 0.00" says nothing a reader can act on.
        if (folded > 0 && foldedNanos >= floor && budget[0]-- > 0) {
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < indent; i++) label.append("  ");
            label.append("(+").append(folded).append(" smaller, each under the fold)");
            rows.add(new String[] {label.toString(), String.format(Locale.ROOT, "%.2f", foldedNanos / 1e6d),
                    String.format(Locale.ROOT, "%.0f%%", foldedNanos * 100d / base), ""});
        }
    }

    private static void collectGaps(Merged node, List<Merged> gaps) {
        if (!node.children.isEmpty() && !CgTrace.isWait(node.name) && node.self() >= GAP_FLOOR_NANOS
                && node.self() >= node.total * GAP_SHARE && node.self() < node.total * LEAF_SHARE) {
            gaps.add(node);
        }
        for (Merged child : node.children.values()) collectGaps(child, gaps);
    }

    /** A zone whose own body is at least this share of it is a leaf in practice, whatever it nests. */
    private static final double LEAF_SHARE = 0.995d;

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    private String src(String source) {
        return source == null ? "" : sources.apply(source);
    }

    private String sourceOf(String name) {
        Acc acc = analysis().zones.get(name);
        return acc == null ? null : acc.source;
    }

    /** Items separated by two spaces, on lines of about 110 characters; never cut. */
    private static void wrap(StringBuilder out, String indent, List<String> items, int maxLines) {
        StringBuilder line = new StringBuilder(indent);
        int lines = 0;
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (line.length() > indent.length() && line.length() + item.length() + 2 > 110) {
                if (++lines >= maxLines) {
                    out.append(line).append("  (+").append(items.size() - i).append(" more)\n");
                    return;
                }
                out.append(line).append('\n');
                line.setLength(0);
                line.append(indent);
            }
            if (line.length() > indent.length()) line.append("  ");
            line.append(item);
        }
        out.append(line).append('\n');
    }

    private static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    private static long medianOf(List<Long> values) {
        if (values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static double mean(double[] values) {
        if (values.length == 0) return 0d;
        double sum = 0d;
        for (double each : values) sum += each;
        return sum / values.length;
    }

    private static double max(double[] values) {
        double most = 0d;
        for (double each : values) most = Math.max(most, each);
        return most;
    }

    /** The value below which {@code share} of {@code values} fall; 0 for none. */
    private static double pct(double[] values, double share) {
        if (values.length == 0) return 0d;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int at = share >= 0.5d && share < 0.51d ? sorted.length / 2
                : (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(share * sorted.length) - 1));
        return sorted[at];
    }
}
