package com.crystalgraphics.trace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The name table: every zone, counter, marker and span name resolved to a small {@code int}, once,
 * together with the source location it was first used from.
 *
 * <pre>{@code
 * int id = CgTraceNames.intern("paint:tree");
 * CgTraceNames.nameOf(id);     // "paint:tree"
 * CgTraceNames.sourceOf(id);   // "render/CgUiPaintContext.java:838", or null
 * }</pre>
 *
 * <h3>Why an int and not the string</h3>
 *
 * <p>A zone record is four primitive values wide and is written on the hot path. Storing a reference
 * would make every zone a GC root and every arena a scanned object array; storing an {@code int} keeps
 * the arenas primitive, which is what lets six hundred frames of history cost a few megabytes.</p>
 *
 * <h3>The source location is what makes a report jumpable</h3>
 *
 * <p>Resolved <b>once per name, at intern time</b> — a few hundred stack walks over the life of a
 * process, never on a hot path — and in exchange every line of every report can end in a
 * {@code File.java:line} the reader can click. {@code "layer:clear 9.8ms"} is a fact;
 * {@code "layer:clear 9.8ms render/CgUiPaintContext.java:1402"} is a next step.</p>
 *
 * <p>Tracy gets this from a C++ macro at the call site. In Java it is a one-time cost paid the first
 * time a name is seen, which is why names must be <b>constants</b>: a name built per call would walk
 * the stack per call and the location would be worthless anyway.</p>
 */
public final class CgTraceNames {

    private CgTraceNames() {
    }

    /** Interned names, indexed by id. Append-only, so a reader never needs a lock. */
    private static final List<String> NAMES = new ArrayList<>(256);

    /** {@code id -> "dir/File.java:line"}, or absent where the walk found nothing of ours. */
    private static final List<String> SOURCES = new ArrayList<>(256);

    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>(256);

    /**
     * The engine's own classes, whose frames are this machinery rather than its caller.
     *
     * <p>By CLASS and not by package: a package prefix would also swallow a legitimate caller that
     * happens to live here, which is exactly what it did to this class's own test.</p>
     */
    private static final Set<String> SELF = Set.of(
            "com.crystalgraphics.trace.CgTrace",
            "com.crystalgraphics.trace.CgTraceNames",
            "com.crystalgraphics.trace.CgTraceZones",
            "com.crystalgraphics.trace.CgTraceEvents",
            "com.crystalgraphics.trace.CgTrace$Zone");

    /**
     * Classes that FORWARD to this engine rather than instrument themselves.
     *
     * <p>A facade breaks the source location, and silently: every name a forwarder interns is
     * attributed to the forwarder, so 275 CrystalGUI call sites all pointed at one line of
     * {@code FrameProfile} and the column that makes a report actionable said the same thing about
     * every row. The same trap is waiting for `CgProfiler` when it becomes a facade.</p>
     *
     * <p>So a forwarder declares itself, once, and its frames are skipped like the engine's own.</p>
     */
    private static final Set<String> FORWARDERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Declares {@code className} a forwarder, so a name interned through it is attributed to ITS caller.
     *
     * <pre>{@code
     * CgTraceNames.addForwarder("com.crystalgui.core.trace.FrameProfile");
     * }</pre>
     *
     * <p>Call before the forwarder interns anything: a name's location is captured once, on first
     * sight, and declaring a forwarder afterwards does not revisit what it already attributed.</p>
     */
    public static void addForwarder(String className) {
        if (className != null && !className.isEmpty()) FORWARDERS.add(className);
    }

    /**
     * The id for {@code name}, assigning one and capturing its source location on first sight.
     *
     * <p>Thread-safe and idempotent. The common case — a name already seen — is one concurrent-map
     * lookup, and the intended use is a {@code static final int} so even that happens once.</p>
     */
    public static int intern(String name) {
        Integer existing = IDS.get(name);
        if (existing != null) return existing;
        synchronized (NAMES) {
            existing = IDS.get(name);
            if (existing != null) return existing;
            int id = NAMES.size();
            NAMES.add(name);
            SOURCES.add(callerOf());
            if (id >= firstSeen.length) firstSeen = Arrays.copyOf(firstSeen, firstSeen.length * 2);
            IDS.put(name, id);
            return id;
        }
    }

