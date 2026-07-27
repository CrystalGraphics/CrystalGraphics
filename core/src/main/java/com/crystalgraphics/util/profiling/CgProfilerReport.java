package com.crystalgraphics.util.profiling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable snapshot of one thread's accumulated {@link CgProfiler} state — produced by
 * {@link CgProfiler#endFrame()}, {@link CgProfiler#report()}, or {@link CgProfiler#reportAllThreads()}.
 *
 * @param threadName the thread this snapshot was captured from
 * @param scopes     every recorded scope path, in pre-order tree sequence — see {@link ScopeEntry}
 * @param counters   flat named event counts, sorted by name
 * @param samples    flat named numeric-value summaries, sorted by name
 */
public record CgProfilerReport(String threadName, List<ScopeEntry> scopes,
                                Map<String, Long> counters, Map<String, SampleSummary> samples) {

    /**
     * One scope's stats after reconstructing the parent/child tree from its full
     * {@code "/"}-joined path (e.g. {@code "draw/resolve/flatten"}).
     *
     * @param path       the full path
     * @param depth      nesting depth (a root-level scope is depth 0)
     * @param totalNanos inclusive time — this scope plus every nested child, summed across all
     *                   {@code push()}/{@code pop()} pairs recorded at this exact path
     * @param selfNanos  {@code totalNanos} minus the sum of direct children's {@code totalNanos}
     *                   — the time spent in this scope's own body, excluding nested scopes
     * @param callCount  number of {@code push()}/{@code pop()} pairs recorded at this path
     * @param maxNanos   the single slowest individual call recorded at this path
     */
    public record ScopeEntry(String path, int depth, long totalNanos, long selfNanos, long callCount, long maxNanos) {
        /** The path's last segment, e.g. {@code "flatten"} for {@code "draw/resolve/flatten"}. */
        public String name() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /**
     * @param count number of {@link CgProfiler#sample(String, double)} calls recorded under this name
     * @param sum   sum of every recorded value
     * @param min   smallest recorded value
     * @param max   largest recorded value
     * @param last  most recently recorded value
     */
    public record SampleSummary(long count, double sum, double min, double max, double last) {
        public double avg() {
            return count == 0 ? 0.0 : sum / count;
        }
    }

    static CgProfilerReport build(String threadName, Map<String, CgProfiler.ScopeAccum> scopeStats,
                                   Map<String, Long> counters, Map<String, CgProfiler.SampleAccum> samples) {
        List<ScopeEntry> entries = buildTree(scopeStats);

        Map<String, Long> countersCopy = new TreeMap<>(counters);

        Map<String, SampleSummary> samplesCopy = new TreeMap<>();
        for (Map.Entry<String, CgProfiler.SampleAccum> e : samples.entrySet()) {
            CgProfiler.SampleAccum a = e.getValue();
            samplesCopy.put(e.getKey(), new SampleSummary(a.count, a.sum, a.min, a.max, a.last));
        }

        return new CgProfilerReport(threadName, entries,
                Collections.unmodifiableMap(countersCopy), Collections.unmodifiableMap(samplesCopy));
    }

    /**
     * Reconstructs the parent/child tree from {@code "/"}-joined path keys and walks it
     * depth-first (lexicographic order on the path strings already guarantees a parent sorts
     * immediately before its own children) so {@link #format()} can print a readable indented
     * call tree with correct self-time per node.
     */
    private static List<ScopeEntry> buildTree(Map<String, CgProfiler.ScopeAccum> scopeStats) {
        List<String> paths = new ArrayList<>(scopeStats.keySet());
        Collections.sort(paths);

        List<ScopeEntry> result = new ArrayList<>(paths.size());
        for (String path : paths) {
            CgProfiler.ScopeAccum self = scopeStats.get(path);

            long childTotal = 0L;
            String prefix = path + "/";
            for (String other : paths) {
                // A direct child's path is "prefix" + exactly one more segment (no further "/").
                if (other.startsWith(prefix) && other.indexOf('/', prefix.length()) < 0) {
                    childTotal += scopeStats.get(other).totalNanos;
                }
            }

            int depth = 0;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') depth++;
            }

            result.add(new ScopeEntry(path, depth, self.totalNanos, self.totalNanos - childTotal,
                    self.callCount, self.maxNanos));
        }
        return Collections.unmodifiableList(result);
    }

    /** Human-readable indented call tree + counters + samples — e.g. for logging once per frame. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("CgProfiler[").append(threadName).append("]\n");

        if (!scopes.isEmpty()) {
            sb.append("  scopes:\n");
            for (ScopeEntry s : scopes) {
                sb.append("    ");
                for (int i = 0; i < s.depth(); i++) sb.append("  ");
                sb.append(s.name())
                        .append(" - total ").append(formatMillis(s.totalNanos())).append("ms")
                        .append(", self ").append(formatMillis(s.selfNanos())).append("ms")
                        .append(", calls ").append(s.callCount())
                        .append(", max ").append(formatMillis(s.maxNanos())).append("ms\n");
            }
        }

        if (!counters.isEmpty()) {
            sb.append("  counters:\n");
            for (Map.Entry<String, Long> e : counters.entrySet()) {
                sb.append("    ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            }
        }

        if (!samples.isEmpty()) {
            sb.append("  samples:\n");
            for (Map.Entry<String, SampleSummary> e : samples.entrySet()) {
                SampleSummary s = e.getValue();
                sb.append("    ").append(e.getKey())
                        .append(" - count ").append(s.count())
                        .append(", avg ").append(formatDecimal(s.avg()))
                        .append(", min ").append(formatDecimal(s.min()))
                        .append(", max ").append(formatDecimal(s.max()))
                        .append(", last ").append(formatDecimal(s.last()))
                        .append('\n');
            }
        }

        return sb.toString();
    }

    private static String formatMillis(long nanos) {
        return formatDecimal(nanos / 1_000_000.0);
    }

    private static String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
