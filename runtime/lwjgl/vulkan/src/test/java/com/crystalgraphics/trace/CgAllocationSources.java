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

/**
 * Where a body allocates, for an allocation proof's failure: this module's tests cannot see core's {@code CgAllocationProof}.
 *
 * <pre>{@code
 * if (perFrame != 0) fail(perFrame + " B a frame; sources:\n" + CgAllocationSources.of(500, frame));
 * }</pre>
 */
public final class CgAllocationSources {

    private CgAllocationSources() {
    }

    /** The sites {@code body} allocates at, most bytes first, from JFR's TLAB samples on this thread. */
    public static String of(int calls, Runnable body) {
        Path file = null;
        try {
            file = Files.createTempFile("cg-alloc", ".jfr");
            long thread = Thread.currentThread().getId();
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
                recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                recording.start();
                for (int i = 0; i < calls * 40; i++) body.run();
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
            for (int i = 0; i < Math.min(12, ranked.size()); i++) out.append("  ").append(ranked.get(i).getKey()).append('\n');
            return ranked.isEmpty() ? "  (no TLAB sample)\n" : out.toString();
        } catch (IOException | RuntimeException e) {
            return "  (JFR unavailable: " + e + ")\n";
        } finally {
            if (file != null) file.toFile().delete();
        }
    }

    private static String site(RecordedStackTrace stack) {
        if (stack == null) return "?";
        List<RecordedFrame> frames = stack.getFrames();
        for (RecordedFrame f : frames) {
            String type = f.getMethod().getType().getName();
            if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) {
                StringBuilder at = new StringBuilder();
                for (int k = frames.indexOf(f); k < Math.min(frames.size(), frames.indexOf(f) + 4); k++) {
                    RecordedFrame c = frames.get(k);
                    if (at.length() > 0) at.append(" < ");
                    at.append(c.getMethod().getType().getName().replaceAll(".*\\.", "")).append('.').append(c.getMethod().getName())
                            .append(':').append(c.getLineNumber());
                }
                return at.toString();
            }
        }
        return frames.isEmpty() ? "?" : frames.get(0).getMethod().getType().getName();
    }
}
