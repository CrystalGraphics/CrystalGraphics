package com.crystalgraphics.platform.gl;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every GL call a stretch of frames handed the backend, by GL function, by the engine code that made it and by the trace
 * zones open at the time, and every state setter asked of the shadow: the whole picture of what a threaded driver's
 * thread is given (T11). Off unless {@code -Dcrystalgraphics.gl.callSites=true}; on, every call walks its stack, so the
 * frames' times mean nothing.
 *
 * <pre>{@code
 * CgGlCallSites.reset();                                      // the range's first frame
 * // ... frames ...
 * CgGlCallSites.write(new File(dir, "gl-calls.txt"), frames); // its end
 * }</pre>
 *
 * Zones are only known on a thread the trace records, under the channels it records.
 */
public final class CgGlCallSites {

    public static final boolean ON = CgVar.bool("crystalgraphics.gl.callSites", false, CgVar.DEV | CgVar.RESTART,
            "Tallies every GL call by function, caller and open trace zones, for gl-calls.txt: very slow.").get();

    private static final String GL = CgGL.class.getName();
    /** The callers named per site: the engine's call, and what called that. */
    private static final int CALLERS = 2;

    private static final Map<String, long[]> CALLS = new HashMap<>(), SETS = new HashMap<>();
    private static final Map<String, long[]> BY_ZONE = new HashMap<>(), BY_ZONE_FUNCTION = new HashMap<>();
    private static final Map<String, long[]> BY_SITE = new HashMap<>();
    /** Calls the shadow does not track, by function: [calls, those setting what their bind point already held]. */
    private static final Map<String, long[]> REPEATS = new HashMap<>();
    private static final Map<String, Long> LAST = new HashMap<>();
    private static final Map<String, long[]> ISSUED = new HashMap<>();
    private static long calls, sets;
    /** The program last asked for, whether or not the shadow sent it: whose uniforms a {@code glUniform*} sets. */
    private static int program;

    private CgGlCallSites() {
    }

