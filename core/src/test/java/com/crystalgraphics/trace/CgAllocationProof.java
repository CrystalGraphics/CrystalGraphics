package com.crystalgraphics.trace;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * A frame-thread system's allocation proof: the bytes one call allocates on the calling thread after warm-up, through
 * {@link CgThreadAllocation}.
 *
 * <pre>{@code
 * CgAllocationProof.assertZero("vfx update", 2_000, 500, () -> vfx.update(clock.next()));
 * }</pre>
 *
 * <p>The least of three measured rounds is taken, so a one-off (a list growing once to its high-water mark) does not
 * fail a system that is otherwise flat. A failure names its sources: the allocating frames sampled by JFR while the body
 * runs again. Skips where the JVM does not count a thread's allocation.</p>
 */
public final class CgAllocationProof {

    private static final int ROUNDS = 3;

    private CgAllocationProof() {
    }

    /** Bytes per call over {@code measured} calls after {@code warmup}, the least of three rounds. */
    public static double bytesPerCall(int warmup, int measured, Runnable body) {
        assumeTrue("this JVM does not count a thread's allocation", CgThreadAllocation.available());
        for (int i = 0; i < warmup; i++) body.run();
        long least = Long.MAX_VALUE;
        for (int r = 0; r < ROUNDS; r++) {
            long before = CgThreadAllocation.current();
            for (int i = 0; i < measured; i++) body.run();
            least = Math.min(least, CgThreadAllocation.current() - before);
        }
        return (double) least / measured;
    }

    /** Fails unless {@code body} allocates nothing per call after warm-up; the message names its top sources. */
    public static void assertZero(String system, int warmup, int measured, Runnable body) {
        double perCall = bytesPerCall(warmup, measured, body);
        if (perCall == 0.0) return;
        // Enough calls to fill TLABs many times over: a sample is taken per TLAB, not per object.
        int calls = (int) Math.max(measured, Math.min(2_000_000, (64L << 20) / Math.max(1.0, perCall)));
        assertEquals(system + " allocates " + perCall + " B a call; sources:\n" + sources(calls, body), 0.0,
                perCall, 0.0);
    }

    /**
     * The allocation sites {@code body} reaches over {@code calls} calls, most bytes first: the first frame outside
     * {@code java.} and {@code jdk.} with the class allocated, from JFR's TLAB samples on this thread.
     */
    public static String sources(int calls, Runnable body) {
        Path file = null;
        try {
            file = Files.createTempFile("cg-alloc", ".jfr");
            long thread = Thread.currentThread().getId();
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
                recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                recording.start();
                for (int i = 0; i < calls; i++) body.run();
                recording.stop();
                recording.dump(file);
            }
            Map<String, Long> bytes = new HashMap<>();
            for (RecordedEvent event : RecordingFile.readAllEvents(file)) {
                if (event.getThread() == null || event.getThread().getJavaThreadId() != thread) continue;
                String site = site(event.getStackTrace()) + " <- " + event.getClass("objectClass").getName();
                long size = event.hasField("tlabSize") ? event.getLong("tlabSize") : event.getLong("allocationSize");
                bytes.merge(site, size, Long::sum);
            }
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(bytes.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < Math.min(8, ranked.size()); i++) {
                out.append("  ").append(ranked.get(i).getKey()).append('\n');
            }
            return ranked.isEmpty() ? "  (no TLAB sample: too few bytes to fill one)\n" : out.toString();
        } catch (IOException | RuntimeException e) {
            return "  (JFR unavailable: " + e + ")\n";
        } finally {
            if (file != null) file.toFile().delete();
        }
    }

    private static String site(RecordedStackTrace stack) {
        if (stack == null) return "?";
        List<RecordedFrame> frames = stack.getFrames();
        String fallback = "?";
        for (int i = 0; i < frames.size(); i++) {
            RecordedFrame f = frames.get(i);
            String type = f.getMethod().getType().getName();
            String at = type + "." + f.getMethod().getName() + ":" + f.getLineNumber();
            if (i == 0) fallback = at;
            if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) return at;
        }
        return fallback;
    }
}
