package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An immutable read of everything the engine holds — frames, zones, counters, markers and spans.
 *
 * <pre>{@code
 * CgTraceSnapshot snap = CgTrace.snapshot();
 * CgFrameRecord worst = snap.worstFrame();
 * for (CgTraceSnapshot.ZoneView zone : snap.zonesIn(worst)) {
 *     System.out.printf("%-24s %6.2fms  %s%n",
 *             zone.name(), zone.millis(), zone.source());
 * }
 * }</pre>
 *
 * <h3>Taken off the frame thread, on purpose</h3>
 *
 * <p>Every renderer above this — a HUD, a text report, a window, an exporter — reads a snapshot rather
 * than the live arenas, so a value cannot change between two rows of the same table. Snapshots are
 * <b>best-effort consistent</b>: a zone being written while this is taken may be missing or still open,
 * which is the same bargain any sampling read of a running system makes and is why a snapshot is cheap
 * enough to take ten times a second.</p>
 *
 * <h3>Zones are attributed to a frame by time</h3>
 *
 * <p>A zone belongs to the frame its <em>start</em> falls inside. For a worker that is a lie in the
 * general case and the right lie here: it is what makes "a decompiler ran on a background thread during
 * this frame" visible against the frame it hurt.</p>
 */
public final class CgTraceSnapshot {

    /** One zone, resolved: names, not ids. */
    public record ZoneView(String name, String source, String thread, String channel,
                           int depth, long startNanos, long endNanos) {

        /** Still running when the snapshot was taken. */
        public boolean isOpen() {
            return endNanos == CgTraceZones.OPEN;
        }

        public long durationNanos() {
            return isOpen() ? 0L : endNanos - startNanos;
        }

        public double millis() {
            return durationNanos() / 1_000_000d;
        }
    }

    public record CounterView(String name, long frameIndex, long value) {
    }

    public record MarkerView(String name, String channel, long nanos, String detail) {
    }

    /** A chain's step. @see CgTrace#spanBegin */
    public record SpanView(long id, long parent, String name, String source, String thread,
                           String channel, long startNanos, long endNanos) {

        public boolean isOpen() {
            return endNanos == CgTraceEvents.OPEN;
        }

        public long durationNanos() {
            return isOpen() ? 0L : endNanos - startNanos;
        }

        public double millis() {
            return durationNanos() / 1_000_000d;
        }
    }

    private final List<CgFrameRecord> frames;
    private final List<ZoneView> zones;
    private final List<CounterView> counters;
    private final List<MarkerView> markers;
    private final List<SpanView> spans;
    private final long droppedZones;

    private CgTraceSnapshot(List<CgFrameRecord> frames, List<ZoneView> zones,
                            List<CounterView> counters, List<MarkerView> markers,
                            List<SpanView> spans, long droppedZones) {
        this.frames = List.copyOf(frames);
        this.zones = List.copyOf(zones);
        this.counters = List.copyOf(counters);
        this.markers = List.copyOf(markers);
        this.spans = List.copyOf(spans);
        this.droppedZones = droppedZones;
    }

    static CgTraceSnapshot of() {
        return of(true);
    }

