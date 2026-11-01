package com.crystalgraphics.trace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.DoubleSupplier;

import com.crystalgraphics.platform.service.CgCacheDirectory;

/**
 * A run's trace files on disk, for any host of the engine: the run directory ({@code <game>/crystalgraphics/cache/
 * trace/latest/}, the previous runs rotated beside it), its log, and on shutdown {@code meta.json} and
 * {@code report.txt}. With {@code -Dcrystalgraphics.trace.hitchDumpMs=<ms>}, the frames before each frame slower than
 * that are written as they happen: a rare hitch in a hand-driven session, caught.
 *
 * <pre>{@code
 * // a dev run or an installed client, nothing else to set up:
 * -Dcrystalgraphics.trace.channels=crystalgraphics,crystalgraphics.vfx,gpu
 * -Dcrystalgraphics.trace.hitchDumpMs=50              // every frame over 50 ms: <run>/hitch-<frame>/report.txt
 * // on exit: <game>/crystalgraphics/cache/trace/latest/report.txt and meta.json
 *
 * CgTraceRun.dump("first-spark", from, CgTrace.currentFrameIndex() + 1);   // a range of one's own, any time
 * }</pre>
 *
 * <ul>
 *   <li>The engine's lifecycle opens it at pre-init and writes the exit files at shutdown, the JVM's exit the
 *       fallback; a host calls nothing.</li>
 *   <li>{@code -Dcrystalgraphics.trace.dir=<path>} puts the runs elsewhere; with the cache off and no dir, nothing is
 *       written.</li>
 *   <li>{@code report.txt} only when something recorded; its tier is {@code -Dcrystalgraphics.trace.report}
 *       ({@code verdict}, {@code breakdown}, the default, or {@code full}).</li>
 *   <li>A hitch dump holds {@value #HITCH_FRAMES} frames, after the run's first {@value #HITCH_FRAMES}, at most
 *       {@code -Dcrystalgraphics.trace.hitchDumps} (5) a run; it sizes the ring to them, and records
 *       {@code crystalgraphics} if no channel was asked for.</li>
 * </ul>
 */
public final class CgTraceRun {

    /** Frames a hitch dump holds, the hitch the last but one. */
    public static final int HITCH_FRAMES = 240;
    private static final long HITCH_NANOS = Long.getLong("crystalgraphics.trace.hitchDumpMs", 0L) * 1_000_000L;
    private static final int HITCH_DUMPS = Integer.getInteger("crystalgraphics.trace.hitchDumps", 5);
    private static final int ZONES_PER_FRAME = Integer.getInteger("crystalgraphics.trace.hitchZonesPerFrame", 16_384);
    /** Frames without the marker that must come before the one a split is made at: a new burst, not one going on. */
    private static final int SPLIT_QUIET = 60;

    private static volatile DoubleSupplier budget = () -> 1000d / 60d;
    private static boolean opened, exitWritten, hooked;
    private static long lastFrameNanos, framesSeen, hitchFrame = -1L, quietUntil = -1L;
    private static int hitchDumps;

    private CgTraceRun() {
    }