    public static String nameOf(int id) {
        synchronized (NAMES) {
            return id >= 0 && id < NAMES.size() ? NAMES.get(id) : "?";
        }
    }

    /** {@code "ui/box/BoxPainter.java:156"}, or null where no caller outside this package was found. */
    public static String sourceOf(int id) {
        synchronized (NAMES) {
            return id >= 0 && id < SOURCES.size() ? SOURCES.get(id) : null;
        }
    }

    /**
     * When a zone of each name was first RECORDED, or 0 — what makes "this is the first time it ran" a
     * question one lookup answers, rather than a scan of everything recorded before it.
     *
     * <p>Grown under the names' lock and published whole; the hot path reads whichever array it sees and
     * skips an id past its end, which only a name interned on another thread this instant can be.</p>
     */
    private static volatile long[] firstSeen = new long[256];

    /** Marks {@code id} seen at {@code nanos} unless it already was. The zone path; costs one array read. */
    static void seen(int id, long nanos) {
        long[] at = firstSeen;
        if (id >= 0 && id < at.length && at[id] == 0L) at[id] = nanos == 0L ? 1L : nanos;
    }

    /**
     * When a zone named {@code name} was first recorded in this process, or 0 if it never was.
     *
     * <pre>{@code
     * long first = CgTraceNames.firstSeenNanos("font:atlas");
     * boolean firstTimeHere = first >= frame.beginNanos() && first < frame.endNanos();
     * }</pre>
     */
    public static long firstSeenNanos(String name) {
        Integer id = IDS.get(name);
        long[] at = firstSeen;
        return id == null || id >= at.length ? 0L : at[id];
    }

    /** Forgets every first sighting. For a test, which reuses names across cases. */
    static void resetFirstSeen() {
        synchronized (NAMES) {
            firstSeen = new long[firstSeen.length];
        }
    }

    /** How many distinct names this process has recorded. */
    public static int count() {
        synchronized (NAMES) {
            return NAMES.size();
        }
    }

    /**
     * The first frame outside this package, as {@code path/File.java:line}.
     *
     * <p>{@link StackWalker} rather than {@code new Throwable().getStackTrace()}: it can stop after the
     * first frame it wants instead of materialising the whole trace, which matters because this runs
     * during class initialisation of whatever declared the constant.</p>
     *
     * <p>The package is carried into the path — {@code ui/box/BoxPainter.java} rather than
     * {@code BoxPainter.java} — because a bare file name is not something a reader can open, and two
     * classes in this build genuinely share one.</p>
     */
    private static String callerOf() {
        Optional<String> found = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .filter(f -> !SELF.contains(f.getClassName())
                                && !FORWARDERS.contains(outermost(f.getClassName())))
                        .map(CgTraceNames::describe)
                        .filter(Objects::nonNull)
                        .findFirst());
        return found.orElse(null);
    }

    /** A forwarder's nested classes forward too: {@code Outer$Inner} is {@code Outer}'s. */
    private static String outermost(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static String describe(StackWalker.StackFrame frame) {
        String file = frame.getFileName();
        if (file == null) return null;
        String type = frame.getClassName();
        int lastDot = type.lastIndexOf('.');
        // The package as a path, minus the class itself, so the result pastes into an editor.
        String dir = lastDot < 0 ? "" : type.substring(0, lastDot).replace('.', '/') + "/";
        return dir + file + ':' + frame.getLineNumber();
    }

    /** Drops every name. For tests only — ids handed out before this become meaningless. */
    static void resetForTesting() {
        synchronized (NAMES) {
            NAMES.clear();
            SOURCES.clear();
            IDS.clear();
        }
    }
}
