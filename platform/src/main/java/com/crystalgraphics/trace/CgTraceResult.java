package com.crystalgraphics.trace;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result file of a bench run: what {@code bench.py} compares with a stored baseline.
 *
 * <pre>{@code
 * String json = CgTraceResult.toJson(CgTrace.snapshot(), "vfx-blasts", "gl-g43", "0123abc", 16_700_000L);
 * Files.writeString(Path.of("result.json"), json);
 * }</pre>
 *
 * <p>Version {@link #VERSION} is JSON with every time as integer nanoseconds and every size as integer bytes, keys in a
 * fixed order and counters sorted by name, so two files of the same run are byte-identical and diff cleanly. A measure
 * no frame has ({@code gpu} before a timer query resolves, {@code directBytes} without the bean) is {@code null},
 * because absent is not zero. Each measure is {@code {count, median, p99, p99.9, max, sum}} over the frames that have
 * it, by {@link CgHitches#percentile} (the median is permille 500). Hitches are counted by
 * {@link CgHitches#find} against the {@code targetNanos} the file records. {@code jvm} counts are sums over the frames
 * and {@code null} where the source cannot see the kind.</p>
 *
 * <p>Easy to get wrong: a reader must refuse a {@code version} it does not know, and a run recorded without the JVM
 * stream compares as {@code null} there, which is a lost measure rather than a pass.</p>
 */
public final class CgTraceResult {

    /** The file format; the first key of every file. */
    public static final int VERSION = 1;

    private static final String[] JVM_KEYS = {"compilations", "deoptimisations", "safepoints", "classLoads"};

    private CgTraceResult() {
    }

    /**
     * @param scene  what ran, such as {@code vfx-blasts}
     * @param cell   the matrix cell it ran in (device, tier, host); a free label
     * @param commit the build
     * @param targetNanos the frame time the run is meant to hold, which defines its hitches
     * @throws IllegalArgumentException if {@code scene}, {@code cell} or {@code commit} is null or empty
     * @throws IllegalStateException    if the snapshot holds no frames
     */
    public static String toJson(CgTraceSnapshot snapshot, String scene, String cell, String commit, long targetNanos) {
        require("scene", scene);
        require("cell", cell);
        require("commit", commit);
        List<CgFrameRecord> frames = snapshot.frames();
        if (frames.isEmpty()) throw new IllegalStateException("no frames in the snapshot: nothing to write");

        long[] wall = new long[frames.size()];
        long[] cpu = new long[frames.size()];
        long[] gpu = new long[frames.size()];
        long[] allocated = new long[frames.size()];
        long[] direct = new long[frames.size()];
        int nCpu = 0;
        int nGpu = 0;
        int nAlloc = 0;
        int nDirect = 0;
        long collections = 0L;
        long pauseMillis = 0L;
        long[] jvm = new long[JVM_KEYS.length];
        boolean[] jvmSeen = new boolean[JVM_KEYS.length];
        for (int i = 0; i < jvmSeen.length; i++) jvmSeen[i] = true;
        for (int i = 0; i < frames.size(); i++) {
            CgFrameRecord frame = frames.get(i);
            wall[i] = frame.wallNanos();
            if (frame.hasCpu()) cpu[nCpu++] = frame.cpuNanos();
            if (frame.hasGpu()) gpu[nGpu++] = frame.gpuNanos();
            if (frame.hasAllocation()) allocated[nAlloc++] = frame.allocatedBytes();
            if (frame.directBytes() != CgFrameRecord.ABSENT) direct[nDirect++] = frame.directBytes();
            collections += frame.gcCollections();
            pauseMillis += frame.gcMillis();
            for (CgJvmEventKind kind : CgJvmEventKind.values()) {
                long count = snapshot.jvmEventCountIn(frame, kind);
                int at = kind.ordinal();
                if (count == CgFrameRecord.ABSENT) jvmSeen[at] = false;
                else jvm[at] += count;
            }
        }

        long ours = 0L;
        long unexplained = 0L;
        List<CgHitch> hitches = CgHitches.find(snapshot, targetNanos);
        for (CgHitch hitch : hitches) {
            if (hitch.ours()) ours++;
            if (!hitch.explained()) unexplained++;
        }

        StringBuilder out = new StringBuilder(2048);
        out.append("{\n  \"version\": ").append(VERSION).append(",\n");
        out.append("  \"scene\": ").append(quote(scene)).append(",\n");
        out.append("  \"cell\": ").append(quote(cell)).append(",\n");
        out.append("  \"commit\": ").append(quote(commit)).append(",\n");
        out.append("  \"targetNanos\": ").append(targetNanos).append(",\n");
        out.append("  \"frames\": ").append(frames.size()).append(",\n");
        out.append("  \"measures\": {\n");
        measure(out, "wall", wall, wall.length, true);
        measure(out, "cpu", cpu, nCpu, true);
        measure(out, "gpu", gpu, nGpu, true);
        measure(out, "allocatedBytes", allocated, nAlloc, true);
        measure(out, "directBytes", direct, nDirect, false);
        out.append("  },\n");
        out.append("  \"hitches\": {\"count\": ").append(hitches.size()).append(", \"ours\": ").append(ours)
                .append(", \"unexplained\": ").append(unexplained).append("},\n");
        out.append("  \"gcCollections\": ").append(collections).append(",\n");
        out.append("  \"gcPauseMillis\": ").append(pauseMillis).append(",\n");
        out.append("  \"jvm\": {");
        for (int i = 0; i < JVM_KEYS.length; i++) {
            if (i > 0) out.append(", ");
            out.append('"').append(JVM_KEYS[i]).append("\": ").append(jvmSeen[i] ? String.valueOf(jvm[i]) : "null");
        }
        out.append("},\n");
        counters(out, snapshot);
        out.append("}\n");
        return out.toString();
    }

    private static void measure(StringBuilder out, String name, long[] values, int count, boolean comma) {
        out.append("    \"").append(name).append("\": ");
        if (count == 0) {
            out.append("null");
        } else {
            long[] used = java.util.Arrays.copyOf(values, count);
            long max = 0L;
            long sum = 0L;
            for (long v : used) {
                max = Math.max(max, v);
                sum += v;
            }
            out.append(String.format(Locale.ROOT,
                    "{\"count\": %d, \"median\": %d, \"p99\": %d, \"p99.9\": %d, \"max\": %d, \"sum\": %d}",
                    count, CgHitches.percentile(used, 500), CgHitches.percentile(used, 990),
                    CgHitches.percentile(used, 999), max, sum));
        }
        out.append(comma ? ",\n" : "\n");
    }

    /** Per counter: the sum over frames, the largest a frame's sum reached, and how many frames wrote it. */
    private static void counters(StringBuilder out, CgTraceSnapshot snapshot) {
        Map<String, Map<Long, Long>> byName = new TreeMap<>();
        for (CgTraceSnapshot.CounterView counter : snapshot.counters()) {
            byName.computeIfAbsent(counter.name(), k -> new TreeMap<>()).merge(counter.frameIndex(), counter.value(), Long::sum);
        }
        if (byName.isEmpty()) {
            out.append("  \"counters\": {}\n");
            return;
        }
        out.append("  \"counters\": {\n");
        int left = byName.size();
        for (Map.Entry<String, Map<Long, Long>> each : byName.entrySet()) {
            long sum = 0L;
            long max = Long.MIN_VALUE;
            for (long v : each.getValue().values()) {
                sum += v;
                max = Math.max(max, v);
            }
            out.append("    ").append(quote(each.getKey())).append(": {\"sum\": ").append(sum).append(", \"max\": ")
                    .append(max).append(", \"frames\": ").append(each.getValue().size()).append('}')
                    .append(--left > 0 ? ",\n" : "\n");
        }
        out.append("  }\n");
    }

    private static void require(String what, String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(what + " must be a non-empty name");
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
