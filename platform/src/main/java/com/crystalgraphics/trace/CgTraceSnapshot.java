package com.crystalgraphics.trace;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.RandomAccess;
import java.util.concurrent.atomic.AtomicLong;

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
    /** The counter views an {@link #incremental} snapshot carries forward; null on any other. */
    private final CounterRun headRun, ringRun;

    private CgTraceSnapshot(List<CgFrameRecord> frames, List<ZoneView> zones,
                            List<CounterView> counters, List<MarkerView> markers,
                            List<SpanView> spans, long droppedZones) {
        this.frames = List.copyOf(frames);
        this.zones = List.copyOf(zones);
        this.counters = List.copyOf(counters);
        this.markers = List.copyOf(markers);
        this.spans = List.copyOf(spans);
        this.droppedZones = droppedZones;
        this.headRun = null;
        this.ringRun = null;
    }

    /** An {@link #incremental} snapshot: the counters are windows over views earlier snapshots made, not a copy. */
    private CgTraceSnapshot(List<CgFrameRecord> frames, CounterRun headRun, CounterRun ringRun,
                            List<MarkerView> markers, List<SpanView> spans, long droppedZones) {
        this.frames = Collections.unmodifiableList(frames);
        this.zones = List.of();
        this.counters = new Joined<>(headRun.view(), ringRun.view());
        this.markers = Collections.unmodifiableList(markers);
        this.spans = Collections.unmodifiableList(spans);
        this.droppedZones = droppedZones;
        this.headRun = headRun;
        this.ringRun = ringRun;
    }

    /**
     * A frame snapshot that carries {@code previous}'s counter views forward: only the counters written since
     * are copied, so a reader refreshing on a clock pays for the frames since its last look, not for the ring.
     * Zones are left out, as in {@link #of(boolean) of(false)}; markers and spans, which are few, are copied.
     *
     * <p>Snapshots in one chain share the storage behind their counters, and the next call appends past the
     * last one's window without moving anything in it. So the calls must not overlap, one at a time on any
     * thread; a snapshot handed to another thread through a queue or a lock may be read there while the next
     * call runs.</p>
     *
     * @param previous the reader's last snapshot, or null; one this method did not make is not reused
     */
    static CgTraceSnapshot incremental(CgTraceSnapshot previous) {
        String[] channelNames = channelNames();
        List<CgFrameRecord> frames = CgTrace.frames();
        long dropped = 0L;
        for (CgTraceZones arena : CgTrace.arenas()) dropped += arena.dropped;

        // Counted, not zoned: a zone here carries the real clock into whatever arena the caller writes, and a
        // caller on its own clock (a test, a replay) then holds zones out of start order. The viewer times it.
        int[] copied = {0};
        CounterRun head = advance(previous == null ? null : previous.headRun, CgTrace.headEvents(), copied);
        CgTraceEvents events = CgTrace.events();
        List<MarkerView> markers = new ArrayList<>();
        List<SpanView> spans = new ArrayList<>();
        CounterRun ring;
        // ONE LOCK across the ring's counters, markers and spans, as in of(): one moment, not three.
        synchronized (events) {
            ring = advance(previous == null ? null : previous.ringRun, events, copied);
            readMarkersAndSpans(events, channelNames, markers, spans);
        }
        CgTrace.add(CgTrace.TRACE, "trace-snapshot-copied", copied[0]);
        return new CgTraceSnapshot(frames, head, ring, markers, spans, dropped);
    }

    /**
     * One event store's counters as views, from write {@code seqFrom}: {@code column[from, to)}. Snapshots
     * share a column and each holds its own window, so appending for the next never disturbs the last.
     */
    private record CounterRun(CgTraceEvents store, Column column, int from, int to, long seqFrom) {
        List<CounterView> view() {
            return new Window(column, from, to);
        }

        /** This run's counters from {@code frameIndex}, by a scan of plain longs rather than of the views. */
        void countersIn(long frameIndex, List<CounterView> out) {
            for (int i = from; i < to; i++) {
                if (column.frameAt(i) == frameIndex) out.add(column.get(i));
            }
        }
    }

    /**
     * Views and their frame indices, appended in fixed chunks under a directory sized once: nothing written is
     * ever moved, so a snapshot handed to another thread reads its window while the next one appends past it.
     */
    private static final class Column {
        private static final int SHIFT = 12;
        private static final int CHUNK = 1 << SHIFT;

        private final CounterView[][] views;
        private final long[][] frames;
        /** Read and written by the appending thread only; a reader goes by its run's {@code to}. */
        private int size;

        Column(long capacity) {
            int chunks = (int) Math.max(1L, Math.min(1 << 16, (capacity + CHUNK - 1) / CHUNK));
            views = new CounterView[chunks][];
            frames = new long[chunks][];
        }

        boolean full() {
            return size >= views.length << SHIFT;
        }

        void add(CounterView view, long frameIndex) {
            int chunk = size >>> SHIFT;
            if (views[chunk] == null) {
                views[chunk] = new CounterView[CHUNK];
                frames[chunk] = new long[CHUNK];
            }
            views[chunk][size & (CHUNK - 1)] = view;
            frames[chunk][size & (CHUNK - 1)] = frameIndex;
            size++;
        }

        CounterView get(int index) {
            return views[index >>> SHIFT][index & (CHUNK - 1)];
        }

        long frameAt(int index) {
            return frames[index >>> SHIFT][index & (CHUNK - 1)];
        }

        /** {@code [from, size)} of this column in a new one, for a run that has outgrown or mostly aged out of it. */
        Column tail(int from, long capacity) {
            Column kept = new Column(capacity);
            for (int i = from; i < size; i++) kept.add(get(i), frameAt(i));
            return kept;
        }
    }

    /** Past this many dead views at the front, and half the column, the next run starts a fresh column. */
    private static final int COMPACT_AFTER = 4096;

    /**
     * {@code run} advanced to what {@code store} holds now, adding only the writes since. Keyed on the write
     * SEQUENCE rather than on slots, since a wrapped store rewrites a slot in place. Starts over when the store
     * was replaced (a clear or a reconfigure), when the run is not the column's newest (another reader
     * advanced it), or when everything it held has since been overwritten.
     */
    private static CounterRun advance(CounterRun run, CgTraceEvents store, int[] copied) {
        synchronized (store) {
            long oldest = store.oldestCounter();
            long written = store.countersWritten;
            // Room for a full store behind the live window and a full store of new writes ahead of it.
            long room = 3L * store.counterCapacity + COMPACT_AFTER;
            Column column;
            int from;
            long seqFrom;
            if (run == null || run.store() != store || run.column().size != run.to()
                    || run.seqFrom() + (run.to() - run.from()) < oldest) {
                column = new Column(room);
                from = 0;
                seqFrom = oldest;
            } else {
                column = run.column();
                int aged = (int) Math.max(0L, oldest - run.seqFrom());
                from = run.from() + aged;
                seqFrom = run.seqFrom() + aged;
            }
            for (long slot = seqFrom + (column.size - from); slot < written; slot++) {
                if (column.full()) {
                    column = column.tail(from, room);
                    from = 0;
                }
                int at = store.counterAt(slot);
                column.add(new CounterView(CgTraceNames.nameOf(store.counterName[at]),
                        store.counterFrame[at], store.counterValue[at]), store.counterFrame[at]);
                copied[0]++;
            }
            if (from > COMPACT_AFTER && from > column.size / 2) {
                column = column.tail(from, room);
                from = 0;
            }
            return new CounterRun(store, column, from, column.size, seqFrom);
        }
    }

    /** {@code column[from, to)}, read-only, reading through rather than copying. */
    private static final class Window extends AbstractList<CounterView> implements RandomAccess {
        private final Column column;
        private final int from, to;

        Window(Column column, int from, int to) {
            this.column = column;
            this.from = from;
            this.to = to;
        }

        @Override public CounterView get(int index) {
            if (index < 0 || index >= to - from) throw new IndexOutOfBoundsException(index + " of " + (to - from));
            return column.get(from + index);
        }

        @Override public int size() {
            return to - from;
        }
    }

    /** {@code first} then {@code second}, read-only. */
    private static final class Joined<T> extends AbstractList<T> implements RandomAccess {
        private final List<T> first, second;

        Joined(List<T> first, List<T> second) {
            this.first = first;
            this.second = second;
        }

        @Override public T get(int index) {
            return index < first.size() ? first.get(index) : second.get(index - first.size());
        }

        @Override public int size() {
            return first.size() + second.size();
        }
    }

    static CgTraceSnapshot of() {
        return of(true);
    }

    /**
     * A snapshot of records that did not come from this process's ring — a trace file read back in.
     *
     * <pre>{@code
     * CgTraceSnapshot loaded = CgTraceSnapshot.of(frames, zones, counters, markers, List.of());
     * List<CgTraceSnapshot.ZoneView> first = loaded.zonesIn(loaded.frames().get(0));
     * }</pre>
     *
     * <p>Pass frames oldest first and zones by start, as a live snapshot holds them: {@link #zonesIn} and
     * every reader above it assume both orders. A zone's {@code depth} may be 0 throughout, since
     * {@link CgTraceAggregate} nests by containment.</p>
     */
    public static CgTraceSnapshot of(List<CgFrameRecord> frames, List<ZoneView> zones,
                                     List<CounterView> counters, List<MarkerView> markers,
                                     List<SpanView> spans) {
        return new CgTraceSnapshot(frames, zones, counters, markers, spans, 0L);
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
            ReadSlots read = new ReadSlots(zones);
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
                read.add(slot);
            }
            read.dropOverwritten(arena, held);
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
            readMarkersAndSpans(events, channelNames, markers, spans);
        }
        // After the lock: a counter written inside it would be the snapshot measuring itself.
        CgTrace.add(CgTrace.TRACE, "trace-snapshot-counters", counters.size());

        return new CgTraceSnapshot(frames, zones, counters, markers, spans, dropped);
    }

    /** Every marker and span {@code events} holds. The caller holds {@code events}' lock. */
    private static void readMarkersAndSpans(CgTraceEvents events, String[] channelNames,
                                            List<MarkerView> markers, List<SpanView> spans) {
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
            ReadSlots read = new ReadSlots(out);
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
                read.add(slot);
            }
            read.dropOverwritten(arena, held);
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

    /** The closed zones in {@code arenas} starting at or after {@code fromNanos}, ordered by start. */
    static List<ZoneView> closedZonesSince(List<CgTraceZones> arenas, long fromNanos) {
        String[] channelNames = channelNames();
        List<ZoneView> out = new ArrayList<>();
        for (CgTraceZones arena : arenas) {
            CgTraceZones.Store held = arena.store;
            long high = arena.highFor(held);
            long low = Math.max(0L, high - held.capacity);
            ReadSlots read = new ReadSlots(out);
            for (long slot = firstAtOrAfter(held, low, high, fromNanos); slot < high; slot++) {
                long end = held.end(slot);
                if (end == CgTraceZones.OPEN) continue;
                int packed = held.packed(slot);
                int nameId = held.nameId(slot);
                out.add(new ZoneView(CgTraceNames.nameOf(nameId), CgTraceNames.sourceOf(nameId),
                        arena.threadName, channelNames[CgTraceZones.channelOf(packed)],
                        CgTraceZones.depthOf(packed), held.start(slot), end));
                read.add(slot);
            }
            read.dropOverwritten(arena, held);
        }
        out.sort(Comparator.comparingLong(ZoneView::startNanos));
        return out;
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

    /**
     * The slots one arena's scan read, in order, so the scan can be validated after it: the owning thread may
     * have lapped the ring meanwhile, and a slot it reached again was read torn.
     */
    /** Zones the torn-zone backstop dropped: 0 unless the watermark check let one through. A test's tally. */
    static final AtomicLong BACKSTOP_DROPPED = new AtomicLong();

    private static final class ReadSlots {
        private final List<ZoneView> out;
        private final int first;
        private long[] slots = new long[64];
        private int count;

        ReadSlots(List<ZoneView> out) {
            this.out = out;
            this.first = out.size();
        }

        void add(long slot) {
            if (count == slots.length) slots = Arrays.copyOf(slots, count * 2);
            slots[count++] = slot;
        }

        /** Drops the views read from slots the writer may have overwritten during the scan: always a prefix. */
        void dropOverwritten(CgTraceZones arena, CgTraceZones.Store held) {
            // PLUS ONE on a rolling arena: the owner fills slot `written` before it publishes it, so the slot it
            // may be overwriting right now is a lap behind the next one. A keep-first arena never overwrites.
            long safe = arena.firstUnoverwritten(held);
            int stale = 0;
            while (stale < count && slots[stale] < safe) stale++;
            if (stale > 0) out.subList(first, first + stale).clear();
            // No load-load fence on Java 8: a reordered read can pass the check above, and this is the backstop.
            List<ZoneView> mine = out.subList(first, out.size());
            int before = mine.size();
            if (mine.removeIf(ReadSlots::torn)) BACKSTOP_DROPPED.addAndGet(before - mine.size());
        }

        private static boolean torn(ZoneView zone) {
            return !zone.isOpen() && zone.endNanos() < zone.startNanos();
        }
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
        if (headRun != null) {
            headRun.countersIn(frame.index(), out);
            ringRun.countersIn(frame.index(), out);
            return out;
        }
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
