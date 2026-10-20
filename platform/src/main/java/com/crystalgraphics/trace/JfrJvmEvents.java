package com.crystalgraphics.trace;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordingStream;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The JFR stream behind {@link CgJvmEvents}: compilations, deoptimisations, safepoints and class loads, each mapped
 * onto {@code System.nanoTime()}.
 *
 * <p>The only class that names {@code jdk.jfr}. {@link CgJvmEvents} finds it by name once the module is known to be
 * there, so a JVM without it (Java 8, a jlinked runtime) never resolves it. Events are delivered about a second late
 * in batches, on the stream's own thread; everything here runs there.</p>
 *
 * <p>Thresholds are off, because the defaults (100 ms for a compilation, 10 ms for a safepoint) hide the events a
 * hitch is made of, and stack traces are off for cost. JFR times events in epoch time; the offset to
 * {@code nanoTime} is sampled at start and again at every flush, so clock adjustments do not accumulate.</p>
 *
 * <p>The stream runs on a daemon thread of our own through {@code start()}: {@code startAsync()} makes a non-daemon
 * thread, which keeps a process alive after its main thread returns.</p>
 */
final class JfrJvmEvents implements CgJvmEvents.Stream {

    private final RecordingStream stream = new RecordingStream();
    private volatile long offset;
    /** Safepoint id to its begin time. Touched only by the stream's thread. */
    private final Map<Long, Long> begins = new HashMap<>();

    JfrJvmEvents() {
        resample();
        for (String name : new String[] {"jdk.Compilation", "jdk.Deoptimization", "jdk.SafepointBegin",
                "jdk.SafepointEnd", "jdk.ClassLoad"}) {
            stream.enable(name).withoutThreshold().withoutStackTrace();
        }
        stream.onFlush(this::resample);
        stream.onEvent("jdk.Compilation", this::compilation);
        stream.onEvent("jdk.Deoptimization", this::deoptimisation);
        stream.onEvent("jdk.SafepointBegin", this::safepointBegin);
        stream.onEvent("jdk.SafepointEnd", this::safepointEnd);
        stream.onEvent("jdk.ClassLoad", this::classLoad);
        Set<Long> before = running();
        Thread thread = new Thread(stream::start, "cg-jvm-events-stream");
        thread.setDaemon(true);
        thread.start();
        awaitRecording(before);
    }

    private static Set<Long> running() {
        Set<Long> ids = new HashSet<>();
        for (Recording r : FlightRecorder.getFlightRecorder().getRecordings()) {
            if (r.getState() == RecordingState.RUNNING) ids.add(r.getId());
        }
        return ids;
    }

    /** Returns once the stream's recording is running, so an event right after the start is not missed. */
    private static void awaitRecording(Set<Long> before) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            Set<Long> now = running();
            now.removeAll(before);
            if (!now.isEmpty()) return;
            Thread.onSpinWait();
        }
    }

    @Override
    public void close() {
        stream.close();
    }

    private void resample() {
        Instant now = Instant.now();
        long nano = System.nanoTime();
        offset = nano - (now.getEpochSecond() * 1_000_000_000L + now.getNano());
    }

    private long nanos(Instant time) {
        return time.getEpochSecond() * 1_000_000_000L + time.getNano() + offset;
    }

    private static String method(RecordedEvent e) {
        RecordedMethod m = e.getValue("method");
        return m == null ? "?" : m.getType().getName() + "." + m.getName();
    }

    private void compilation(RecordedEvent e) {
        CgJvmEvents.record(CgJvmEventKind.COMPILATION, method(e), "level " + e.getValue("compileLevel"),
                nanos(e.getStartTime()), e.getDuration().toNanos());
    }

    private void deoptimisation(RecordedEvent e) {
        CgJvmEvents.record(CgJvmEventKind.DEOPTIMIZATION, method(e), e.getString("reason") + "/" + e.getString("action"),
                nanos(e.getStartTime()), 0L);
    }

    private void safepointBegin(RecordedEvent e) {
        begins.put(e.getLong("safepointId"), nanos(e.getStartTime()));
    }

    private void safepointEnd(RecordedEvent e) {
        long id = e.getLong("safepointId");
        Long begin = begins.remove(id);
        if (begin == null) return;
        CgJvmEvents.record(CgJvmEventKind.SAFEPOINT, "", "id " + id, begin, Math.max(0L, nanos(e.getEndTime()) - begin));
    }

    private void classLoad(RecordedEvent e) {
        RecordedClass loaded = e.getValue("loadedClass");
        CgJvmEvents.record(CgJvmEventKind.CLASS_LOAD, loaded == null ? "?" : loaded.getName(), "",
                nanos(e.getStartTime()), e.getDuration().toNanos());
    }
}
