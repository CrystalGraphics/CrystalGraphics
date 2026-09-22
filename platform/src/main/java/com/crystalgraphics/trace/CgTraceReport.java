package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The trace as text — <b>the surface an agent reads</b>, and a human in a terminal.
 *
 * <pre>{@code
 * String verdict = CgTraceReport.of(CgTrace.snapshot()).verdict();
 * String one     = CgTraceReport.of(snapshot).frame(412);
 * String slowest = CgTraceReport.of(snapshot).worst(5);
 * String where   = CgTraceReport.of(snapshot).zone("paint:tree");
 * String delta   = CgTraceReport.of(snapshot).compare(0, 299, 300, 599);
 * }</pre>
 *
 * <h3>This is the primary surface, not a by-product of the window</h3>
 *
 * <p>A flame chart cannot be read by the thing most often driving this engine. The window is for a
 * human investigating; this is for an agent in a loop — run the scene, read one file, edit, run again,
 * {@code diff}. Both are renderings of one {@link CgTraceSnapshot}, which is what makes it impossible
 * for them to disagree.</p>
 *
 * <h3>Five rules, and two of them are load-bearing</h3>
 *
 * <ol>
 *   <li><b>Tiered, because context is the scarce resource.</b> {@link Tier#VERDICT} is about twenty
 *       lines and is read first; {@link Tier#BREAKDOWN} is about a hundred and twenty; {@link Tier#FULL}
 *       is unbounded and is asked for only when the breakdown was inconclusive. This is the analogue of
 *       the HUD's 46-character rule: a profiler that floods its reader has destroyed the reader's
 *       ability to act on it, and an agent's context is as finite as a corner of a screen.</li>
 *   <li><b>Deterministic, so two runs {@code diff}.</b> Sorted by cost descending and by name on a tie,
 *       durations at fixed precision, and <b>no wall-clock time, thread id or run id anywhere in the
 *       body</b> — those live in the header, which a diff skips. A before-and-after then needs no
 *       tooling and no window.</li>
 *   <li><b>Jumpable.</b> Every zone line ends in the {@code File.java:line} its name was first used
 *       from ({@link CgTraceNames}), which a terminal or an editor turns into a click.</li>
 *   <li><b>Attributed.</b> Where a {@code blame} marker names a call site, the frame carries it.</li>
 *   <li><b>Self-describing, so a partial trace cannot read as a complete one.</b> The header states the
 *       channels recording, the channels <em>not</em> recording, the ring's true span and anything
 *       dropped. A reader who cannot see that a channel was off reads a gap as work that did not
 *       happen.</li>
 * </ol>
 */
public final class CgTraceReport {

    /** How much of the trace a reader is asking for. */
    public enum Tier {
        /** ~20 lines: the verdict, the worst frame's tree, its counters and its hints. */
        VERDICT,
        /** ~120 lines: the above, the slowest handful of frames, and the zone table. */
        BREAKDOWN,
        /** Unbounded — every frame. Asked for when the breakdown was inconclusive. */
        FULL
    }

    private final CgTraceSnapshot snapshot;
    private double budgetMillis = 1000d / 60d;

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

    // ── The queries ─────────────────────────────────────────────────────────────────────────

    public String verdict() {
        return render(Tier.VERDICT);
    }

    public String breakdown() {
        return render(Tier.BREAKDOWN);
    }

    /** The whole report at {@code tier}. */
    public String render(Tier tier) {
        StringBuilder out = new StringBuilder(2048);
        header(out);
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.size() < 2) {
            out.append("\nVERDICT   no frames recorded\n");
            return out.toString();
        }
        verdictLine(out, frames);
        CgFrameRecord worst = worstByCpu(frames);
        if (worst != null) frameBlock(out, worst, 8);

        if (tier == Tier.VERDICT) return out.toString();

        out.append("\nSLOWEST FRAMES\n");
        List<CgFrameRecord> byCost = new ArrayList<>(frames);
        byCost.sort(Comparator.comparingLong(CgFrameRecord::wallNanos).reversed()
                .thenComparingLong(CgFrameRecord::index));
        for (int i = 0; i < Math.min(5, byCost.size()); i++) {
            CgFrameRecord frame = byCost.get(i);
            out.append(String.format(Locale.ROOT, "  frame %-8d %7.2fms wall  %s cpu%n",
                    frame.index(), frame.wallMillis(),
                    frame.hasCpu() ? String.format(Locale.ROOT, "%7.2fms", frame.cpuMillis()) : "  absent"));
        }

        out.append("\nZONES, whole ring\n");
        zoneTable(out, CgTraceAggregate.byCost(snapshot.zones()), tier == Tier.FULL ? 200 : 25);

        if (tier != Tier.FULL) return out.toString();

        out.append("\nEVERY FRAME\n");
        for (CgFrameRecord frame : frames) {
            out.append(String.format(Locale.ROOT, "  %-8d %7.2fms%n", frame.index(), frame.wallMillis()));
        }
        return out.toString();
    }

    /** One frame in full — its tree, its counters, its hints. */
    public String frame(long index) {
        StringBuilder out = new StringBuilder(1024);
        header(out);
        CgFrameRecord frame = snapshot.frame(index);
        if (frame == null) {
            out.append("\nframe ").append(index).append(" is not in the ring\n");
            return out.toString();
        }
        frameBlock(out, frame, 64);
        return out.toString();
    }

    /** The {@code count} slowest frames, each as a breakdown. */
    public String worst(int count) {
        StringBuilder out = new StringBuilder(2048);
        header(out);
        List<CgFrameRecord> byCost = new ArrayList<>(snapshot.frames());
        byCost.sort(Comparator.comparingLong(CgFrameRecord::wallNanos).reversed()
                .thenComparingLong(CgFrameRecord::index));
        for (int i = 0; i < Math.min(Math.max(1, count), byCost.size()); i++) {
            frameBlock(out, byCost.get(i), 8);
        }
        return out.toString();
    }

    /** Every instance of one zone across the ring: how many, how long, how it is spread. */
    public String zone(String name) {
        StringBuilder out = new StringBuilder(512);
        header(out);
        for (CgTraceAggregate.Stat stat : CgTraceAggregate.byCost(snapshot.zones())) {
            if (!stat.name().equals(name)) continue;
            out.append(String.format(Locale.ROOT,
                    "%n%s%n  count %d   total %.2fms   self %.2fms   mean %.3fms   max %.2fms%n  %s%n",
                    stat.name(), stat.count(), stat.totalMillis(), stat.selfMillis(),
                    stat.meanMillis(), stat.maxMillis(),
                    stat.source() == null ? "" : stat.source()));
            return out.toString();
        }
        out.append("\nno zone named ").append(name).append(" in the ring\n");
        return out.toString();
    }

    /**
     * Two frame ranges, per zone, sorted by the size of the change — what an agent runs after an edit.
     *
     * <p>Both ranges come from ONE ring, which is what makes the comparison honest: the same process,
     * the same JIT state, the same machine. Comparing two runs means comparing two exported files, and
     * that is a different and weaker claim.</p>
     */
    public String compare(long fromA, long toA, long fromB, long toB) {
        Map<String, CgTraceAggregate.Stat> before = statsOver(fromA, toA);
        Map<String, CgTraceAggregate.Stat> after = statsOver(fromB, toB);

        StringBuilder out = new StringBuilder(1024);
        header(out);
        out.append(String.format(Locale.ROOT, "%nCOMPARE   frames %d..%d against %d..%d%n",
                fromA, toA, fromB, toB));

        List<String> names = new ArrayList<>(before.keySet());
        for (String name : after.keySet()) {
            if (!names.contains(name)) names.add(name);
        }
        List<Object[]> rows = new ArrayList<>();
        for (String name : names) {
            double a = before.containsKey(name) ? before.get(name).totalMillis() : 0d;
            double b = after.containsKey(name) ? after.get(name).totalMillis() : 0d;
            rows.add(new Object[] {name, a, b, b - a});
        }
        rows.sort((x, y) -> {
            int byDelta = Double.compare(Math.abs((Double) y[3]), Math.abs((Double) x[3]));
            return byDelta != 0 ? byDelta : ((String) x[0]).compareTo((String) y[0]);
        });
        out.append(String.format(Locale.ROOT, "  %-28s %10s %10s %10s%n", "zone", "before", "after", "delta"));
        for (int i = 0; i < Math.min(25, rows.size()); i++) {
            Object[] row = rows.get(i);
            out.append(String.format(Locale.ROOT, "  %-28s %9.2fms %9.2fms %+9.2fms%n",
                    cut((String) row[0], 28), row[1], row[2], row[3]));
        }
        return out.toString();
    }

    /** {@link #compare} filtered to what got worse — the one an agent wants after an edit. */
    public String regressions(long fromA, long toA, long fromB, long toB) {
        String all = compare(fromA, toA, fromB, toB);
        StringBuilder out = new StringBuilder(all.length());
        for (String line : all.split("\n", -1)) {
            if (line.contains("ms") && line.contains("+")) out.append(line).append('\n');
            else if (!line.startsWith("  ") || line.contains("zone")) out.append(line).append('\n');
        }
        return out.toString();
    }

    private Map<String, CgTraceAggregate.Stat> statsOver(long from, long to) {
        List<CgTraceSnapshot.ZoneView> zones = new ArrayList<>();
        for (CgFrameRecord frame : snapshot.frames()) {
            if (frame.index() < from || frame.index() > to) continue;
            zones.addAll(snapshot.zonesIn(frame));
        }
        Map<String, CgTraceAggregate.Stat> out = new LinkedHashMap<>();
        for (CgTraceAggregate.Stat stat : CgTraceAggregate.byCost(zones)) out.put(stat.name(), stat);
        return out;
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────

    /**
     * What this trace IS — so a partial one cannot be read as a complete one.
     *
     * <p>Above the body and never inside it, because a {@code diff} of two runs should show what
     * changed in the measurements rather than that the clock moved.</p>
     */
    private void header(StringBuilder out) {
        List<CgFrameRecord> frames = snapshot.frames();
        double span = frames.size() < 2 ? 0d
                : (frames.get(frames.size() - 1).endNanos() - frames.get(0).beginNanos()) / 1e9d;

        List<String> on = new ArrayList<>();
        List<String> off = new ArrayList<>();
        for (CgTraceChannel channel : CgTrace.channels()) {
            (channel.isEnabled() ? on : off).add(channel.name());
        }
        out.append(String.format(Locale.ROOT, "trace  %d frames / %.2fs%n", frames.size(), span));
        out.append("channels  recording: ").append(on.isEmpty() ? "none" : String.join(", ", on));
        if (!off.isEmpty()) out.append("  |  NOT recording: ").append(String.join(", ", off));
        out.append('\n');
        long dropped = snapshot.droppedZones();
        if (dropped > 0) {
            out.append("WARNING   ").append(dropped)
                    .append(" zones dropped: the arena overflowed, so a short frame may not be a fast one\n");
        }
    }

    private void verdictLine(StringBuilder out, List<CgFrameRecord> frames) {
        long[] wall = new long[frames.size()];
        for (int i = 0; i < frames.size(); i++) wall[i] = frames.get(i).wallNanos();
        long[] sorted = wall.clone();
        java.util.Arrays.sort(sorted);
        double medianMs = sorted[sorted.length / 2] / 1_000_000d;
        long budget = (long) (budgetMillis * 1_000_000d);
        int missed = 0;
        for (long each : wall) {
            if (each > budget) missed++;
        }
        CgFrameRecord worst = worstByWall(frames);
        out.append(String.format(Locale.ROOT,
                "%nVERDICT   %.0f fps median   %d/%d over %.1fms   worst %.1fms @ frame %d%n",
                medianMs <= 0d ? 0d : 1000d / medianMs, missed, frames.size(), budgetMillis,
                worst == null ? 0d : worst.wallMillis(), worst == null ? -1L : worst.index()));
    }

    /** One frame: its headline, its tree, its counters, its hints. */
    private void frameBlock(StringBuilder out, CgFrameRecord frame, int maxTreeRows) {
        out.append(String.format(Locale.ROOT, "%nframe %-8d %7.2fms wall  %s cpu%s%n",
                frame.index(), frame.wallMillis(),
                frame.hasCpu() ? String.format(Locale.ROOT, "%.2fms", frame.cpuMillis()) : "absent",
                frame.hadGc() ? "   GC " + frame.gcSummary() : ""));
        if (frame.leakedZones() > 0) {
            out.append("  NOTE    ").append(frame.leakedZones())
                    .append(" zone(s) were still open at the boundary and were force-closed\n");
        }

        List<CgTraceSnapshot.ZoneView> zones = snapshot.zonesIn(frame);
        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(zones);
        long total = CgTraceAggregate.totalOf(roots);
        int[] budget = {maxTreeRows};
        List<CgTraceAggregate.Node> sorted = new ArrayList<>(roots);
        sorted.sort(Comparator.comparingLong(CgTraceAggregate.Node::durationNanos).reversed()
                .thenComparing(CgTraceAggregate.Node::name));
        for (CgTraceAggregate.Node root : sorted) {
            if (budget[0] <= 0) break;
            treeRow(out, root, total, 1, budget);
        }
        if (roots.isEmpty()) out.append("  (no zones recorded in this frame)\n");

        Map<String, Long> counters = countersOf(frame);
        if (!counters.isEmpty()) {
            StringBuilder line = new StringBuilder("  counters");
            for (Map.Entry<String, Long> each : counters.entrySet()) {
                line.append(' ').append(each.getKey()).append('=').append(each.getValue());
            }
            out.append(cut(line.toString(), 110)).append('\n');
        }
        for (CgTraceHints.Hint hint : CgTraceHints.forFrame(frame, roots, counters)) {
            out.append(String.format(Locale.ROOT, "  HINT    %-18s %s%s%n",
                    hint.code(), hint.text(), hint.link() == null ? "" : "  -> " + hint.link()));
        }
    }

    private void treeRow(StringBuilder out, CgTraceAggregate.Node node, long total,
                         int indent, int[] budget) {
        if (budget[0]-- <= 0) return;
        // PADDED BY HAND: Java's format has no `*` width taken from an argument, and the name column
        // narrows with depth so the tree reads as a tree.
        int width = Math.max(4, 30 - indent * 2);
        String label = cut(node.name(), width);
        for (int i = 0; i < indent; i++) out.append("  ");
        out.append(label);
        for (int i = label.length(); i < width; i++) out.append(' ');
        out.append(String.format(Locale.ROOT, " %8.2fms %3.0f%%   %s%n",
                node.millis(), total <= 0L ? 0d : node.durationNanos() * 100d / total,
                node.source() == null ? "" : node.source()));
        List<CgTraceAggregate.Node> children = new ArrayList<>(node.children());
        children.sort(Comparator.comparingLong(CgTraceAggregate.Node::durationNanos).reversed()
                .thenComparing(CgTraceAggregate.Node::name));
        for (CgTraceAggregate.Node child : children) treeRow(out, child, total, indent + 1, budget);
    }

    private void zoneTable(StringBuilder out, List<CgTraceAggregate.Stat> stats, int limit) {
        out.append(String.format(Locale.ROOT, "  %-28s %6s %10s %10s %9s   %s%n",
                "zone", "count", "total", "self", "mean", "source"));
        for (int i = 0; i < Math.min(limit, stats.size()); i++) {
            CgTraceAggregate.Stat stat = stats.get(i);
            out.append(String.format(Locale.ROOT, "  %-28s %6d %9.2fms %9.2fms %8.3fms   %s%n",
                    cut(stat.name(), 28), stat.count(), stat.totalMillis(), stat.selfMillis(),
                    stat.meanMillis(), stat.source() == null ? "" : stat.source()));
        }
    }

    private Map<String, Long> countersOf(CgFrameRecord frame) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (CgTraceSnapshot.CounterView counter : snapshot.countersIn(frame)) {
            out.merge(counter.name(), counter.value(), Long::sum);
        }
        return out;
    }

    private static CgFrameRecord worstByWall(List<CgFrameRecord> frames) {
        CgFrameRecord worst = null;
        for (CgFrameRecord frame : frames) {
            if (worst == null || frame.wallNanos() > worst.wallNanos()) worst = frame;
        }
        return worst;
    }

    /**
     * The slowest frame by CPU.
     *
     * <p>Not by wall time: under vsync the slowest wall frame is usually the one that waited longest,
     * and there is nothing in a wait to fix.</p>
     */
    private static CgFrameRecord worstByCpu(List<CgFrameRecord> frames) {
        CgFrameRecord worst = null;
        for (CgFrameRecord frame : frames) {
            if (!frame.hasCpu()) continue;
            if (worst == null || frame.cpuNanos() > worst.cpuNanos()) worst = frame;
        }
        return worst != null ? worst : worstByWall(frames);
    }

    private static String cut(String text, int width) {
        return text.length() <= width ? text : text.substring(0, Math.max(1, width - 1)) + "…";
    }
}
