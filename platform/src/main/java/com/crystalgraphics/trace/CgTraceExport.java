package com.crystalgraphics.trace;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a snapshot as a Chrome JSON trace — the format {@code ui.perfetto.dev} opens directly.
 *
 * <pre>{@code
 * try (Writer out = Files.newBufferedWriter(path)) {
 *     CgTraceExport.writeChromeJson(out, CgTrace.snapshot());
 * }
 * }</pre>
 *
 * <h3>Why this is worth a few hundred lines</h3>
 *
 * <p>It buys a mature viewer, a SQL query engine and a file that can be attached to a bug report,
 * without any of them being written here. It is also what makes the tooling usable before this
 * project's own window exists.</p>
 *
 * <h3>An export, never the storage</h3>
 *
 * <p>Emitting this shape live was considered and rejected: it means building a string per zone, which
 * is an order of magnitude past what {@link CgTrace}'s hot path is allowed to cost. JSON is larger and
 * slower to write than a binary format and neither matters for a five-second window written when a
 * human asks.</p>
 *
 * <h3>The mapping</h3>
 *
 * <table border="1">
 *   <caption>Ours to Chrome's</caption>
 *   <tr><th>Ours</th><th>Chrome</th><th>Note</th></tr>
 *   <tr><td>Zone</td><td>{@code X} — a complete slice</td><td>{@code tid} is the thread, {@code cat} the channel</td></tr>
 *   <tr><td>Frame</td><td>{@code X} on a thread of its own</td><td>Named {@code Frame N}, so the strip reads as a track</td></tr>
 *   <tr><td>Counter</td><td>{@code C}</td><td>One counter track per name</td></tr>
 *   <tr><td>Marker</td><td>{@code I}</td><td>Process-scoped, so it draws across the tracks</td></tr>
 *   <tr><td>Span</td><td>{@code b}/{@code e} — async</td><td>On its own track, because a chain outlives a frame</td></tr>
 * </table>
 *
 * <p><b>Timestamps are microseconds</b>, which is the format's unit, and are made relative to the
 * earliest event so a trace opens at zero rather than at some enormous absolute.</p>
 */
public final class CgTraceExport {

    private CgTraceExport() {
    }

    /** The synthetic thread id the frame track is emitted on. Above any real thread's. */
    private static final int FRAME_TID = 1_000_000;

    /** And the one spans go on, for the same reason. */
    private static final int SPAN_TID = 1_000_001;

    private static final int PID = 1;

