package com.crystalgraphics.platform.gl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code -Dcrystalgraphics.gl.threadCheck=true}: names every place that reaches {@link CgGL} inside a GL-free
 * section, or off the thread that owns the context. Each distinct call site is logged once, with its stack, and a
 * table of every site and how often it ran is printed at exit. Off, it costs one constant branch per call.
 */
final class CgGLAudit {

    static final boolean ON = Boolean.getBoolean("crystalgraphics.gl.threadCheck");
    /** Frames outside {@code platform.gl} that make a site: enough to tell callers apart, few enough to merge. */
    private static final int SITE_DEPTH = 4;
    private static final int LOGGED_DEPTH = 14;
    private static final String PACKAGE = CgGL.class.getPackage().getName() + ".";

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final ThreadLocal<String> SECTION = new ThreadLocal<>();
    private static final Map<String, int[]> SITES = new LinkedHashMap<>();

    static {
        if (ON) Runtime.getRuntime().addShutdownHook(new Thread(CgGLAudit::summary, "cg-gl-audit"));
    }

    private CgGLAudit() {}

    static void enter(String section) {
        if (ON) SECTION.set(section);
    }

    static void exit() {
        if (ON) SECTION.remove();
    }

    static void check(CgGLBackend backend) {
        String section = SECTION.get();
        boolean offThread = backend != null && !backend.ownedByCurrentThread();
        if (section == null && !offThread) return;
        String why = offThread ? "off the owner thread" + (section != null ? ", in " + section : "") : "in " + section;

        StackTraceElement[] stack = new Throwable().getStackTrace();
        int entry = 0;
        String call = "?";
        for (int i = 0; i < stack.length; i++) {
            if (!stack[i].getClassName().startsWith(PACKAGE)) {
                entry = i;
                break;
            }
            if (stack[i].getClassName().equals(CgGL.class.getName())) call = stack[i].getMethodName();
        }
        StringBuilder key = new StringBuilder(why).append(" | ").append(call);
        for (int i = entry; i < Math.min(stack.length, entry + SITE_DEPTH); i++) key.append(" < ").append(frame(stack[i]));

        boolean first;
        synchronized (SITES) {
            int[] count = SITES.get(key.toString());
            first = count == null;
            if (first) SITES.put(key.toString(), count = new int[1]);
            count[0]++;
        }
        if (!first) return;
        StringBuilder message = new StringBuilder("[gl-audit] CgGL.").append(call).append(' ').append(why)
                .append(" on ").append(Thread.currentThread().getName());
        for (int i = entry; i < Math.min(stack.length, entry + LOGGED_DEPTH); i++) message.append("\n    at ").append(stack[i]);
        LOG.warn(message.toString());
    }

    private static String frame(StackTraceElement e) {
        String type = e.getClassName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + e.getMethodName() + ":" + e.getLineNumber();
    }

    private static void summary() {
        List<Map.Entry<String, int[]>> sites;
        synchronized (SITES) {
            sites = new ArrayList<>(SITES.entrySet());
        }
        // System.err: log4j may have stopped by the time a shutdown hook runs.
        if (sites.isEmpty()) {
            System.err.println("[gl-audit] no GL in a GL-free section or off the owner thread");
            return;
        }
        sites.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder table = new StringBuilder("[gl-audit] ").append(sites.size()).append(" sites, by calls:");
        for (Map.Entry<String, int[]> site : sites) {
            table.append(String.format("%n%9d  %s", site.getValue()[0], site.getKey()));
        }
        System.err.println(table);
    }
}
