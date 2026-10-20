package com.crystalgraphics.trace;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JVM-event source: a JFR stream where the JVM has one, else a per-frame class-load count.
 *
 * <p>Not public; {@link CgTrace} starts, stops and reads it. Nothing here names {@code jdk.jfr}: the stream lives in
 * {@code JfrJvmEvents}, which is found by name and never loaded on a JVM that lacks the module (Java 8, and runtimes
 * jlinked without {@code jdk.jfr}), so those fall back instead of failing to link.</p>
 *
 * <p>Events arrive on the JFR thread and are kept by their start time, so the frame thread does nothing per event; a
 * frame's events are those that started inside it, whenever they were delivered.</p>
 */
final class CgJvmEvents {

    /** An open source; closing it stops the stream. */
    interface Stream {
        void close();
    }

    private static final int CAPACITY = 1 << 12;
    private static final int ALL = (1 << CgJvmEventKind.values().length) - 1;
    private static final String STREAM_CLASS = "com.crystalgraphics.trace.JfrJvmEvents";
    private static final String JFR_PROBE = "jdk.jfr.consumer.RecordingStream";

    private static final Object LOCK = new Object();
    private static final CgTraceSnapshot.JvmEventView[] RING = new CgTraceSnapshot.JvmEventView[CAPACITY];
    private static long written;

    /** Fallback: the loaded-class total at the open frame's start, and the count each committed frame saw. */
    private static final long[] FALLBACK_FRAME = new long[1024];
    private static final long[] FALLBACK_COUNT = new long[1024];
    private static long fallbackWritten;
    private static ClassLoadingMXBean classLoading;

    private static Stream stream;
    private static volatile boolean fallbackOn;
    private static volatile int mask;
    /** Set by any explicit start or stop: a host's start with the trace never overrides a caller's choice. */
    private static boolean chosen;
    private static boolean autoTried;

    private CgJvmEvents() {
    }

    /** Streams JFR events where the JVM has them; true if it does, else starts the fallback and returns false. */
    static boolean start() {
        synchronized (LOCK) {
            chosen = true;
            if (stream != null) return true;
            return startLocked();
        }
    }

    static boolean startFallback() {
        synchronized (LOCK) {
            chosen = true;
            closeLocked();
            beginFallbackLocked();
            return false;
        }
    }

    static void stop() {
        synchronized (LOCK) {
            chosen = true;
            closeLocked();
            mask = 0;
        }
    }

    /**
     * The host's start, once per process, when the trace records its first frame: on a thread of its own, since a
     * stream takes hundreds of milliseconds to open and the frame thread must not wait for it.
     */
    static void autoStart() {
        synchronized (LOCK) {
            if (autoTried) return;
            autoTried = true;
        }
        Thread starter = new Thread(() -> {
            synchronized (LOCK) {
                if (!chosen && stream == null && !fallbackOn) startLocked();
            }
        }, "cg-jvm-events");
        starter.setDaemon(true);
        starter.start();
    }

    private static boolean startLocked() {
        closeLocked();
        Stream opened = open();
        if (opened != null) {
            stream = opened;
            mask = ALL;
            return true;
        }
        beginFallbackLocked();
        return false;
    }

    /** The JFR stream, or null when this JVM has none. Never throws: a missing module is a fallback, not a failure. */
    private static Stream open() {
        try {
            ClassLoader loader = CgJvmEvents.class.getClassLoader();
            Class.forName(JFR_PROBE, false, loader);
            return (Stream) Class.forName(STREAM_CLASS, true, loader).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            Throwable cause = absent.getCause() != null ? absent.getCause() : absent;
            Logger.getLogger("CrystalGraphics").log(Level.INFO,
                    "[trace] no JFR stream for JVM events (" + cause + "): counting class loads only");
            return null;
        }
    }

    private static void beginFallbackLocked() {
        classLoading = ManagementFactory.getClassLoadingMXBean();
        fallbackOn = true;
        mask = 1 << CgJvmEventKind.CLASS_LOAD.ordinal();
    }

    private static void closeLocked() {
        if (stream != null) {
            try {
                stream.close();
            } catch (RuntimeException ignored) {
                // a stream that will not close has nothing more to give
            }
            stream = null;
        }
        fallbackOn = false;
    }