    /** A call handed to the backend, from {@code CgGL.gl()}. */
    static void call() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        String function = function(stack);
        StringBuilder zones = new StringBuilder();
        zones.append('[').append(Thread.currentThread().getName()).append("] ");
        CgTrace.openZones(zones, " > ");
        String zone = zones.toString();
        synchronized (CgGlCallSites.class) {
            calls++;
            add(CALLS, function);
            add(BY_ZONE, zone);
            add(BY_ZONE_FUNCTION, zone + "\u0000" + function);
            add(BY_SITE, function + "  <-  " + callers(stack));
        }
    }

    /** A setter asked of the state shadow, from {@code CgGL.state()}; one it finds changed is a {@link #call} too. */
    static void set() {
        String function = function(new Throwable().getStackTrace());
        synchronized (CgGlCallSites.class) {
            sets++;
            add(SETS, function);
        }
    }

    /** A driver call a backend makes inside one of its own methods, past what {@link #call} sees: a gathered barrier. */
    static synchronized void issued(String function) {
        ISSUED.computeIfAbsent(function, k -> new long[1])[0]++;
    }

    /** {@code glUseProgram(program)} asked, from CgGL. */
    static void program(int program) {
        CgGlCallSites.program = program;
    }

    /**
     * An untracked call setting {@code value} at bind point {@code point} of {@code function}: counted as a repeat when
     * the point already held it. A uniform's point is its program's, since a program keeps its uniforms.
     */
    static synchronized void value(String function, long point, long value) {
        boolean uniform = function.startsWith("glUniform");
        String key = function + ':' + (uniform ? program + "/" : "") + point;
        Long last = LAST.put(key, value);
        long[] n = REPEATS.computeIfAbsent(function, k -> new long[2]);
        n[0]++;
        if (last != null && last == value) n[1]++;
    }

    /** Forgets everything counted: a range's first frame. */
    public static synchronized void reset() {
        REPEATS.clear();
        ISSUED.clear();
        CALLS.clear();
        SETS.clear();
        BY_ZONE.clear();
        BY_ZONE_FUNCTION.clear();
        BY_SITE.clear();
        calls = sets = 0;
    }

    /** Writes what was counted since {@link #reset} over {@code frames} frames, ranked, per frame. */
    public static synchronized void write(File file, long frames) throws IOException {
        double f = Math.max(1, frames);
        long draws = 0, dispatches = 0;
        for (Map.Entry<String, long[]> e : CALLS.entrySet()) {
            String inner = e.getKey().substring(e.getKey().lastIndexOf('>') + 1);
            if (inner.startsWith("glDraw") || inner.startsWith("glMultiDraw")) draws += e.getValue()[0];
            if (inner.startsWith("glDispatch")) dispatches += e.getValue()[0];
        }
        try (PrintWriter out = new PrintWriter(file, StandardCharsets.UTF_8.name())) {
            out.printf(Locale.ROOT, "GL CALLS  %d frames%n", frames);
            out.printf(Locale.ROOT, "  backend calls %.1f a frame, shadow sets %.1f, draws %.1f, dispatches %.1f, calls a draw or dispatch %.1f%n",
                    calls / f, sets / f, draws / f, dispatches / f, calls / Math.max(1.0, draws + dispatches));
            out.println("  A function is the CgGL method the engine called, then the one that reached the backend where they differ.");
            section(out, "BY FUNCTION  backend calls a frame", CALLS, calls, f, Integer.MAX_VALUE);
            section(out, "ISSUED  driver calls a backend makes inside its own methods, a frame", ISSUED, calls, f, Integer.MAX_VALUE);
            out.println();
            out.println("REPEATS  calls the shadow does not track, a frame, and those setting what their bind point already held");
            for (Map.Entry<String, long[]> e : ranked(REPEATS)) {
                long[] n = e.getValue();
                out.printf(Locale.ROOT, "  %8.1f  repeats %8.1f  %5.1f%%  %s%n", n[0] / f, n[1] / f, 100.0 * n[1] / Math.max(1, n[0]), e.getKey());
            }
            section(out, "SHADOW SETS BY FUNCTION  setters asked a frame (changed ones are calls above)", SETS, sets, f, Integer.MAX_VALUE);
            section(out, "BY ZONES  backend calls a frame, by the trace zones open at the call", BY_ZONE, calls, f, Integer.MAX_VALUE);
            out.println();
            out.println("BY ZONES, BY FUNCTION  each zone path's functions");
            List<Map.Entry<String, long[]>> zoneFunctions = ranked(BY_ZONE_FUNCTION);
            for (Map.Entry<String, long[]> zone : ranked(BY_ZONE)) {
                out.printf(Locale.ROOT, "  %8.1f  %s%n", zone.getValue()[0] / f, zone.getKey());
                String prefix = zone.getKey() + "\u0000";
                for (Map.Entry<String, long[]> e : zoneFunctions) {
                    if (e.getKey().startsWith(prefix)) {
                        out.printf(Locale.ROOT, "      %8.1f  %s%n", e.getValue()[0] / f, e.getKey().substring(prefix.length()));
                    }
                }
            }
            section(out, "BY SITE  backend calls a frame: function  <-  the engine's call  <-  its caller", BY_SITE, calls, f, 400);
        }
    }

    private static void section(PrintWriter out, String title, Map<String, long[]> counts, long total, double frames, int limit) {
        out.println();
        out.println(title);
        List<Map.Entry<String, long[]>> rows = ranked(counts);
        double running = 0;
        for (int i = 0; i < rows.size() && i < limit; i++) {
            long n = rows.get(i).getValue()[0];
            running += n;
            out.printf(Locale.ROOT, "  %8.1f  %5.1f%%  %5.1f%%  %s%n", n / frames, 100.0 * n / Math.max(1, total),
                    100.0 * running / Math.max(1, total), rows.get(i).getKey());
        }
        if (rows.size() > limit) out.printf(Locale.ROOT, "  (+%d more)%n", rows.size() - limit);
    }

    private static List<Map.Entry<String, long[]>> ranked(Map<String, long[]> counts) {
        List<Map.Entry<String, long[]>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        return rows;
    }

    private static void add(Map<String, long[]> counts, String key) {
        counts.computeIfAbsent(key, k -> new long[1])[0]++;
    }

    /** The CgGL method the engine called, and the one that reached gl() or state() where they differ. */
    private static String function(StackTraceElement[] stack) {
        // [0] this, [1] CgGL.gl() or state(), [2] the CgGL method that reached it, ... the CgGL method the engine called
        String inner = stack.length > 2 ? stack[2].getMethodName() : "?";
        String outer = inner;
        for (int i = 2; i < stack.length && stack[i].getClassName().equals(GL); i++) outer = stack[i].getMethodName();
        return outer.equals(inner) ? inner : outer + ">" + inner;
    }

    private static String callers(StackTraceElement[] stack) {
        StringBuilder out = new StringBuilder();
        int named = 0;
        for (int i = 1; i < stack.length && named < CALLERS; i++) {
            if (stack[i].getClassName().equals(GL)) continue;
            if (named++ > 0) out.append("  <-  ");
            String type = stack[i].getClassName();
            out.append(type.substring(type.lastIndexOf('.') + 1)).append('.').append(stack[i].getMethodName())
                    .append(':').append(stack[i].getLineNumber());
        }
        return out.toString();
    }
}
