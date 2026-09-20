package com.crystalgraphics.trace;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The trace engine — one capture path for zones, counters, markers, spans and frames.
 *
 * <p>Records an <b>event stream</b> rather than running totals, which is what makes a frame
 * addressable: a bar in a frame strip can be clicked because frame 412's zones are still there.
 * Statistics are a view over the stream, never the storage.</p>
 *
 * <pre>{@code
 * // Declare a channel per owner, once. Everything on it is off until somebody asks.
 * private static final CgTraceChannel TRACE = CgTrace.channel("mymod.render");
 * private static final int DRAW = CgTrace.name("draw");     // intern once; optional but free
 *
 * void render() {
 *     try (CgTrace.Zone z = CgTrace.zone(TRACE, DRAW)) {
 *         CgTrace.counter(TRACE, "drawcalls", calls);
 *     }
 * }
 *
 * // A host with a frame loop brackets it:
 * CgTrace.frameBegin();   // at the very top
 * CgTrace.frameEnd();     // after the last paint — a MARK, not a close; see below
 * }</pre>
 *
 * <h3>Turning it on</h3>
 *
 * <pre>{@code
 * CgTrace.enable("crystalgui");          // and everything beneath it
 * CgTrace.enable("crystalgraphics.gl");  // or one subsystem
 * CgTrace.snapshot();                    // an immutable read, safe off the frame thread
 * }</pre>
 *
 * <p>Enabling is <b>per channel</b> and live. A channel nobody enabled costs one mask test, so
 * instrumentation can be left in permanently — which is the whole point, since the frames worth
 * measuring are never the ones you predicted.</p>
 *
 * <h3>A frame closes at the NEXT frameBegin</h3>
 *
 * <p>A frame's wall time is the interval to the one after it, so it is not known until that one starts.
 * {@link #frameEnd()} is therefore a <em>mark</em> giving the CPU figure, not a close — and a host that
 * frames without ever painting still reports frames. That is not hypothetical: the end of a frame is
 * the painter's last line, so a test, a headless step or a minimised window would otherwise record
 * nothing at all while a readout sat on "warming up" forever.</p>
 *
 * <h3>What a zone costs</h3>
 *
 * <p>Enabled: two {@code System.nanoTime()} calls, four array writes, no allocation. Disabled: one
 * volatile read and one AND. Names must be <b>constants</b> — a name built per call defeats
 * {@link CgTraceNames}' interning and its source location both.</p>
 *
 * @see CgTraceSnapshot
 */
public final class CgTrace {

    private CgTrace() {
    }

    // ── Channels and the mask ───────────────────────────────────────────────────────────────

    /** A long is 64 channels, which is more than this build will declare. */
    public static final int MAX_CHANNELS = 64;

    private static final Map<String, CgTraceChannel> CHANNELS = new ConcurrentHashMap<>();
    private static final List<CgTraceChannel> ORDER = new CopyOnWriteArrayList<>();

    /**
     * Which channels are recording.
     *
     * <p>Volatile because it is written from a UI thread and read on every hot path; a plain field
     * would let the JIT hoist the test out of a frame loop and the switch-on would never be seen.</p>
     */
    private static volatile long enabledMask;

    /**
     * Registers a channel, or returns the one already registered under {@code name}.
     *
     * <p>Idempotent, so the usual {@code static final} declaration is safe however many classes
     * declare the same channel. Names are dotted and owner-first: {@code crystalgraphics.text},
     * {@code crystalgui.paint}, {@code mymod.worldgen}.</p>
     *
     * <p>A 65th channel is refused rather than silently aliased onto an existing bit — it returns a
     * channel that can never be enabled, so its instrumentation is inert instead of wrong.</p>
     */
    public static CgTraceChannel channel(String name) {
        CgTraceChannel existing = CHANNELS.get(name);
        if (existing != null) return existing;
        synchronized (CHANNELS) {
            existing = CHANNELS.get(name);
            if (existing != null) return existing;
            int index = ORDER.size();
            if (index >= MAX_CHANNELS) {
                // Index 63 is the overflow bucket: recording on it is impossible, which is the honest
                // outcome. Aliasing onto a real bit would attribute somebody else's zones to it.
                CgTraceChannel overflow = new CgTraceChannel(name, MAX_CHANNELS - 1);
                CHANNELS.put(name, overflow);
                return overflow;
            }
            CgTraceChannel made = new CgTraceChannel(name, index);
            CHANNELS.put(name, made);
            ORDER.add(made);
            return made;
        }
    }

    /** Every registered channel, in registration order — the option list a mask control binds to. */
    public static List<CgTraceChannel> channels() {
        return List.copyOf(ORDER);
    }

    public static boolean isEnabled(CgTraceChannel channel) {
        return (enabledMask & channel.bit()) != 0L;
    }

    /** Whether anything at all is recording — the one test a host needs before doing optional work. */
    public static boolean isRecording() {
        return enabledMask != 0L;
    }

    /**
     * Switches on every channel whose name is {@code prefix} or begins {@code prefix + '.'}.
     *
     * <p>So {@code enable("crystalgraphics")} takes the whole of it and
     * {@code enable("crystalgraphics.text")} takes one subsystem. A prefix nothing matches is not an
     * error: a channel registers when its declaring class first loads, which may be later.</p>
     */
    public static void enable(String prefix) {
        setEnabled(prefix, true);
    }

    public static void disable(String prefix) {
        setEnabled(prefix, false);
    }

    /**
     * Switches one channel, by the channel itself.
     *
     * <p><b>Prefer this over the string form when the caller holds the constant.</b> A prefix is
     * matched against channels that have REGISTERED, and a channel registers when its declaring class
     * first loads — so enabling {@code "crystalgui.frame"} before anything has touched the class that
     * declares it matches nothing and silently does nothing. Passing the channel cannot fail that way,
     * because holding one is proof it exists.</p>
     */
    public static synchronized void setEnabled(CgTraceChannel channel, boolean on) {
        long was = enabledMask;
        enabledMask = on ? (was | channel.bit()) : (was & ~channel.bit());
        if (enabledMask != was) markMaskChange();
    }

    public static synchronized void setEnabled(String prefix, boolean on) {
        long bits = 0L;
        for (CgTraceChannel channel : ORDER) {
            if (matches(channel.name(), prefix)) bits |= channel.bit();
        }
        if (bits == 0L) return;
        long was = enabledMask;
        enabledMask = on ? (was | bits) : (was & ~bits);
        if (enabledMask != was) markMaskChange();
    }

    /** The enabled channels, by NAME. What a setting persists — never the mask itself. */
    public static synchronized List<String> enabledNames() {
        List<String> out = new ArrayList<>();
        for (CgTraceChannel channel : ORDER) {
            if (isEnabled(channel)) out.add(channel.name());
        }
        return out;
    }

    /** Replaces the whole enabled set from names, which is how a saved setting is restored. */
    public static synchronized void enableOnly(Iterable<String> names) {
        long bits = 0L;
        for (String name : names) {
            CgTraceChannel channel = CHANNELS.get(name);
            if (channel != null) bits |= channel.bit();
        }
        if (bits == enabledMask) return;
        enabledMask = bits;
        markMaskChange();
    }

    public static synchronized void disableAll() {
        if (enabledMask == 0L) return;
        enabledMask = 0L;
        markMaskChange();
    }

    private static boolean matches(String name, String prefix) {
        return name.equals(prefix)
                || (name.startsWith(prefix) && name.length() > prefix.length()
                    && name.charAt(prefix.length()) == '.');
    }

    /**
     * Records that the recording changed shape.
     *
     * <p>A range that spans one of these is not comparable with itself, and a viewer that did not know
     * would draw the difference as a performance change. So it is an event rather than a silent
     * mutation.</p>
     */
    private static void markMaskChange() {
        if (enabledMask == 0L) return;
        events.marker(CgTraceNames.intern("trace:mask"), MAX_CHANNELS - 1, System.nanoTime(),
                CgTraceNames.intern(String.join(",", enabledNames())));
    }

    // ── Storage ─────────────────────────────────────────────────────────────────────────────

    /** Zones per thread. ~200 a frame means this holds around three hundred frames of one thread. */
    private static final int ZONE_CAPACITY =
            roundUpPowerOfTwo(Integer.getInteger("crystalgraphics.trace.zones", 1 << 16));

    /** Frames held. 600 is five seconds at 120fps, ten at 60. */
    private static final int FRAME_CAPACITY =
            Math.max(2, Integer.getInteger("crystalgraphics.trace.frames", 600));

    private static volatile CgTraceEvents events = new CgTraceEvents(1 << 14, 1 << 12, 1 << 12);

    private static final Map<Thread, CgTraceZones> ARENAS = new ConcurrentHashMap<>();

    private static final ThreadLocal<CgTraceZones> LOCAL = ThreadLocal.withInitial(() -> {
        Thread thread = Thread.currentThread();
        CgTraceZones made = new CgTraceZones(thread.getName(), ARENAS.size(), ZONE_CAPACITY);
        ARENAS.put(thread, made);
        return made;
    });

    /** Every thread that has recorded anything. Read by {@link #snapshot()}. */
    static List<CgTraceZones> arenas() {
        return List.copyOf(ARENAS.values());
    }

    static CgTraceEvents events() {
        return events;
    }

    // ── Zones ───────────────────────────────────────────────────────────────────────────────

    /**
     * A zone's lifetime, as a try-with-resources handle.
     *
     * <p>The handle is a per-thread singleton and allocates nothing: {@link #close()} pops whatever is
     * innermost, and try-with-resources guarantees that is this one. A disabled zone hands back a
     * shared no-op whose close does nothing, so an enabled zone nested inside a disabled one still
     * pairs correctly.</p>
     */
    public static final class Zone implements AutoCloseable {

        final CgTraceZones owner;

        Zone(CgTraceZones owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner != null) owner.pop(System.nanoTime());
        }
    }

    /** What a zone on a channel nobody enabled hands back. */
    private static final Zone NONE = new Zone(null);

    /**
     * This thread's handle, holding this thread's arena.
     *
     * <p>The arena is reached THROUGH the handle rather than through a second thread-local, so an
     * opened zone costs one lookup and not two.</p>
     */
    private static final ThreadLocal<Zone> LOCAL_ZONE =
            ThreadLocal.withInitial(() -> new Zone(LOCAL.get()));

    /** Interns {@code name} and returns its id — the form a hot call site should hold in a constant. */
    public static int name(String name) {
        return CgTraceNames.intern(name);
    }

    public static Zone zone(CgTraceChannel channel, int nameId) {
        if ((enabledMask & channel.bit()) == 0L) return NONE;
        Zone handle = LOCAL_ZONE.get();
        handle.owner.push(nameId, channel.index(), System.nanoTime());
        return handle;
    }

    /** As {@link #zone(CgTraceChannel, int)}, interning on the way. Prefer the id form on a hot path. */
    public static Zone zone(CgTraceChannel channel, String name) {
        if ((enabledMask & channel.bit()) == 0L) return NONE;
        return zone(channel, CgTraceNames.intern(name));
    }

    /**
     * Opens a zone without a handle; pass what this returns to {@link #end(long)}.
     *
     * @return 0 when the channel is not recording, which {@link #end(long)} ignores
     */
    public static long begin(CgTraceChannel channel, int nameId) {
        if ((enabledMask & channel.bit()) == 0L) return 0L;
        long now = System.nanoTime();
        LOCAL_ZONE.get().owner.push(nameId, channel.index(), now);
        // Never 0 — a zone genuinely opened at nanoTime()==0 would otherwise be dropped by end().
        return now == 0L ? 1L : now;
    }

    public static long begin(CgTraceChannel channel, String name) {
        if ((enabledMask & channel.bit()) == 0L) return 0L;
        return begin(channel, CgTraceNames.intern(name));
    }

    /**
     * Records a zone that is already over, from a start stamp taken earlier.
     *
     * <pre>{@code
     * long t = CgTrace.begin();           // or any earlier System.nanoTime()
     * ...
     * CgTrace.zoneDone(CH, "layer:clear", t);
     * }</pre>
     *
     * <p>The faithful translation of an <em>additive bucket</em> — a shape that never had a nesting
     * discipline, so imposing one on it would be inventing structure. It lands at whatever depth is
     * currently open, so it nests properly inside a real zone and reads as a sibling otherwise.</p>
     */
    public static void zoneDone(CgTraceChannel channel, int nameId, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        LOCAL_ZONE.get().owner.record(nameId, channel.index(), startNanos, System.nanoTime());
    }

    public static void zoneDone(CgTraceChannel channel, String name, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        zoneDone(channel, CgTraceNames.intern(name), startNanos);
    }

    /** As {@link #zoneDone(CgTraceChannel, int, long)}, with both ends given — for a synthetic clock. */
    public static void zoneDone(CgTraceChannel channel, String name, long startNanos, long endNanos) {
        if ((enabledMask & channel.bit()) == 0L) return;
        LOCAL_ZONE.get().owner.record(CgTraceNames.intern(name), channel.index(), startNanos, endNanos);
    }

    /** A start stamp for {@link #zoneDone}, or 0 when nothing is recording. */
    public static long begin() {
        return enabledMask == 0L ? 0L : System.nanoTime();
    }

    /** Closes the innermost zone opened by {@link #begin}. A zero token is a no-op. */
    public static void end(long token) {
        if (token == 0L) return;
        LOCAL_ZONE.get().owner.pop(System.nanoTime());
    }

    // ── Counters and markers ────────────────────────────────────────────────────────────────

    /**
     * Records one value of a named counter against the open frame.
     *
     * <p>May be called several times in one frame; min, mean and max are derived on read. That is what
     * makes a separate "sample" concept unnecessary — a distribution is a counter written more than
     * once.</p>
     */
    public static void counter(CgTraceChannel channel, int nameId, long value) {
        if ((enabledMask & channel.bit()) == 0L) return;
        events.counter(nameId, openIndex, value);
    }

    public static void counter(CgTraceChannel channel, String name, long value) {
        if ((enabledMask & channel.bit()) == 0L) return;
        events.counter(CgTraceNames.intern(name), openIndex, value);
    }

    /** An instant: something happened, with no duration. */
    public static void marker(CgTraceChannel channel, String name) {
        if ((enabledMask & channel.bit()) == 0L) return;
        events.marker(CgTraceNames.intern(name), channel.index(), System.nanoTime(), -1);
    }

    /**
     * An instant with an attribution — what a call site was blamed for.
     *
     * <p>"2,143 elements re-matched" is a count; "2,000 of them from {@code Tooltip.reposition:214}" is
     * a fix. {@code detail} is interned, so an attribution repeated every frame costs one lookup.</p>
     */
    public static void marker(CgTraceChannel channel, String name, String detail) {
        if ((enabledMask & channel.bit()) == 0L) return;
        events.marker(CgTraceNames.intern(name), channel.index(), System.nanoTime(),
                detail == null ? -1 : CgTraceNames.intern(detail));
    }

    // ── Spans: the chain that is not a frame ────────────────────────────────────────────────

    /**
     * Opens a span — a nestable interval that is <b>not bound to a frame</b>.
     *
     * <p>For a chain rather than a phase: opening a file is a command, a picker, a search per keystroke,
     * an accept, a dock open, a read and a parse, and what matters is the order and where it stalled.
     * Per-frame buckets lose exactly that, and several of those steps happen in no frame at all.</p>
     *
     * @return an id for {@link #spanEnd(long)}, or -1 when the channel is not recording
     */
    public static long spanBegin(CgTraceChannel channel, String name) {
        if ((enabledMask & channel.bit()) == 0L) return -1L;
        CgTraceZones local = LOCAL.get();
        long id = events.spanBegin(CgTraceNames.intern(name), channel.index(), local.threadId,
                local.currentSpan(), System.nanoTime());
        local.pushSpan(id);
        return id;
    }

    public static void spanEnd(long id) {
        if (id < 0L) return;
        LOCAL.get().popSpan();
        events.spanEnd(id, System.nanoTime());
    }

    /**
     * Records a span that is already over — the shape a {@code step(startedAt, what)} call has.
     *
     * <p>Nests under whatever span is open on this thread, so a sequence of completed steps inside an
     * open chain reads as that chain's children.</p>
     */
    public static void spanDone(CgTraceChannel channel, String name, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        CgTraceZones local = LOCAL.get();
        events.spanDone(CgTraceNames.intern(name), channel.index(), local.threadId,
                local.currentSpan(), startNanos, System.nanoTime());
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────

    private static final CgFrameRecord[] FRAMES = new CgFrameRecord[FRAME_CAPACITY];
    private static long framesWritten;

    private static long openBegin = -1L;
    private static long openIndex = -1L;
    private static long openCpu = CgFrameRecord.ABSENT;
    private static long openGc;
    private static long openDropped;
    private static int openLeaked;

    private static volatile Thread frameThread;

    /**
     * The top of a frame — and the point at which the PREVIOUS frame is committed.
     *
     * <p>Also where a leaked zone is caught: anything still open on this thread is force-closed here,
     * counted, and reported on the frame record. In a depth-carrying arena a zone left open would
     * otherwise shift every zone after it, which reads as a wrong tree rather than as a failure.</p>
     */
    public static void frameBegin() {
        frameBegin(System.nanoTime());
    }

    /**
     * {@link #frameBegin()} at a stated time — for a host with its own clock, and for a test that must
     * assert on a distribution rather than on whatever the machine happened to do.
     */
    public static void frameBegin(long now) {
        if (enabledMask == 0L) return;
        CgTraceZones local = LOCAL.get();
        frameThread = Thread.currentThread();
        if (openBegin >= 0L) {
            openLeaked += local.closeOpen(now);
            commit(now, local);
        }
        openBegin = now;
        openIndex = framesWritten;
        openCpu = CgFrameRecord.ABSENT;
        openGc = gcMillis();
        openDropped = local.dropped;
        openLeaked = 0;
    }

    /**
     * The end of the same frame's work — a <b>mark</b>, not a close.
     *
     * <p>Gives the frame its CPU figure. Its absence costs that one number and nothing else, which is
     * what lets a host whose paint never runs still record frames.</p>
     */
    public static void frameEnd() {
        frameEnd(System.nanoTime());
    }

    /** {@link #frameEnd()} at a stated time. @see #frameBegin(long) */
    public static void frameEnd(long now) {
        if (enabledMask == 0L || openBegin < 0L) return;
        openCpu = now - openBegin;
    }

    private static void commit(long now, CgTraceZones local) {
        CgFrameRecord record = new CgFrameRecord(openIndex, openBegin, now, openCpu,
                CgFrameRecord.ABSENT, Math.max(0L, gcMillis() - openGc), openLeaked,
                Math.max(0L, local.dropped - openDropped));
        FRAMES[(int) (framesWritten % FRAME_CAPACITY)] = record;
        framesWritten++;
    }

    /** The thread that owns the frame loop — the one a viewer draws first. */
    public static Thread frameThread() {
        return frameThread;
    }

    static long framesWritten() {
        return framesWritten;
    }

    /**
     * How many frames have ever been committed — a cheap validity stamp.
     *
     * <p>A readout that asks ten questions per refresh can build its window once and reuse it while
     * this has not moved, instead of walking the ring per question.</p>
     */
    public static long frameCount() {
        return framesWritten;
    }

    /** The counters recorded against {@code frame}. */
    public static List<CgTraceSnapshot.CounterView> countersIn(CgFrameRecord frame) {
        return CgTraceSnapshot.countersOf(frame.index());
    }

    static CgFrameRecord frameAt(long index) {
        return FRAMES[(int) (index % FRAME_CAPACITY)];
    }

    static int frameCapacity() {
        return FRAME_CAPACITY;
    }

    /** Total collector time this JVM has spent, in millis. A pause is charged to whatever was running. */
    static long gcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long spent = collector.getCollectionTime();
            if (spent > 0L) total += spent;
        }
        return total;
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    /** An immutable view of everything held, safe to read off the frame thread. */
    public static CgTraceSnapshot snapshot() {
        return CgTraceSnapshot.of();
    }

    /**
     * Just the frames, oldest first — the cheap read.
     *
     * <p>A readout refreshed ten times a second wants frame times and nothing else, and a full
     * {@link #snapshot()} copies every zone to deliver them. Six hundred record references cost
     * nothing; the zones are fetched only for the frame somebody actually looked at.</p>
     */
    public static List<CgFrameRecord> frames() {
        List<CgFrameRecord> out = new ArrayList<>(FRAME_CAPACITY);
        long oldest = Math.max(0L, framesWritten - FRAME_CAPACITY);
        for (long i = oldest; i < framesWritten; i++) {
            CgFrameRecord record = frameAt(i);
            if (record != null) out.add(record);
        }
        return out;
    }

    /**
     * The zones whose start falls inside {@code frame}, across every thread, ordered by start.
     *
     * <p>Binary search per arena rather than a walk of the whole ring: zones are appended in start
     * order, so the frame's window is a contiguous run and the cost is the run's length rather than the
     * arena's.</p>
     */
    public static List<CgTraceSnapshot.ZoneView> zonesIn(CgFrameRecord frame) {
        return CgTraceSnapshot.zonesOf(frame);
    }

    /** Drops everything recorded. The enabled mask is left alone. */
    public static synchronized void clear() {
        for (int i = 0; i < FRAMES.length; i++) FRAMES[i] = null;
        framesWritten = 0L;
        openBegin = -1L;
        openIndex = -1L;
        openCpu = CgFrameRecord.ABSENT;
        openLeaked = 0;
        ARENAS.clear();
        LOCAL.remove();
        LOCAL_ZONE.remove();
        events = new CgTraceEvents(1 << 14, 1 << 12, 1 << 12);
    }

    /** {@link #clear()} plus every channel off — what a test uses between cases. */
    public static synchronized void resetForTesting() {
        disableAll();
        enabledMask = 0L;
        clear();
    }

    private static int roundUpPowerOfTwo(int value) {
        int at = 1;
        while (at < value && at < (1 << 30)) at <<= 1;
        return at;
    }
}
