package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a frame's zone <b>tree</b> from the flat ring, and the per-name statistics over it.
 *
 * <pre>{@code
 * List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(CgTrace.zonesIn(frame));
 * for (CgTraceAggregate.Stat stat : CgTraceAggregate.byCost(CgTrace.zonesIn(frame))) {
 *     System.out.printf("%-24s %6.2fms self %6.2fms x%d%n",
 *             stat.name(), stat.totalMillis(), stat.selfMillis(), stat.count());
 * }
 * }</pre>
 *
 * <h3>Below both surfaces, on purpose</h3>
 *
 * <p>A text report and a flame chart want the same thing: parenthood, and the time a zone spent in its
 * own body rather than in its children. Computing that inside the window would leave the report to
 * duplicate it, and would put arithmetic that deserves a headless test inside a widget. So it lives
 * here, where {@link CgTraceReport} and the viewer both read it and neither owns it.</p>
 *
 * <h3>Depth is authoritative, containment is the guard</h3>
 *
 * <p>Parenthood comes from the depth each zone recorded, not from guessing at overlaps: the arena wrote
 * it down at the moment of the push and it is the one answer that cannot be wrong. Containment is
 * checked anyway — a zone that begins after its apparent parent has ended is re-parented outward —
 * because a leaked zone force-closed at a frame boundary can leave exactly that shape, and a tree built
 * from it would nest a frame's worth of work under one row.</p>
 */
public final class CgTraceAggregate {

    private CgTraceAggregate() {
    }

    /** One zone with its children — what a flame chart draws and what a report indents. */
    public record Node(String name, String source, String thread, String channel,
                       long startNanos, long endNanos, int depth, List<Node> children) {

        public long durationNanos() {
            return Math.max(0L, endNanos - startNanos);
        }

        public double millis() {
            return durationNanos() / 1_000_000d;
        }

        /** This zone's own body — its duration less everything nested inside it. */
        public long selfNanos() {
            long own = durationNanos();
            for (Node child : children) own -= child.durationNanos();
            return Math.max(0L, own);
        }

        public double selfMillis() {
            return selfNanos() / 1_000_000d;
        }
    }

    /** Every instance of one name, rolled up. @see #byCost */
    public record Stat(String name, String source, int count,
                       long totalNanos, long selfNanos, long minNanos, long maxNanos) {

        public double totalMillis() {
            return totalNanos / 1_000_000d;
        }

        public double selfMillis() {
            return selfNanos / 1_000_000d;
        }

        public double meanMillis() {
            return count == 0 ? 0d : totalNanos / (double) count / 1_000_000d;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000d;
        }
    }

    /**
     * The roots of {@code zones}, in start order, with children attached.
     *
     * <p>One tree per thread, concatenated: a worker's zones are nobody's children, and nesting them
     * under whatever the frame thread happened to have open would invent a call relationship across
     * threads that does not exist.</p>
     */
    public static List<Node> tree(List<CgTraceSnapshot.ZoneView> zones) {
        Map<String, List<CgTraceSnapshot.ZoneView>> byThread = new LinkedHashMap<>();
        for (CgTraceSnapshot.ZoneView zone : zones) {
            if (zone.isOpen()) continue;
            byThread.computeIfAbsent(zone.thread(), key -> new ArrayList<>()).add(zone);
        }
        List<Node> roots = new ArrayList<>();
        for (List<CgTraceSnapshot.ZoneView> ofThread : byThread.values()) {
            ofThread.sort(Comparator.comparingLong(CgTraceSnapshot.ZoneView::startNanos));
            buildThread(ofThread, roots);
        }
        roots.sort(Comparator.comparingLong(Node::startNanos));
        return roots;
    }

    private static void buildThread(List<CgTraceSnapshot.ZoneView> ofThread, List<Node> roots) {
        List<Node> open = new ArrayList<>();
        for (CgTraceSnapshot.ZoneView zone : ofThread) {
            // Pop anything that cannot be this zone's ancestor: a sibling or deeper, or one that has
            // already ended. The second is what protects the tree from a force-closed leak.
            while (!open.isEmpty()) {
                Node top = open.get(open.size() - 1);
                if (top.depth() >= zone.depth() || top.endNanos() <= zone.startNanos()) {
                    open.remove(open.size() - 1);
                } else {
                    break;
                }
            }
            Node made = new Node(zone.name(), zone.source(), zone.thread(), zone.channel(),
                    zone.startNanos(), zone.endNanos(), zone.depth(), new ArrayList<>());
            if (open.isEmpty()) roots.add(made);
            else open.get(open.size() - 1).children().add(made);
            open.add(made);
        }
    }

    /**
     * Every distinct name in {@code zones}, rolled up and sorted by total cost.
     *
     * <p>Descending by total, ties broken by name ascending — <b>deterministic on purpose</b>, so two
     * runs of the same scene produce byte-identical output and a before-and-after is a {@code diff}
     * rather than a reading exercise.</p>
     */
    public static List<Stat> byCost(List<CgTraceSnapshot.ZoneView> zones) {
        Map<String, long[]> totals = new LinkedHashMap<>();   // count, total, self, min, max
        Map<String, String> sources = new LinkedHashMap<>();
        for (Node root : tree(zones)) accumulate(root, totals, sources);

        List<Stat> out = new ArrayList<>(totals.size());
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            long[] at = entry.getValue();
            out.add(new Stat(entry.getKey(), sources.get(entry.getKey()),
                    (int) at[0], at[1], at[2], at[3], at[4]));
        }
        out.sort(Comparator.comparingLong(Stat::totalNanos).reversed().thenComparing(Stat::name));
        return out;
    }

    private static void accumulate(Node node, Map<String, long[]> totals, Map<String, String> sources) {
        long duration = node.durationNanos();
        long[] at = totals.computeIfAbsent(node.name(),
                key -> new long[] {0L, 0L, 0L, Long.MAX_VALUE, 0L});
        at[0]++;
        at[1] += duration;
        at[2] += node.selfNanos();
        at[3] = Math.min(at[3], duration);
        at[4] = Math.max(at[4], duration);
        sources.putIfAbsent(node.name(), node.source());
        for (Node child : node.children()) accumulate(child, totals, sources);
    }

    /** The sum of the roots — what a share is taken against. @see CgTraceReport */
    public static long totalOf(List<Node> roots) {
        long total = 0L;
        for (Node root : roots) total += root.durationNanos();
        return total;
    }
}
