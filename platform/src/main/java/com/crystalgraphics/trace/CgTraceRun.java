package com.crystalgraphics.trace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import com.crystalgraphics.platform.service.CgCacheDirectory;

/**
 * A run's trace files on disk, for any host of the engine: the run directory ({@code <game>/crystalgraphics/cache/
 * trace/latest/}, the previous runs rotated beside it), its log, and on shutdown {@code meta.json}, {@code report.txt}
 * (the whole run), {@code play-report.txt} and {@code slow.txt} (play alone: from {@value #HITCH_FRAMES} frames past
 * the first world frame). With {@code -Dcrystalgraphics.trace.hitchDumpMs=<ms>}, the frames before each frame of play
 * slower than that are written as they happen, on a thread of their own: a rare hitch in a hand-driven session, caught.
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
 *   <li>To see a whole session, size the ring to hold it ({@code trace.frames}, {@code trace.zones}) and read
 *       {@code slow.txt}: hitch dumps are for sessions too long to hold.</li>
 *   <li>A hitch dump holds {@value #HITCH_FRAMES} frames, from the play range's start, at most
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
    private static long lastFrameNanos, hitchFrame = -1L, quietUntil = -1L;
    /** The first frame of play: {@value #HITCH_FRAMES} past the first world frame. -1 until a world draws. */
    private static volatile long playFrom = -1L;
    private static int hitchDumps;
    /** Writes dumps off the render thread: a snapshot of the ring and its reports cost 100 ms and more. */
    private static ExecutorService dumper;

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
            // Twice a dump: the frames it copies off-thread are not overwritten while it copies them.
            CgTrace.configure(0, 2 * HITCH_FRAMES, ZONES_PER_FRAME);
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

    /** Once a world frame: the first starts the play range, {@value #HITCH_FRAMES} frames on, past joining the world. */
    public static void worldFrame() {
        if (playFrom < 0L) playFrom = CgTrace.currentFrameIndex() + HITCH_FRAMES;
    }

    /** The first frame of play: past the first world frame's settling, else past the run's first frames. */
    public static long playStart() {
        long from = playFrom;
        return from >= 0L ? from : HITCH_FRAMES;
    }

    /**
     * Once a host frame, after it closed: a frame of play slower than {@code hitchDumpMs} is dumped two frames later,
     * so a wait booked to the frame after it is in, on a thread of its own. Render thread; nothing unless hitch dumps
     * are on.
     */
    public static void frame() {
        if (HITCH_NANOS <= 0L || hitchDumps >= HITCH_DUMPS || CgTraceLog.dir() == null) return;
        long now = System.nanoTime(), took = lastFrameNanos == 0L ? 0L : now - lastFrameNanos;
        lastFrameNanos = now;
        long frame = CgTrace.currentFrameIndex();
        if (hitchFrame < 0L && frame > playStart() && frame > quietUntil && took > HITCH_NANOS) {
            hitchFrame = frame - 1;
            CgTraceLog.line("hitch: a " + took / 1_000_000L + " ms frame at " + hitchFrame);
        } else if (hitchFrame >= 0L && frame >= hitchFrame + 3) {
            String label = "hitch-" + hitchFrame;
            long from = frame - HITCH_FRAMES;
            dumper().execute(() -> CgTraceLog.line("hitch at " + label + ": " + dump(label, from, frame)));
            hitchDumps++;
            hitchFrame = -1L;
            // A burst of slow frames is one dump, not five copies of it.
            quietUntil = frame + HITCH_FRAMES / 4;
        }
    }

    private static synchronized ExecutorService dumper() {
        if (dumper == null) {
            dumper = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "crystalgraphics-trace-dump");
                thread.setDaemon(true);
                return thread;
            });
        }
        return dumper;
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
     * Writes {@code meta.json} and, when anything recorded, {@code report.txt}, the play's own reports and any split,
     * then closes the log. Once, after any hitch dump still being written. The engine's shutdown calls it; the JVM's
     * exit is the fallback, too late on a mod loader whose class loader has closed.
     */
    public static synchronized void writeExitFiles() {
        if (exitWritten || CgTraceLog.dir() == null) return;
        exitWritten = true;
        finishDumps();
        writeMeta();
        if (CgTrace.frameCount() > 0) {
            writeReport(tier());
            writePlay();
            writeSplitIfAsked();
        }
        CgTraceLog.stop();
    }

    private static void finishDumps() {
        ExecutorService pending;
        synchronized (CgTraceRun.class) {
            pending = dumper;
            dumper = null;
        }
        if (pending == null) return;
        pending.shutdown();
        try {
            pending.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The frames of play alone, loading and joining the world left out: {@code play-report.txt} at the exit report's
     * tier, and {@code slow.txt}, its 20 slowest frames in full.
     */
    private static void writePlay() {
        CgTraceSnapshot play = CgTrace.snapshot().between(playStart(), Long.MAX_VALUE);
        if (play.frames().isEmpty() || play.frames().get(0).index() < playStart()) return;
        CgTraceReport report = CgTraceReport.of(play).budget(budget.getAsDouble());
        CgTraceLog.write("play-report.txt", report.render(tier()));
        CgTraceLog.write("slow.txt", report.worst(20));
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
        playFrom = -1L;
        hitchFrame = -1L;
        quietUntil = -1L;
        hitchDumps = 0;
    }
}
