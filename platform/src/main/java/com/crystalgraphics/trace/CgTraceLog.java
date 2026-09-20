package com.crystalgraphics.trace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A run's own output directory, written by one daemon thread that is never the frame thread.
 *
 * <pre>{@code
 * CgTraceLog.useRoot(cacheDir.resolve("trace"));   // a host, at startup
 * CgTraceLog.line("[frame] 35ms  paint:tree 29ms");
 * CgTraceLog.dir();                                // .../trace/latest
 * }</pre>
 *
 * <h3>Why not the game log</h3>
 *
 * <p>The probe this replaces reported a slow frame by calling the logger from inside {@code frameEnd},
 * which on a Minecraft host is a synchronous hop through the loader's pipeline to a console appender —
 * a Swing console, on 1.7.10. A probe that blocks the frame thread on terminal I/O has changed the
 * thing it is measuring, and {@code -Dcrystalgui.frameprofile.every=0} made that the documented way to
 * take a statistic.</p>
 *
 * <p>So a producer never blocks: {@link #line} offers to a bounded queue and <b>drops and counts</b>
 * when it is full. A silently truncated log is worse than an honestly short one, so the drop count is
 * written into the file and surfaced to any reader.</p>
 *
 * <h3>{@code latest/} is a real directory, and it is the current run</h3>
 *
 * <p>An agent's loop is "run the scene, read one file, edit, run again" — which needs a path that does
 * not change. Symbolic links need privileges on Windows, so instead the live run <em>is</em>
 * {@code latest/}, and the NEXT {@link #useRoot} rotates whatever it finds there to the run id recorded
 * in its own {@code meta} line before starting a fresh one.</p>
 *
 * <p>That ordering is the point: after a run ends, {@code latest/} still holds it — which is exactly
 * when somebody reads it. Renaming at shutdown would empty the one path a reader was told to use, and
 * would lose the directory entirely for a process that crashed.</p>
 */
public final class CgTraceLog {

    private CgTraceLog() {
    }

    /** What the live run is always called. @see CgTraceLog */
    public static final String LATEST = "latest";

    /** Runs kept before the oldest is deleted. */
    private static final int KEEP_RUNS = 5;

    /** Lines held before a producer starts dropping. Generous: a slow frame writes one line. */
    private static final int QUEUE_DEPTH = 4096;

    private static final DateTimeFormatter RUN_ID =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final String POISON = new String("\u0000stop");

    private static volatile Path directory;
    private static volatile BlockingQueue<String> queue;
    private static volatile Thread writer;
    private static final AtomicLong DROPPED = new AtomicLong();
    private static String runId = "";

    /**
     * Points the log at {@code root}, rotating the previous run out of {@link #LATEST} and starting
     * the writer.
     *
     * <p>A null root changes nothing, so a caller that does not know one need not check. Calling twice
     * is a no-op after the first: a run is a process lifetime.</p>
     */
    public static synchronized void useRoot(Path root) {
        if (root == null || directory != null) return;
        try {
            Files.createDirectories(root);
            rotate(root);
            prune(root);
            runId = RUN_ID.format(Instant.now());
            Path dir = root.resolve(LATEST);
            Files.createDirectories(dir);
            Files.write(dir.resolve("run-id"), runId.getBytes(StandardCharsets.UTF_8));
            directory = dir;
            queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
            writer = startWriter(dir.resolve("trace.log"));
        } catch (IOException failed) {
            // A DIAGNOSTIC THAT CANNOT WRITE MUST NOT TAKE THE APPLICATION WITH IT. The log stays off
            // and every line becomes a no-op; nothing else in the engine depends on it.
            directory = null;
            queue = null;
        }
    }

    /** The live run's directory, or null when no root was given. */
    public static Path dir() {
        return directory;
    }

    /** This run's id — the name {@link #LATEST} takes when the next run rotates it away. */
    public static String runId() {
        return runId;
    }

    public static boolean isOpen() {
        return queue != null;
    }

    /** Lines the writer could not keep up with. Non-zero must be SHOWN, never swallowed. */
    public static long dropped() {
        return DROPPED.get();
    }

    /**
     * Queues one line. Never blocks, never throws, never touches a file on the calling thread.
     *
     * @return false when the line was dropped, which a caller may ignore and a reporter should not
     */
    public static boolean line(String text) {
        BlockingQueue<String> sink = queue;
        if (sink == null) return false;
        if (sink.offer(text)) return true;
        DROPPED.incrementAndGet();
        return false;
    }

    /** Writes {@code content} into the run directory under {@code name}, on the calling thread. */
    public static void write(String name, String content) {
        Path dir = directory;
        if (dir == null) return;
        try {
            Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // As above: a diagnostic that cannot write stays quiet rather than failing a frame.
        }
    }

    /** Flushes and closes. Idempotent; the directory stays as {@link #LATEST} for the next reader. */
    public static synchronized void stop() {
        BlockingQueue<String> sink = queue;
        Thread thread = writer;
        queue = null;
        writer = null;
        if (sink == null || thread == null) return;
        try {
            // OFFERED WITH A TIMEOUT, IN A LOOP. A plain offer() returns false on a full queue and the
            // poison is silently dropped -- so a run that flooded the writer would never shut it down,
            // never write its own drop count, and hang the join below for its full two seconds. The
            // queue is draining the whole time, so a slot appears; the deadline is for the case where
            // the writer has already died.
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (thread.isAlive() && System.nanoTime() < deadline) {
                if (sink.offer(POISON, 50L, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
            }
            thread.join(2000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread startWriter(Path file) {
        BlockingQueue<String> sink = queue;
        Thread thread = new Thread(() -> {
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                while (true) {
                    String text = sink.take();
                    //noinspection StringEquality — the poison is an identity sentinel on purpose, so a
                    // line that happens to equal it cannot stop the writer.
                    if (text == POISON) break;
                    out.write(text);
                    out.write('\n');
                    // Flushed when the queue drains rather than per line: a burst of sixty writes one
                    // syscall, and a reader looking at a stalled application sees everything up to the
                    // stall because the queue is empty by definition while nothing is producing.
                    if (sink.isEmpty()) out.flush();
                }
                long lost = DROPPED.get();
                if (lost > 0) out.write("[trace] " + lost + " lines dropped: the writer fell behind\n");
            } catch (IOException | InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        }, "cg-trace-log");
        thread.setDaemon(true);
        // BELOW NORMAL. This thread exists to keep I/O off the frame thread; letting it compete for a
        // core with the thread it is measuring would put the measurement back into the measurement.
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        return thread;
    }

    /** Moves a previous run out of {@link #LATEST}, under the id it recorded for itself. */
    private static void rotate(Path root) throws IOException {
        Path latest = root.resolve(LATEST);
        if (!Files.isDirectory(latest)) return;
        Path marker = latest.resolve("run-id");
        String previous = Files.isRegularFile(marker)
                ? new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim()
                : RUN_ID.format(Files.getLastModifiedTime(latest).toInstant());
        Path target = root.resolve(previous.isEmpty() ? "unnamed" : previous);
        if (Files.exists(target)) return;
        Files.move(latest, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Deletes all but the newest {@link #KEEP_RUNS} rotated runs. */
    private static void prune(Path root) throws IOException {
        List<Path> runs = new ArrayList<>();
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(each -> !LATEST.equals(each.getFileName().toString()))
                    .forEach(runs::add);
        }
        if (runs.size() <= KEEP_RUNS) return;
        runs.sort(Comparator.comparing(each -> each.getFileName().toString()));
        for (int i = 0; i < runs.size() - KEEP_RUNS; i++) deleteTree(runs.get(i));
    }

    private static void deleteTree(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            List<Path> all = new ArrayList<>();
            walk.forEach(all::add);
            for (int i = all.size() - 1; i >= 0; i--) Files.deleteIfExists(all.get(i));
        }
    }

    /**
     * Stops the writer and forgets the root, so a later {@link #useRoot} opens a fresh run.
     *
     * <p>For a test, and for a host that genuinely restarts its own session. Ordinary shutdown wants
     * {@link #stop()}, which leaves the directory in place for whoever reads it next.</p>
     */
    public static synchronized void resetForTesting() {
        stop();
        directory = null;
        DROPPED.set(0L);
        runId = "";
    }
}