    /**
     * Opens this run's directory and arms the exit files and hitch dumps: what the engine's pre-init does. Once; a
     * second call does nothing.
     */
    public static synchronized void open() {
        if (opened) return;
        opened = true;
        Path root = root();
        if (root == null) return;
        CgTraceLog.useRoot(root);
        if (!hooked) {
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(CgTraceRun::writeExitFiles, "crystalgraphics-trace-exit"));
        }
        if (HITCH_NANOS > 0L) {
            CgTrace.configure(0, HITCH_FRAMES + 8, ZONES_PER_FRAME);
            CgTrace.stopAfterHitch(0L, 0);
            if (CgTrace.enabledNames().isEmpty()) CgTrace.enable("crystalgraphics");
        }
    }

    /** Where the runs go: {@code -Dcrystalgraphics.trace.dir}, else the engine's cache; null for nowhere. */
    public static Path root() {
        String dir = System.getProperty("crystalgraphics.trace.dir");
        if (dir != null && !dir.isEmpty()) return Paths.get(dir);
        return CgCacheDirectory.of("trace");
    }

    /** What a report counts a frame over budget against, in milliseconds: a host's refresh interval, 60 Hz unless set. */
    public static void budget(DoubleSupplier millis) {
        budget = millis;
    }

    /**
     * Once a host frame, after it closed: a frame slower than {@code hitchDumpMs} is dumped two frames later, so a wait
     * booked to the frame after it is in. Render thread; nothing unless hitch dumps are on.
     */
    public static void frame() {
        if (HITCH_NANOS <= 0L || hitchDumps >= HITCH_DUMPS || CgTraceLog.dir() == null) return;
        long now = System.nanoTime(), took = lastFrameNanos == 0L ? 0L : now - lastFrameNanos;
        lastFrameNanos = now;
        framesSeen++;
        long frame = CgTrace.currentFrameIndex();
        if (hitchFrame < 0L && framesSeen > HITCH_FRAMES && frame > quietUntil && took > HITCH_NANOS) {
            hitchFrame = frame - 1;
            CgTraceLog.line("hitch: a " + took / 1_000_000L + " ms frame at " + hitchFrame);
        } else if (hitchFrame >= 0L && frame >= hitchFrame + 3) {
            Path dir = dump("hitch-" + hitchFrame, frame - HITCH_FRAMES, frame);
            CgTraceLog.line("hitch at " + hitchFrame + ": " + dir);
            hitchDumps++;
            hitchFrame = -1L;
            // A burst of slow frames is one dump, not five copies of it.
            quietUntil = frame + HITCH_FRAMES / 4;
        }
    }

    /**
     * Writes frames {@code [fromFrame, toFrame)} into {@code <run>/<label>/}: {@code report.txt} (the breakdown),
     * {@code report-full.txt} and {@code slow.txt} (the slowest frames, each in full).
     *
     * @return the folder written, or null with no run directory
     */
    public static Path dump(String label, long fromFrame, long toFrame) {
        Path dir = CgTraceLog.dir();
        if (dir == null) return null;
        CgTraceReport report = CgTraceReport.of(CgTrace.snapshot().between(fromFrame, toFrame)).budget(budget.getAsDouble());
        CgTraceLog.write(label + "/report.txt", report.breakdown());
        CgTraceLog.write(label + "/report-full.txt", report.render(CgTraceReport.Tier.FULL));
        CgTraceLog.write(label + "/slow.txt", report.worst(20));
        return dir.resolve(label);
    }

    /**
     * Writes {@code meta.json} and, when anything recorded, {@code report.txt} and any split, then closes the log.
     * Once. The engine's shutdown calls it; the JVM's exit is the fallback, too late on a mod loader whose class
     * loader has closed.
     */
    public static synchronized void writeExitFiles() {
        if (exitWritten || CgTraceLog.dir() == null) return;
        exitWritten = true;
        writeMeta();
        if (CgTrace.frameCount() > 0) {
            writeReport(tier());
            writeSplitIfAsked();
        }
        CgTraceLog.stop();
    }

    /** The tier {@code -Dcrystalgraphics.trace.report} names: the breakdown unless it names another. */
    private static CgTraceReport.Tier tier() {
        String asked = System.getProperty("crystalgraphics.trace.report");
        if (asked == null || asked.isEmpty()) return CgTraceReport.Tier.BREAKDOWN;
        for (CgTraceReport.Tier tier : CgTraceReport.Tier.values()) if (tier.name().equalsIgnoreCase(asked)) return tier;
        return CgTraceReport.Tier.VERDICT;
    }

    /** Writes {@code report.txt} at {@code tier} into the run directory; the file, or null without one. */
    public static Path writeReport(CgTraceReport.Tier tier) {
        Path dir = CgTraceLog.dir();
        if (dir == null) return null;
        CgTraceLog.write("report.txt", CgTraceReport.of(CgTrace.snapshot()).budget(budget.getAsDouble()).render(tier));
        return dir.resolve("report.txt");
    }

    /**
     * {@code -Dcrystalgraphics.trace.splitAt=<marker>}: the run either side of the first such marker after
     * {@value #SPLIT_QUIET} frames without one, as {@code report-before.txt}, {@code report-after.txt} and their
     * per-zone {@code report-compare.txt}; {@code -Dcrystalgraphics.trace.splitFrames} (300) frames each at most.
     */
    private static void writeSplitIfAsked() {
        String marker = System.getProperty("crystalgraphics.trace.splitAt");
        if (marker == null || marker.isEmpty()) return;
        int most = Integer.getInteger("crystalgraphics.trace.splitFrames", 300);
        CgTraceSnapshot snap = CgTrace.snapshot();
        List<CgFrameRecord> frames = snap.frames();
        if (frames.isEmpty()) return;
        long previous = frames.get(0).index() - 1, last = frames.get(frames.size() - 1).index(), split = -1L;
        for (long at : snap.markerFrames(marker)) {
            if (at - previous > SPLIT_QUIET) {
                split = at;
                break;
            }
            previous = at;
        }
        if (split < 0L) {
            CgTraceLog.line("trace.splitAt: no '" + marker + "' after " + SPLIT_QUIET + " frames without one; nothing split");
            return;
        }
        long from = Math.max(previous + 1, split - most), to = Math.min(last + 1, split + most);
        double ms = budget.getAsDouble();
        CgTraceLog.write("report-before.txt", CgTraceReport.of(snap.between(from, split)).budget(ms).breakdown());
        CgTraceLog.write("report-after.txt", CgTraceReport.of(snap.between(split, to)).budget(ms).breakdown());
        CgTraceLog.write("report-compare.txt", CgTraceReport.of(snap).budget(ms).compare(from, split - 1, split, to - 1));
    }

    /**
     * Writes what this run was beside its log, so a partial trace cannot be read as a complete one: the channels
     * recording and not, the frames held, and what the writer and the arena dropped.
     */
    public static void writeMeta() {
        if (CgTraceLog.dir() == null) return;
        StringBuilder out = new StringBuilder(256);
        out.append("{\n  \"run\": \"").append(CgTraceLog.runId()).append("\",\n");
        out.append("  \"frames\": ").append(CgTrace.frameCount()).append(",\n");
        out.append("  \"droppedLines\": ").append(CgTraceLog.dropped()).append(",\n");
        out.append("  \"droppedZones\": ").append(CgTrace.droppedZones()).append(",\n");
        out.append("  \"recording\": [");
        List<String> on = CgTrace.enabledNames();
        for (int i = 0; i < on.size(); i++) {
            if (i > 0) out.append(", ");
            out.append('"').append(on.get(i)).append('"');
        }
        out.append("],\n  \"notRecording\": [");
        boolean first = true;
        for (CgTraceChannel channel : CgTrace.channels()) {
            if (channel.isEnabled()) continue;
            if (!first) out.append(", ");
            first = false;
            out.append('"').append(channel.name()).append('"');
        }
        out.append("]\n}\n");
        CgTraceLog.write("meta.json", out.toString());
    }

    /** Forgets that it was opened or wrote its exit files, so a test opens a run of its own. Tests only. */
    public static synchronized void resetForTesting() {
        opened = false;
        exitWritten = false;
        lastFrameNanos = 0L;
        framesSeen = 0L;
        hitchFrame = -1L;
        quietUntil = -1L;
        hitchDumps = 0;
    }
}