    /** One event from the stream. Called on the stream's thread. */
    static void record(CgJvmEventKind kind, String subject, String detail, long startNanos, long durationNanos) {
        CgTraceSnapshot.JvmEventView view =
                new CgTraceSnapshot.JvmEventView(kind, subject, detail, startNanos, durationNanos);
        synchronized (LOCK) {
            RING[(int) (written++ % CAPACITY)] = view;
        }
    }

    /** The loaded-class total, or -1 while the fallback is not the source. Frame thread, allocation-free. */
    static long loadedClasses() {
        return fallbackOn ? classLoading.getTotalLoadedClassCount() : -1L;
    }

    /** A frame committed: under the fallback, the classes loaded since {@code startTotal}. */
    static void frameCommitted(long frameIndex, long startTotal) {
        if (!fallbackOn || startTotal < 0L) return;
        long count = Math.max(0L, classLoading.getTotalLoadedClassCount() - startTotal);
        synchronized (LOCK) {
            int at = (int) (fallbackWritten++ % FALLBACK_FRAME.length);
            FALLBACK_FRAME[at] = frameIndex;
            FALLBACK_COUNT[at] = count;
        }
    }

    static boolean available(CgJvmEventKind kind) {
        return (mask & (1 << kind.ordinal())) != 0;
    }

    /** Whether a source is running, so a report has a line to print. */
    static boolean active() {
        return mask != 0;
    }

    static void clear() {
        synchronized (LOCK) {
            written = 0L;
            fallbackWritten = 0L;
            Arrays.fill(RING, null);
        }
    }

    /** What is held now, for a snapshot to keep. */
    static Held held() {
        synchronized (LOCK) {
            List<CgTraceSnapshot.JvmEventView> events = new ArrayList<>();
            for (long slot = Math.max(0L, written - CAPACITY); slot < written; slot++) {
                events.add(RING[(int) (slot % CAPACITY)]);
            }
            events.sort((a, b) -> Long.compare(a.startNanos(), b.startNanos()));
            long[] frames = new long[(int) Math.min(fallbackWritten, FALLBACK_FRAME.length)];
            long[] counts = new long[frames.length];
            for (int i = 0; i < frames.length; i++) {
                long slot = Math.max(0L, fallbackWritten - FALLBACK_FRAME.length) + i;
                frames[i] = FALLBACK_FRAME[(int) (slot % FALLBACK_FRAME.length)];
                counts[i] = FALLBACK_COUNT[(int) (slot % FALLBACK_FRAME.length)];
            }
            return new Held(mask, fallbackOn, events, frames, counts);
        }
    }

    /** An immutable copy: the events, and the fallback's per-frame class-load counts. */
    static final class Held {
        static final Held NONE = new Held(0, false, List.of(), new long[0], new long[0]);

        private final int mask;
        private final boolean fallback;
        private final List<CgTraceSnapshot.JvmEventView> events;
        private final long[] frames;
        private final long[] counts;

        Held(int mask, boolean fallback, List<CgTraceSnapshot.JvmEventView> events, long[] frames, long[] counts) {
            this.mask = mask;
            this.fallback = fallback;
            this.events = List.copyOf(events);
            this.frames = frames;
            this.counts = counts;
        }

        boolean active() {
            return mask != 0;
        }

        boolean available(CgJvmEventKind kind) {
            return (mask & (1 << kind.ordinal())) != 0;
        }

        List<CgTraceSnapshot.JvmEventView> eventsIn(CgFrameRecord frame) {
            List<CgTraceSnapshot.JvmEventView> out = new ArrayList<>();
            for (CgTraceSnapshot.JvmEventView view : events) if (frame.contains(view.startNanos())) out.add(view);
            return out;
        }

        /** The kind's count in {@code frame}, or {@link CgFrameRecord#ABSENT} where the source cannot see it. */
        long countIn(CgFrameRecord frame, CgJvmEventKind kind) {
            if (!available(kind)) return CgFrameRecord.ABSENT;
            if (fallback) {
                for (int i = frames.length - 1; i >= 0; i--) if (frames[i] == frame.index()) return counts[i];
                return 0L;
            }
            long n = 0L;
            for (CgTraceSnapshot.JvmEventView view : events) {
                if (view.kind() == kind && frame.contains(view.startNanos())) n++;
            }
            return n;
        }
    }
}