    /**
     * @param withZones false leaves {@link #zones()} empty — for a reader that fetches zones per frame
     *                  through {@link CgTrace#zonesBetween}, where copying the whole ring would cost more
     *                  than everything else in the snapshot put together
     */
    static CgTraceSnapshot of(boolean withZones) {
        String[] channelNames = channelNames();

        List<CgFrameRecord> frames = CgTrace.frames();

        List<ZoneView> zones = new ArrayList<>();
        long dropped = 0L;
        for (CgTraceZones arena : CgTrace.arenas()) {
            // READ THE WATERMARK FIRST. Anything the owning thread writes after this point is simply
            // not in the snapshot, which is correct; reading it last would let the bound run ahead of
            // the data and hand back a half-written zone.
            CgTraceZones.Store held = arena.store;
            long high = arena.highFor(held);
            dropped += arena.dropped;
            if (!withZones) continue;
            long from = Math.max(0L, high - held.capacity);
            for (long slot = from; slot < high; slot++) {
                int packed = held.packed(slot);
                int nameId = held.nameId(slot);
                zones.add(new ZoneView(
                        CgTraceNames.nameOf(nameId),
                        CgTraceNames.sourceOf(nameId),
                        arena.threadName,
                        channelNames[CgTraceZones.channelOf(packed)],
                        CgTraceZones.depthOf(packed),
                        held.start(slot),
                        held.end(slot)));
            }
        }
        zones.sort(Comparator.comparingLong(ZoneView::startNanos));

        CgTraceEvents events = CgTrace.events();
        List<CounterView> counters = new ArrayList<>();
        List<MarkerView> markers = new ArrayList<>();
        List<SpanView> spans = new ArrayList<>();
        // THE FIRST FRAMES' COUNTERS, from their own store: the ring's would have overwritten them.
        readCounters(CgTrace.headEvents(), counters, -1L);
        // ONE LOCK ACROSS ALL THREE. Taken separately, a counter could be read from before a write
        // and a marker from after it, and the snapshot would describe a moment that never existed --
        // which is the one thing a snapshot is for.
        synchronized (events) {
            for (long slot = events.oldestCounter(); slot < events.countersWritten; slot++) {
                int at = events.counterAt(slot);
                counters.add(new CounterView(CgTraceNames.nameOf(events.counterName[at]),
                        events.counterFrame[at], events.counterValue[at]));
            }
            for (long slot = events.oldestMarker(); slot < events.markersWritten; slot++) {
                int at = events.markerAt(slot);
                int detail = events.markerDetail[at];
                markers.add(new MarkerView(CgTraceNames.nameOf(events.markerName[at]),
                        channelNames[events.markerChannel[at]], events.markerNanos[at],
                        detail < 0 ? null : CgTraceNames.nameOf(detail)));
            }
            for (long slot = events.oldestSpan(); slot < events.spansWritten; slot++) {
                int at = events.spanAt(slot);
                int nameId = events.spanName[at];
                spans.add(new SpanView(slot, events.spanParent[at],
                        CgTraceNames.nameOf(nameId), CgTraceNames.sourceOf(nameId),
                        String.valueOf(events.spanThread[at]),
                        channelNames[events.spanChannel[at]],
                        events.spanStartNanos[at], events.spanEndNanos[at]));
            }
        }

        return new CgTraceSnapshot(frames, zones, counters, markers, spans, dropped);
    }

    /**
     * The zones of one frame, without building a whole snapshot.
     *
     * <p>Zones are appended in start order within an arena, so a frame's window is a contiguous run —
     * found by binary search and walked until it ends. That is what makes this affordable for a readout
     * that asks ten times a second, where {@link #of()} copying every zone in the ring is not.</p>
     */
    static List<ZoneView> zonesOf(CgFrameRecord frame) {
        return zonesBetween(frame.beginNanos(), frame.endNanos());
    }

    /** Every zone starting in {@code [fromNanos, toNanos)}, across every thread, ordered by start. */
    static List<ZoneView> zonesBetween(long fromNanos, long toNanos) {
        String[] channelNames = channelNames();
        List<ZoneView> out = new ArrayList<>();
        for (CgTraceZones arena : CgTrace.arenas()) {
            CgTraceZones.Store held = arena.store;
            long high = arena.highFor(held);
            long low = Math.max(0L, high - held.capacity);
            long at = firstAtOrAfter(held, low, high, fromNanos);
            for (long slot = at; slot < high; slot++) {
                long start = held.start(slot);
                if (start >= toNanos) break;
                if (start < fromNanos) continue;
                int packed = held.packed(slot);
                int nameId = held.nameId(slot);
                out.add(new ZoneView(
                        CgTraceNames.nameOf(nameId),
                        CgTraceNames.sourceOf(nameId),
                        arena.threadName,
                        channelNames[CgTraceZones.channelOf(packed)],
                        CgTraceZones.depthOf(packed),
                        start,
                        held.end(slot)));
            }
        }
        out.sort(Comparator.comparingLong(ZoneView::startNanos));
        return out;
    }

