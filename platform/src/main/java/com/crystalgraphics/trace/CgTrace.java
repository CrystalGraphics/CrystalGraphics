package com.crystalgraphics.trace;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * Prefixes enabled by name, applied to channels that register afterwards. Guarded by the class.
     * ABOVE every channel declaration: {@link #channel} reads it, and {@link #TRACE} is one.
     */
    private static final Set<String> STANDING = new LinkedHashSet<>();

    private static final Map<String, CgTraceChannel> CHANNELS = new ConcurrentHashMap<>();
    private static final List<CgTraceChannel> ORDER = new CopyOnWriteArrayList<>();

    /**
     * The engine's own channel — what it records ABOUT a recording.
     *
     * <p>A mask change, and anything else this class has to say for itself. Registered here rather
     * than borrowed from the overflow bucket, so these events carry a name a reader can filter on
     * instead of appearing under {@code ?}.</p>
     */
    public static final CgTraceChannel TRACE = channel("trace");

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
            if (isEngineOwn(name)) metaMask |= made.bit();
            // AN ENABLED PREFIX IS A STANDING RULE. A channel registers when its declaring class first
            // loads, which is routinely after somebody asked for its owner at startup.
            synchronized (CgTrace.class) {
                for (String prefix : STANDING) {
                    if (matches(name, prefix)) {
                        long was = enabledMask;
                        enabledMask = was | made.bit();
                        if (enabledMask != was) markMaskChange();
                        break;
                    }
                }
            }
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
     * Switches on every channel whose name is {@code prefix} or begins {@code prefix + '.'} — now, and
     * any that registers later.
     *
     * <p>So {@code enable("crystalgraphics")} takes the whole of it and
     * {@code enable("crystalgraphics.text")} takes one subsystem. A channel registers when its declaring
     * class first loads, which is often after startup; the prefix stands until disabled, so enabling at
     * launch records channels that did not exist yet.</p>
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
        if (on) {
            STANDING.add(prefix);
        } else {
            STANDING.removeIf(held -> matches(held, prefix));
        }
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
        STANDING.clear();
        for (String name : names) STANDING.add(name);
        if (bits == enabledMask) return;
        enabledMask = bits;
        markMaskChange();
    }

    public static synchronized void disableAll() {
        STANDING.clear();
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
        if (enabledMask == 0L) {
            markedMask = 0L;
            return;
        }
        // THE ENGINE'S OWN CHANNEL IS ON WHENEVER ANYTHING IS. It carries this very event, which is
        // written unconditionally because a recording that changed shape must say so -- and a meta
        // file listing `trace` as "not recording" beside a mask marker it had just recorded would be
        // exactly the kind of quiet lie the self-describing header exists to prevent.
        enabledMask |= TRACE.bit();
        // THE ENGINE'S OWN CHANNELS DO NOT CHANGE WHAT IS MEASURED. A viewer switching on the channel its
        // own work is recorded on would otherwise mark every frame before it as incomparable -- opening
        // the profiler would grey out the strip it opened on.
        long measured = enabledMask & ~metaMask;
        if (measured == markedMask) return;
        markedMask = measured;
        events.marker(CgTraceNames.intern("trace:mask"), TRACE.index(), System.nanoTime(),
                CgTraceNames.intern(String.join(",", enabledNames())));
    }

    /**
     * Whether {@code name} is one of the engine's own channels — {@code trace}, or beneath it, such as a
     * viewer's {@code trace.viewer}. What they record is ABOUT a recording, so switching one does not
     * change what the recording measures.
     */
    public static boolean isEngineOwn(String name) {
        return matches(name, TRACE_NAME);
    }

    private static final String TRACE_NAME = "trace";

    /** Bits of the engine's own channels. No initialiser: {@link #channel} fills it during class init. */
    private static volatile long metaMask;
    /** The measured channels the last mask marker recorded. Guarded by the class. */
    private static long markedMask;

    // ── Storage ─────────────────────────────────────────────────────────────────────────────

    /**
     * Frames kept from the START of a recording, never overwritten. 0 keeps none. Set by {@link #configure}.
     *
     * <p>A ring of the newest frames loses the first ones a few seconds in, and the first ones are often
     * the ones worth having: startup, the first open of a window, the first time a cache is cold.</p>
     */
    private static volatile int firstFrames = Math.max(0, Integer.getInteger("crystalgraphics.trace.firstFrames", 0));

    /** Newest frames kept after the first ones, overwriting the oldest. 0 stops once the first are full. */
    private static volatile int newestFrames = Math.max(0, Integer.getInteger("crystalgraphics.trace.frames", 600));

    /** Zones per thread at most, for the newest frames and for the first ones separately. */
    private static volatile int zoneCapacity =
            roundUpPowerOfTwo(Integer.getInteger("crystalgraphics.trace.zones", 1 << 16));
    private static volatile int headZoneCapacity = 1 << 12;

    /** Where a new thread's arena starts; it doubles as the thread records, up to its ceiling. */
    private static final int INITIAL_ZONES = 1 << 12;

    /**
     * Whether the first frames are all recorded. Until then every thread records into its HEAD arena,
     * which is never overwritten; after, each moves to its ring arena at its next zone with nothing open.
     */
    private static volatile boolean headSealed = firstFrames == 0;

    /**
     * Bumped by {@link #clear()} and {@link #configure}. A thread whose handle carries an older one makes
     * itself a new arena on its next zone — on its OWN thread, which is the only thread allowed to write
     * one. Clearing used to drop every arena from the registry and hand a fresh one to the calling
     * thread alone, so every other thread went on writing into an arena no snapshot could see.
     */
    private static volatile int generation;

    private static volatile CgTraceEvents events = newEvents(newestFrames);
    /** Counters of the first frames, kept apart so the ring cannot overwrite them. Markers and spans stay in {@link #events}. */
    private static volatile CgTraceEvents headEvents = newEvents(firstFrames);

    /** Counters are written per frame, so a ring is sized from its frames: 32 a frame. */
    private static CgTraceEvents newEvents(int frames) {
        int counters = roundUpPowerOfTwo(Math.max(1 << 12, frames * 32));
        return new CgTraceEvents(counters, 1 << 12, 1 << 12);
    }

    /**
     * Sizes what is kept: the first frames of a recording, the newest after them, and the zones per frame
     * each may hold. <b>Clears what is recorded.</b>
     *
     * <pre>{@code
     * CgTrace.configure(600, 600, 256);      // the first 600 frames for good, and the newest 600 after them
     * CgTrace.configure(0, 600, 256);        // the newest 600 only
     * CgTrace.configure(10_000, 0, 256);     // the first 10,000, then recording stops
     * }</pre>
     *
     * <p>Between the first frames and the newest there is a gap once more than both have been recorded;
     * {@link #frames()} simply skips it, and a frame's {@link CgFrameRecord#index()} says where it fell.
     * The zone ceiling is not an allocation: an arena starts small and doubles as its thread records.</p>
     */
    public static synchronized void configure(int first, int newest, int zonesPerFrame) {
        firstFrames = Math.max(0, first);
        newestFrames = Math.max(0, newest);
        if (firstFrames == 0 && newestFrames == 0) newestFrames = 2;
        int perFrame = Math.max(16, zonesPerFrame);
        zoneCapacity = zoneCeiling((long) newestFrames * perFrame);
        headZoneCapacity = zoneCeiling((long) firstFrames * perFrame);
        clear();
    }

    private static int zoneCeiling(long zones) {
        return roundUpPowerOfTwo((int) Math.max(INITIAL_ZONES, Math.min(1L << 26, zones)));
    }

    /** The per-thread zone ceiling for the newest frames. */
    public static int zoneCapacity() {
        return zoneCapacity;
    }

    /** The per-thread zone ceiling for the first frames. */
    public static int headZoneCapacity() {
        return headZoneCapacity;
    }

    public static int firstFrames() {
        return firstFrames;
    }

    public static int newestFrames() {
        return newestFrames;
    }

    private static final Map<Thread, CgTraceZones> ARENAS = new ConcurrentHashMap<>();
    /** Each thread's arena for the first frames. Kept after they are sealed: it is what holds them. */
    private static final Map<Thread, CgTraceZones> HEAD_ARENAS = new ConcurrentHashMap<>();

    /** Every arena that holds anything, the first frames' among them. Read by {@link #snapshot()}. */
    static List<CgTraceZones> arenas() {
        List<CgTraceZones> all = new ArrayList<>(HEAD_ARENAS.values());
        all.addAll(ARENAS.values());
        return all;
    }

    static CgTraceEvents events() {
        return events;
    }

    static CgTraceEvents headEvents() {
        return headEvents;
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
        final int generation;
        /** Whether {@link #owner} is this thread's arena for the first frames. */
        final boolean head;

        Zone(CgTraceZones owner, int generation, boolean head) {
            this.owner = owner;
            this.generation = generation;
            this.head = head;
        }

        @Override
        public void close() {
            if (owner != null) owner.pop(System.nanoTime());
        }
    }

    /** What a zone on a channel nobody enabled hands back. */
    private static final Zone NONE = new Zone(null, -1, false);

    /**
     * This thread's handle, holding this thread's arena.
     *
     * <p><b>The only thread-local here.</b> The arena is reached through the handle
     * ({@code LOCAL_ZONE.get().owner}) rather than through a second one, so an opened zone costs one
     * lookup and not two — and spans, which are rare enough not to care, still go through the same
     * door so there is one definition of "this thread's state".</p>
     */
    private static final ThreadLocal<Zone> LOCAL_ZONE = ThreadLocal.withInitial(CgTrace::newHandle);

    private static Zone newHandle() {
        Thread thread = Thread.currentThread();
        CgTraceZones head = HEAD_ARENAS.get(thread);
        // ONE THREAD, ONE ID, across its two arenas: a viewer groups by thread and must not see two.
        int id = head != null ? head.threadId : ARENAS.size() + HEAD_ARENAS.size();
        if (!headSealed) {
            int max = headZoneCapacity;
            CgTraceZones made = new CgTraceZones(thread.getName(), id, Math.min(INITIAL_ZONES, max), max, true);
            HEAD_ARENAS.put(thread, made);
            return new Zone(made, generation, true);
        }
        int max = zoneCapacity;
        // WITH NO NEWEST FRAMES, nothing may wrap: recording stops once the first are full.
        CgTraceZones made = new CgTraceZones(thread.getName(), id, Math.min(INITIAL_ZONES, max), max,
                newestFrames == 0);
        ARENAS.put(thread, made);
        return new Zone(made, generation, false);
    }

    /**
     * This thread's handle, replaced first if a clear or a resize has happened since it was made — or if
     * the first frames are complete and this thread is between zones, when it moves to its ring arena.
     * Only between zones: a zone opened in one arena must close in it.
     */
    private static Zone handle() {
        Zone current = LOCAL_ZONE.get();
        if (current.generation == generation && !(current.head && headSealed && current.owner.depth() == 0)) {
            return current;
        }
        Zone fresh = newHandle();
        LOCAL_ZONE.set(fresh);
        return fresh;
    }

    /** This thread's arena. */
    private static CgTraceZones local() {
        return handle().owner;
    }

    /** Interns {@code name} and returns its id — the form a hot call site should hold in a constant. */
    public static int name(String name) {
        return CgTraceNames.intern(name);
    }

    public static Zone zone(CgTraceChannel channel, int nameId) {
        if ((enabledMask & channel.bit()) == 0L) return NONE;
        Zone handle = handle();
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
        handle().owner.push(nameId, channel.index(), now);
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
     * long t = System.nanoTime();
     * ...
     * CgTrace.zoneDone(CH, "layer:clear", t);
     * }</pre>
     *
     * <p><b>Not paired with {@link #end(long)}</b>, which closes a zone {@link #begin} opened. This
     * takes a plain stamp and writes a whole zone; there is nothing on the stack to pop.</p>
     *
     * <p>The faithful translation of an <em>additive bucket</em> — a shape that never had a nesting
     * discipline, so imposing one on it would be inventing structure. It lands at whatever depth is
     * currently open, so it nests properly inside a real zone and reads as a sibling otherwise.</p>
     */
    public static void zoneDone(CgTraceChannel channel, int nameId, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        handle().owner.record(nameId, channel.index(), startNanos, System.nanoTime());
    }

    public static void zoneDone(CgTraceChannel channel, String name, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        zoneDone(channel, CgTraceNames.intern(name), startNanos);
    }

    /** As {@link #zoneDone(CgTraceChannel, int, long)}, with both ends given — for a synthetic clock. */
    public static void zoneDone(CgTraceChannel channel, String name, long startNanos, long endNanos) {
        if ((enabledMask & channel.bit()) == 0L) return;
        handle().owner.record(CgTraceNames.intern(name), channel.index(), startNanos, endNanos);
    }

    /** Closes the innermost zone opened by {@link #begin}. A zero token is a no-op. */
    public static void end(long token) {
        if (token == 0L) return;
        handle().owner.pop(System.nanoTime());
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
        long frame = openIndex;
        (frame < firstFrames ? headEvents : events).counter(nameId, frame, value);
    }

    public static void counter(CgTraceChannel channel, String name, long value) {
        if ((enabledMask & channel.bit()) == 0L) return;
        counter(channel, CgTraceNames.intern(name), value);
    }

    /**
     * A counter written against {@code frameIndex} rather than the frame open now — for a caller that
     * tallies a frame's worth of increments and flushes the total once the frame has moved on.
     *
     * <pre>{@code
     * long frame = CgTrace.currentFrameIndex();
     * // ... many increments tallied locally ...
     * if (CgTrace.currentFrameIndex() != frame) CgTrace.counterAt(CHANNEL, HITS, frame, tally);
     * }</pre>
     */
    public static void counterAt(CgTraceChannel channel, int nameId, long frameIndex, long value) {
        if ((enabledMask & channel.bit()) == 0L || frameIndex < 0L) return;
        (frameIndex < firstFrames ? headEvents : events).counter(nameId, frameIndex, value);
    }

    /** The frame being recorded, or -1 before the first {@link #frameBegin}. */
    public static long currentFrameIndex() {
        return openIndex;
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
        markerAt(channel, name, detail, System.nanoTime());
    }

    /**
     * {@link #marker(CgTraceChannel, String, String)} at a stated time.
     *
     * <p>For a host with its own clock, and for a test: an event recorded on the real clock while the
     * frames around it are synthetic falls outside every window that would show it, which is a silent
     * omission rather than a failure.</p>
     */
    public static void markerAt(CgTraceChannel channel, String name, String detail, long nanos) {
        if ((enabledMask & channel.bit()) == 0L) return;
        events.marker(CgTraceNames.intern(name), channel.index(), nanos,
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
        return spanBeginAt(channel, name, System.nanoTime());
    }

    /** {@link #spanBegin} at a stated time. @see #markerAt */
    public static long spanBeginAt(CgTraceChannel channel, String name, long nanos) {
        if ((enabledMask & channel.bit()) == 0L) return -1L;
        CgTraceZones local = local();
        long id = events.spanBegin(CgTraceNames.intern(name), channel.index(), local.threadId,
                local.currentSpan(), nanos);
        local.pushSpan(id);
        return id;
    }

    public static void spanEnd(long id) {
        spanEndAt(id, System.nanoTime());
    }

    /** {@link #spanEnd} at a stated time. @see #markerAt */
    public static void spanEndAt(long id, long nanos) {
        if (id < 0L) return;
        local().popSpan();
        events.spanEnd(id, nanos);
    }

    /**
     * Records a span that is already over — the shape a {@code step(startedAt, what)} call has.
     *
     * <p>Nests under whatever span is open on this thread, so a sequence of completed steps inside an
     * open chain reads as that chain's children.</p>
     */
    public static void spanDone(CgTraceChannel channel, String name, long startNanos) {
        if ((enabledMask & channel.bit()) == 0L || startNanos == 0L) return;
        CgTraceZones local = local();
        events.spanDone(CgTraceNames.intern(name), channel.index(), local.threadId,
                local.currentSpan(), startNanos, System.nanoTime());
    }

    // ── Frames ──────────────────────────────────────────────────────────────────────────────

    /** The newest frames, a ring. Replaced whole by {@link #configure}; readers index the array they hold. */
    private static volatile CgFrameRecord[] FRAMES = new CgFrameRecord[Math.max(1, newestFrames)];
    /** The first frames, in order, never overwritten. */
    private static volatile CgFrameRecord[] HEAD = new CgFrameRecord[firstFrames];
    private static long framesWritten;

    // ── Stopping by itself ──────────────────────────────────────────────────────────────────

    /** Why recording last stopped by itself, or null. Cleared when anything re-enables a channel. */
    private static volatile String stopReason;
    /** The channels that were on when it stopped, so a viewer's Record can put exactly those back. */
    private static volatile List<String> stoppedChannels = List.of();

    private static volatile long hitchNanos;
    private static volatile int framesAfterHitch;
    /** Frames still to record after a hitch before stopping; -1 while nothing has tripped. */
    private static int hitchCountdown = -1;
    private static long hitchFrame = -1L;

    /**
     * Stops recording a set number of frames after the first frame slower than {@code thresholdNanos}.
     *
     * <pre>{@code
     * CgTrace.stopAfterHitch(100_000_000L, 60);   // a 100 ms frame, then sixty more, then stop
     * CgTrace.stopAfterHitch(0L, 0);               // off
     * }</pre>
     *
     * <p>What keeps a hitch on screen: in a ring of the newest frames it is overwritten a few seconds
     * after it happens, which is usually before anybody has opened a window to look at it.</p>
     */
    public static synchronized void stopAfterHitch(long thresholdNanos, int framesAfter) {
        hitchNanos = Math.max(0L, thresholdNanos);
        framesAfterHitch = Math.max(0, framesAfter);
        hitchCountdown = -1;
        hitchFrame = -1L;
    }

    /** Why recording stopped by itself — "kept the first 10000 frames" — or null if it did not. */
    public static String stopReason() {
        return stopReason;
    }

    /** What was recording when it stopped by itself; empty otherwise. */
    public static List<String> stoppedChannels() {
        return stoppedChannels;
    }

    /** The frame that tripped {@link #stopAfterHitch}, or -1. */
    public static long hitchFrame() {
        return hitchFrame;
    }

    private static synchronized void stopBecause(String reason) {
        stoppedChannels = enabledNames();
        stopReason = reason;
        disableAll();
    }

    private static long openBegin = -1L;

    /**
     * The frame a counter is attributed to.
     *
     * <p>Volatile because {@link #counter} reads it from whatever thread recorded the count, while
     * only the frame thread writes it. The rest of the open-frame state below is the frame thread's
     * alone; this is the one field that crosses.</p>
     */
    private static volatile long openIndex = -1L;
    private static long openCpu = CgFrameRecord.ABSENT;
    private static long openGc;
    private static long openGcCount;
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
        CgGpuTrace.closeLeaked();
        CgTraceZones local = local();
        frameThread = Thread.currentThread();
        if (openBegin >= 0L) {
            openLeaked += local.closeOpen(now);
            commit(now, local);
        }
        openBegin = now;
        openIndex = framesWritten;
        openCpu = CgFrameRecord.ABSENT;
        openGc = gcMillis();
        openGcCount = gcCount();
        openDropped = local.dropped;
        openLeaked = 0;
        // After the boundary moves, so the frame just committed counts as closed to the GPU track.
        CgGpuTrace.collect();
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
                CgFrameRecord.ABSENT, Math.max(0L, gcMillis() - openGc),
                (int) Math.max(0L, gcCount() - openGcCount), openLeaked,
                Math.max(0L, local.dropped - openDropped));
        CgFrameRecord[] head = HEAD;
        if (framesWritten < head.length) {
            head[(int) framesWritten] = record;
        } else {
            // NOTHING AFTER THE FIRST FRAMES, whoever switched a channel back on: there is nowhere to
            // put it that is not one of the frames kept. Only a clear starts again.
            if (newestFrames == 0) {
                stopBecause(keptFirst(head.length));
                return;
            }
            CgFrameRecord[] ring = FRAMES;
            ring[(int) ((framesWritten - head.length) % ring.length)] = record;
        }
        framesWritten++;
        if (!headSealed && framesWritten >= head.length) headSealed = true;

        if (hitchCountdown >= 0) {
            if (hitchCountdown-- == 0) {
                stopBecause(String.format("stopped %d frames after a %.1f ms frame", framesAfterHitch,
                        frameAt(hitchFrame) == null ? 0d : frameAt(hitchFrame).wallMillis()));
            }
        } else if (hitchNanos > 0L && record.wallNanos() > hitchNanos) {
            hitchFrame = record.index();
            hitchCountdown = framesAfterHitch - 1;
            if (hitchCountdown < 0) {
                stopBecause(String.format("stopped on a %.1f ms frame", record.wallMillis()));
            }
        }
        if (isFull()) stopBecause(keptFirst(head.length));
    }

    private static String keptFirst(int frames) {
        return "kept the first " + frames + " frames";
    }

    /** Whether the first frames are full and no newest are kept — recording starts again only after a {@link #clear()}. */
    public static boolean isFull() {
        return newestFrames == 0 && firstFrames > 0 && framesWritten >= firstFrames;
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

    /**
     * The ring's CURRENT record for frame {@code index}, or null once it has been overwritten — for a
     * viewer holding an older snapshot, whose copy predates a GPU figure that has since landed.
     */
    public static CgFrameRecord frame(long index) {
        return frameAt(index);
    }

    static int generation() {
        return generation;
    }

    /** Fills in a committed frame's GPU time. A frame already overwritten is skipped. */
    static synchronized void setGpu(long index, long gpuNanos) {
        CgFrameRecord was = frameAt(index);
        if (was == null) return;
        CgFrameRecord now = new CgFrameRecord(was.index(), was.beginNanos(), was.endNanos(), was.cpuNanos(),
                gpuNanos, was.gcMillis(), was.gcCollections(), was.leakedZones(), was.droppedZones());
        CgFrameRecord[] head = HEAD;
        if (index < head.length) {
            head[(int) index] = now;
        } else {
            CgFrameRecord[] ring = FRAMES;
            ring[(int) ((index - head.length) % ring.length)] = now;
        }
    }

    static CgFrameRecord frameAt(long index) {
        if (index < 0L) return null;
        CgFrameRecord[] head = HEAD;
        CgFrameRecord record;
        if (index < head.length) {
            record = head[(int) index];
        } else {
            CgFrameRecord[] ring = FRAMES;
            record = ring[(int) ((index - head.length) % ring.length)];
        }
        return record != null && record.index() == index ? record : null;
    }

    /** How many frames are kept at most: the first ones and the newest together. */
    public static int frameCapacity() {
        return firstFrames + newestFrames;
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

    /** Collections this JVM has run, across every collector. */
    static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            if (count > 0L) total += count;
        }
        return total;
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    /**
     * Zones lost to a full arena, across every thread.
     *
     * <p>The cheap read. {@link CgTraceSnapshot#droppedZones()} answers the same number after copying
     * every zone in the ring to get it, which is the wrong price for a figure that belongs in a
     * one-line header.</p>
     */
    public static long droppedZones() {
        long total = 0L;
        for (CgTraceZones arena : arenas()) total += arena.dropped;
        return total;
    }

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
        long written = framesWritten;
        int first = HEAD.length;
        List<CgFrameRecord> out = new ArrayList<>(Math.min((int) Math.min(written, Integer.MAX_VALUE), frameCapacity()));
        for (long i = 0L; i < Math.min(first, written); i++) {
            CgFrameRecord record = frameAt(i);
            if (record != null) out.add(record);
        }
        if (newestFrames > 0) {
            // THE GAP is simply skipped: the next frame's index says how many fell between.
            for (long i = Math.max(first, written - FRAMES.length); i < written; i++) {
                CgFrameRecord record = frameAt(i);
                if (record != null) out.add(record);
            }
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

    /** The markers recorded during {@code frame} — what a hint reads a blamed call site from. */
    public static List<CgTraceSnapshot.MarkerView> markersIn(CgFrameRecord frame) {
        return CgTraceSnapshot.markersBetween(frame.beginNanos(), frame.endNanos());
    }

    /**
     * {@code thread}'s closed zones that started at or after {@code fromNanos}, oldest first — what a
     * per-thread report is rebuilt from.
     */
    public static List<CgTraceSnapshot.ZoneView> zonesOfThread(Thread thread, long fromNanos) {
        List<CgTraceZones> arenas = new ArrayList<>(2);
        CgTraceZones head = HEAD_ARENAS.get(thread);
        if (head != null) arenas.add(head);
        CgTraceZones ring = ARENAS.get(thread);
        if (ring != null) arenas.add(ring);
        return CgTraceSnapshot.closedZonesSince(arenas, fromNanos);
    }

    /** Every zone starting in {@code [fromNanos, toNanos)} — a range of frames without a whole snapshot. */
    public static List<CgTraceSnapshot.ZoneView> zonesBetween(long fromNanos, long toNanos) {
        return CgTraceSnapshot.zonesBetween(fromNanos, toNanos);
    }

    /**
     * Frames, counters, markers and spans, with {@link CgTraceSnapshot#zones()} left EMPTY.
     *
     * <p>What a viewer refreshing several times a second wants. A full {@link #snapshot()} copies every
     * zone held, which at ten thousand frames is millions of objects per call — the viewer would become
     * the lag it is measuring. Fetch a frame's zones with {@link #zonesIn} or {@link #zonesBetween}.</p>
     */
    public static CgTraceSnapshot frameSnapshot() {
        return CgTraceSnapshot.of(false);
    }

    /** Drops everything recorded. The enabled mask is left alone. */
    public static synchronized void clear() {
        FRAMES = new CgFrameRecord[Math.max(1, newestFrames)];
        HEAD = new CgFrameRecord[firstFrames];
        headSealed = firstFrames == 0;
        framesWritten = 0L;
        stopReason = null;
        stoppedChannels = List.of();
        hitchCountdown = -1;
        hitchFrame = -1L;
        openBegin = -1L;
        openIndex = -1L;
        openCpu = CgFrameRecord.ABSENT;
        openLeaked = 0;
        ARENAS.clear();
        HEAD_ARENAS.clear();
        // EVERY THREAD makes itself a new arena on its next zone. @see #generation
        generation++;
        events = newEvents(newestFrames);
        headEvents = newEvents(firstFrames);
        // Indices start again from 0, so an image kept would sit under a different frame.
        CgFrameImages.clear();
    }

    /** {@link #clear()} plus every channel off — what a test uses between cases. */
    public static synchronized void resetForTesting() {
        CgTraceNames.resetFirstSeen();
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