    public static void writeChromeJson(Writer out, CgTraceSnapshot snapshot) throws IOException {
        writeChromeJson(out, snapshot, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Writes the frames whose index falls in {@code [fromFrame, toFrame]} and everything inside them.
     *
     * <p>Zones are taken by TIME rather than by frame index, so a worker's zone that overlaps the
     * range is included even though it belongs to no frame — which is the point of having it.</p>
     */
    public static void writeChromeJson(Writer out, CgTraceSnapshot snapshot,
                                       long fromFrame, long toFrame) throws IOException {
        List<CgFrameRecord> frames = snapshot.frames().stream()
                .filter(f -> f.index() >= fromFrame && f.index() <= toFrame)
                .toList();
        long first = earliest(snapshot, frames);

        out.write("{\"displayTimeUnit\":\"ms\",\"traceEvents\":[\n");
        Emitter emit = new Emitter(out);

        emit.metadata(PID, FRAME_TID, "thread_name", "Frames");
        emit.metadata(PID, SPAN_TID, "thread_name", "Chains");

        Map<String, Integer> threadIds = new HashMap<>();
        for (CgFrameRecord frame : frames) {
            emit.complete("Frame " + frame.index(), "frame", PID, FRAME_TID,
                    micros(frame.beginNanos() - first), micros(frame.wallNanos()),
                    frame.hasCpu() ? "{\"cpu_ms\":" + round(frame.cpuMillis()) + "}" : null);
        }

        long windowFrom = frames.isEmpty() ? Long.MIN_VALUE : frames.get(0).beginNanos();
        long windowTo = frames.isEmpty() ? Long.MAX_VALUE : frames.get(frames.size() - 1).endNanos();

        for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) {
            if (zone.isOpen()) continue;
            if (zone.startNanos() < windowFrom || zone.startNanos() > windowTo) continue;
            int tid = threadIds.computeIfAbsent(zone.thread(), name -> {
                int id = threadIds.size() + 1;
                try {
                    emit.metadata(PID, id, "thread_name", name);
                } catch (IOException failed) {
                    throw new java.io.UncheckedIOException(failed);
                }
                return id;
            });
            emit.complete(zone.name(), zone.channel(), PID, tid,
                    micros(zone.startNanos() - first), micros(zone.durationNanos()),
                    zone.source() == null ? null : "{\"src\":\"" + escape(zone.source()) + "\"}");
        }

        // COUNTERS ARE STAMPED WITH THEIR FRAME'S START, because a counter is recorded against a frame
        // rather than at an instant -- so the track lines up with the frame that produced it.
        Map<Long, CgFrameRecord> byIndex = new HashMap<>();
        for (CgFrameRecord frame : frames) byIndex.put(frame.index(), frame);
        for (CgTraceSnapshot.CounterView counter : snapshot.counters()) {
            CgFrameRecord frame = byIndex.get(counter.frameIndex());
            if (frame == null) continue;
            emit.counter(counter.name(), PID, micros(frame.beginNanos() - first), counter.value());
        }

        for (CgTraceSnapshot.MarkerView marker : snapshot.markers()) {
            if (marker.nanos() < windowFrom || marker.nanos() > windowTo) continue;
            emit.instant(marker.name(), marker.channel(), PID, micros(marker.nanos() - first),
                    marker.detail() == null ? null : "{\"detail\":\"" + escape(marker.detail()) + "\"}");
        }

        for (CgTraceSnapshot.SpanView span : snapshot.spans()) {
            if (span.isOpen()) continue;
            if (span.endNanos() < windowFrom || span.startNanos() > windowTo) continue;
            emit.async('b', span.name(), span.channel(), PID, SPAN_TID, span.id(),
                    micros(span.startNanos() - first));
            emit.async('e', span.name(), span.channel(), PID, SPAN_TID, span.id(),
                    micros(span.endNanos() - first));
        }

        out.write("\n]}");
        out.flush();
    }

    /**
     * The earliest nanosecond in the export.
     *
     * <p>Everything is written relative to it so a trace opens at zero. {@code System.nanoTime()} has
     * an arbitrary origin — often a large negative — and a viewer handed those draws a timeline
     * starting decades ago.</p>
     */
    private static long earliest(CgTraceSnapshot snapshot, List<CgFrameRecord> frames) {
        long first = Long.MAX_VALUE;
        for (CgFrameRecord frame : frames) first = Math.min(first, frame.beginNanos());
        for (CgTraceSnapshot.ZoneView zone : snapshot.zones()) first = Math.min(first, zone.startNanos());
        for (CgTraceSnapshot.SpanView span : snapshot.spans()) first = Math.min(first, span.startNanos());
        return first == Long.MAX_VALUE ? 0L : first;
    }

    private static double micros(long nanos) {
        return nanos / 1000d;
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /** Writes the events, keeping track of whether a comma is owed. */
    private static final class Emitter {

        private final Writer out;
        private boolean any;

        Emitter(Writer out) {
            this.out = out;
        }

        private void comma() throws IOException {
            if (any) out.write(",\n");
            any = true;
        }

        void complete(String name, String category, int pid, int tid,
                      double ts, double dur, String args) throws IOException {
            comma();
            out.write("{\"ph\":\"X\",\"name\":\"" + escape(name) + "\",\"cat\":\"" + escape(category)
                    + "\",\"pid\":" + pid + ",\"tid\":" + tid
                    + ",\"ts\":" + round(ts) + ",\"dur\":" + round(dur));
            if (args != null) out.write(",\"args\":" + args);
            out.write('}');
        }

        void counter(String name, int pid, double ts, long value) throws IOException {
            comma();
            out.write("{\"ph\":\"C\",\"name\":\"" + escape(name) + "\",\"pid\":" + pid
                    + ",\"ts\":" + round(ts) + ",\"args\":{\"value\":" + value + "}}");
        }

        void instant(String name, String category, int pid, double ts, String args) throws IOException {
            comma();
            // SCOPE "p" -- process-wide, so the viewer draws it as a line across every track rather
            // than as a tick on one. A marker is about the frame, not about a thread.
            out.write("{\"ph\":\"i\",\"s\":\"p\",\"name\":\"" + escape(name) + "\",\"cat\":\""
                    + escape(category) + "\",\"pid\":" + pid + ",\"ts\":" + round(ts));
            if (args != null) out.write(",\"args\":" + args);
            out.write('}');
        }

        void async(char phase, String name, String category, int pid, int tid, long id, double ts)
                throws IOException {
            comma();
            out.write("{\"ph\":\"" + phase + "\",\"name\":\"" + escape(name) + "\",\"cat\":\""
                    + escape(category) + "\",\"pid\":" + pid + ",\"tid\":" + tid
                    + ",\"id\":" + id + ",\"ts\":" + round(ts) + '}');
        }

        void metadata(int pid, int tid, String name, String value) throws IOException {
            comma();
            out.write("{\"ph\":\"M\",\"pid\":" + pid + ",\"tid\":" + tid + ",\"name\":\"" + name
                    + "\",\"args\":{\"name\":\"" + escape(value) + "\"}}");
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char at = text.charAt(i);
            switch (at) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (at < 0x20) out.append(String.format("\\u%04x", (int) at));
                    else out.append(at);
                }
            }
        }
        return out.toString();
    }
}