    /** The counters written against one frame index, without building a whole snapshot. */
    static List<CounterView> countersOf(long frameIndex) {
        List<CounterView> out = new ArrayList<>();
        readCounters(frameIndex < CgTrace.firstFrames() ? CgTrace.headEvents() : CgTrace.events(), out, frameIndex);
        return out;
    }

    /** Every counter in {@code events}, or only {@code frameIndex}'s when it is not -1. */
    private static void readCounters(CgTraceEvents events, List<CounterView> out, long frameIndex) {
        synchronized (events) {
            for (long slot = events.oldestCounter(); slot < events.countersWritten; slot++) {
                int at = events.counterAt(slot);
                if (frameIndex >= 0L && events.counterFrame[at] != frameIndex) continue;
                out.add(new CounterView(CgTraceNames.nameOf(events.counterName[at]),
                        events.counterFrame[at], events.counterValue[at]));
            }
        }
    }

    /** Markers stamped in {@code [fromNanos, toNanos)}, oldest first. */
    static List<MarkerView> markersBetween(long fromNanos, long toNanos) {
        String[] channelNames = channelNames();
        CgTraceEvents events = CgTrace.events();
        List<MarkerView> out = new ArrayList<>();
        synchronized (events) {
            for (long slot = events.oldestMarker(); slot < events.markersWritten; slot++) {
                int at = events.markerAt(slot);
                long nanos = events.markerNanos[at];
                if (nanos < fromNanos || nanos >= toNanos) continue;
                int detail = events.markerDetail[at];
                out.add(new MarkerView(CgTraceNames.nameOf(events.markerName[at]),
                        channelNames[events.markerChannel[at]], nanos,
                        detail < 0 ? null : CgTraceNames.nameOf(detail)));
            }
        }
        return out;
    }

    /** The first slot in {@code [low, high)} whose start is at or after {@code nanos}. */
    private static long firstAtOrAfter(CgTraceZones.Store held, long low, long high, long nanos) {
        while (low < high) {
            long mid = low + ((high - low) >>> 1);
            if (held.start(mid) < nanos) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static String[] channelNames() {
        String[] names = new String[CgTrace.MAX_CHANNELS];
        for (int i = 0; i < names.length; i++) names[i] = "?";
        for (CgTraceChannel channel : CgTrace.channels()) names[channel.index()] = channel.name();
        return names;
    }

    /** Oldest first. */
    public List<CgFrameRecord> frames() {
        return frames;
    }

    /** Every zone held, ordered by start. */
    public List<ZoneView> zones() {
        return zones;
    }

    public List<CounterView> counters() {
        return counters;
    }

    public List<MarkerView> markers() {
        return markers;
    }

    public List<SpanView> spans() {
        return spans;
    }

    /** Zones lost to a full arena. A non-zero here must be shown, not swallowed. */
    public long droppedZones() {
        return droppedZones;
    }

    /** The zones whose start falls inside {@code frame}, ordered by start. */
    public List<ZoneView> zonesIn(CgFrameRecord frame) {
        List<ZoneView> out = new ArrayList<>();
        for (ZoneView zone : zones) {
            if (frame.contains(zone.startNanos())) out.add(zone);
        }
        return out;
    }

    public List<CounterView> countersIn(CgFrameRecord frame) {
        List<CounterView> out = new ArrayList<>();
        for (CounterView counter : counters) {
            if (counter.frameIndex() == frame.index()) out.add(counter);
        }
        return out;
    }

    /** The frame with that index, or null when it has aged out of the ring. */
    public CgFrameRecord frame(long index) {
        for (CgFrameRecord record : frames) {
            if (record.index() == index) return record;
        }
        return null;
    }

    /** The slowest frame by wall time, or null when none is held. */
    public CgFrameRecord worstFrame() {
        CgFrameRecord worst = null;
        for (CgFrameRecord record : frames) {
            if (worst == null || record.wallNanos() > worst.wallNanos()) worst = record;
        }
        return worst;
    }
}
