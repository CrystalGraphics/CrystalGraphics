package com.crystalgraphics.util.profiling;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a {@link CgProfiler} snapshot to a file, so profiling works outside the debug harness.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Every performance number CrystalGraphics has was produced by {@code gl-debug-harness}: LWJGL3,
 * no Minecraft, no other mods. That is the right place to iterate — it boots in seconds and isolates
 * the engine — but it is not evidence about production. In-game there is an ASM coremod redirecting
 * GL calls, a modpack's worth of foreign GL state, a different LWJGL, different drivers, and the
 * game's own render work competing for the same cores.
 *
 * <p>The harness dumps its profile by writing files at scene end. Nothing equivalent existed for a
 * loader, which is the only reason in-game measurement had never happened: the profiler already
 * works there, it simply had no way to get its results out. This is that way out.
 *
 * <h3>Using it from a loader</h3>
 *
 * <p>Call it from a keybind, command, or shutdown hook — anywhere on the render thread:
 *
 * <pre>{@code
 * CgProfiler.setEnabled(true);           // once, at startup or on a toggle key
 * // ... play for a while ...
 * CgProfilerDump.dumpAllThreads(new File("crystalgraphics-profile"), "ingame");
 * }</pre>
 *
 * <p>{@link #dumpAllThreads} is the one to use: glyph generation runs on background workers, so a
 * render-thread-only report shows none of it — which is exactly how MSDF generation stayed unmeasured
 * for so long despite dominating warmup.
 *
 * <p>Never throws. A diagnostic that can take down the game it is diagnosing is worse than no
 * diagnostic; failures are logged and swallowed.
 */
public final class CgProfilerDump {

    private static final Logger LOGGER = Logger.getLogger(CgProfilerDump.class.getName());

    private CgProfilerDump() {
    }

    /**
     * Writes this thread's profile only. Prefer {@link #dumpAllThreads} unless you specifically want
     * to exclude background work.
     *
     * @return the file written, or {@code null} on failure
     */
    public static File dump(File directory, String label) {
        return write(directory, label, Map.of("render", CgProfiler.report()));
    }

    /**
     * Writes every thread's profile, including background glyph-generation workers.
     *
     * @param directory written to, created if absent
     * @param label     included in the filename, e.g. {@code "ingame"} or {@code "menu"}
     * @return the file written, or {@code null} on failure
     */
    public static File dumpAllThreads(File directory, String label) {
        return write(directory, label, CgProfiler.reportAllThreads());
    }

    private static File write(File directory, String label, Map<String, CgProfilerReport> reports) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                LOGGER.warning("[CgProfilerDump] could not create " + directory);
                return null;
            }
            // Timestamped rather than overwritten: comparing two sessions is the whole point, and
            // silently replacing the previous capture would make that impossible.
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            File out = new File(directory, "cg-profile-" + label + "-" + stamp + ".txt");

            try (PrintWriter pw = new PrintWriter(out, "UTF-8")) {
                pw.println("=== CrystalGraphics profile: " + label + " @ " + stamp + " ===");
                pw.println("threads: " + reports.size());
                pw.println();
                for (Map.Entry<String, CgProfilerReport> entry : reports.entrySet()) {
                    pw.println("--- thread: " + entry.getKey() + " ---");
                    writeReport(pw, entry.getValue());
                    pw.println();
                }
            }
            LOGGER.info("[CgProfilerDump] wrote " + out.getAbsolutePath());
            return out;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "[CgProfilerDump] failed to write profile", e);
            return null;
        }
    }

    private static void writeReport(PrintWriter pw, CgProfilerReport report) {
        if (report == null) {
            pw.println("  (no data)");
            return;
        }
        pw.printf(Locale.ROOT, "  %-40s %12s %12s %8s %10s%n",
                "scope", "total ms", "self ms", "calls", "max ms");
        for (CgProfilerReport.ScopeEntry e : report.scopes()) {
            pw.printf(Locale.ROOT, "  %-40s %12.3f %12.3f %8d %10.3f%n",
                    e.name(),
                    e.totalNanos() / 1_000_000.0,
                    e.selfNanos() / 1_000_000.0,
                    e.callCount(),
                    e.maxNanos() / 1_000_000.0);
        }
        if (!report.counters().isEmpty()) {
            pw.println("  --- counters ---");
            for (Map.Entry<String, Long> c : report.counters().entrySet()) {
                pw.printf(Locale.ROOT, "  %-40s %12d%n", c.getKey(), c.getValue());
            }
        }
        if (!report.samples().isEmpty()) {
            pw.println("  --- samples ---");
            for (Map.Entry<String, CgProfilerReport.SampleSummary> sEntry : report.samples().entrySet()) {
                CgProfilerReport.SampleSummary s = sEntry.getValue();
                pw.printf(Locale.ROOT, "  %-40s count %8d  avg %12.3f  min %12.3f  max %12.3f%n",
                        sEntry.getKey(), s.count(), s.avg(), s.min(), s.max());
            }
        }
    }
}
