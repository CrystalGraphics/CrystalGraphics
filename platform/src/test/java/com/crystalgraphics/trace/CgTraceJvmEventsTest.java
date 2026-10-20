package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The trace's JVM events: JIT compilations, deoptimisations, safepoints and class loads, each on the frame it happened
 * in, and a fallback that says what it cannot see.
 *
 * <h3>What the JVM reports, and what this relies on</h3>
 *
 * <p>From HotSpot's {@code src/hotspot/share/jfr/metadata/metadata.xml} (the JFR event definitions) and
 * {@code jdk.jfr.consumer.RecordingStream}:</p>
 * <ul>
 *   <li>{@code jdk.Compilation}: fields {@code method} (a {@code RecordedMethod}), {@code compileLevel} (ushort),
 *       {@code succeded} (sic), {@code isOsr}, {@code codeSize}; a duration event, thread-attributed;</li>
 *   <li>{@code jdk.Deoptimization}: {@code method}, {@code lineNumber}, {@code bci}, {@code instruction},
 *       {@code reason}, {@code action}; {@code startTime="false"}, so it is an instant;</li>
 *   <li>{@code jdk.SafepointBegin} / {@code jdk.SafepointEnd}: both carry {@code safepointId}; Begin's duration is the
 *       time to bring threads to the safepoint, so a safepoint's length is Begin's start to End's end, paired by id;</li>
 *   <li>{@code jdk.ClassLoad}: {@code loadedClass}, {@code definingClassLoader}, {@code initiatingClassLoader}; a
 *       duration event with a stack trace, off by default.</li>
 * </ul>
 * <p>The default thresholds (100 ms for {@code jdk.Compilation}, 10 ms for the safepoint events) hide everything this
 * gate looks for, so a stream built for the trace enables each event {@code withoutThreshold()}.
 * {@code RecordingStream} delivers events in batches about a second after they happen, and times them in epoch
 * time: a probe on HotSpot 21 put an event 1 ms from {@code System.nanoTime()} taken at the action. So every action
 * here sits at least 100 ms from its frame's edges, and every assertion waits for delivery instead of sleeping a fixed
 * second.</p>
 *
 * <h3>The API this fixes</h3>
 * <ul>
 *   <li>{@code CgJvmEventKind}: {@code COMPILATION}, {@code DEOPTIMIZATION}, {@code SAFEPOINT}, {@code CLASS_LOAD};</li>
 *   <li>{@code CgTrace.startJvmEvents()} streams JFR events where the JVM has them (Java 14+) and returns true;
 *       otherwise it starts the fallback and returns false. {@code CgTrace.startJvmEventsFallback()} starts the
 *       fallback whatever the JVM; {@code CgTrace.stopJvmEvents()} stops either; both starts are idempotent;</li>
 *   <li>{@code CgTrace.jvmEventsAvailable(kind)}: whether the running source can see the kind at all;</li>
 *   <li>{@code CgTrace.jvmEventsIn(frame)} → {@code List<CgTraceSnapshot.JvmEventView(kind, subject, detail,
 *       startNanos, durationNanos)>}: events whose start is inside the frame. Subject: the method as
 *       {@code Type.method}, or the class loaded. Detail: {@code level N} for a compilation,
 *       {@code reason/action} for a deoptimisation, {@code id N} for a safepoint. A safepoint's duration spans Begin
 *       to End;</li>
 *   <li>{@code CgTrace.jvmEventCountIn(frame, kind)}: the count, or {@code CgFrameRecord.ABSENT} where the source
 *       cannot see the kind (absent is not zero);</li>
 *   <li>the fallback (Java 8's, and what {@code startJvmEventsFallback} runs here): class loads counted per frame from
 *       {@code ClassLoadingMXBean}; compilations, deoptimisations and safepoints unavailable;</li>
 *   <li>{@code CgTraceReport.frame(i)} gains a line {@code jvm  compilations N  deoptimisations N  safepoints N
 *       class loads N}, a kind the source cannot see printing {@code <name> unavailable}, and under it one row per
 *       deoptimisation, safepoint and class load (subject and detail), capped.</li>
 * </ul>
 */
public class CgTraceJvmEventsTest {

    /** Registers the channel: enabling a name no channel has does nothing, and frames then record nothing. */
    private static final CgTraceChannel CH = CgTrace.channel("t2gate");

    private static final int MAGIC = 123_456_789;
    private static final long WAIT_MILLIS = 30_000L;

    /** Kept reachable so the work under test cannot be elided. */
    private static volatile long sink;

    /** A hot method that never takes its branch, so C2 compiles the branch away. One class per test: JIT state is per method. */
    static final class HotA {
        static int branch(int x) {
            if (x == MAGIC) return x * 31 + 7;
            return x + 1;
        }
    }

    static final class HotB {
        static int branch(int x) {
            if (x == MAGIC) return x * 31 + 7;
            return x + 1;
        }
    }

    /** Never named in code, only by string, so the first load happens inside the frame under test. */
    static final class LateOne {
    }

    static final class LateTwo {
    }

    @Before
    public void on() {
        CgTrace.resetForTesting();
        CgTrace.enable("t2gate");
        CgTrace.frameBegin();    // frame 0 exists, so every test's frames are 1, 2, ...
    }

    @After
    public void off() {
        CgTrace.stopJvmEvents();
        CgTrace.resetForTesting();
    }

    @Test
    public void aStreamStartsOnThisJvmAndSeesEveryKind() {
        assertTrue("HotSpot 14+ streams JFR events", CgTrace.startJvmEvents());
        assertTrue(CgTrace.startJvmEvents());   // idempotent
        for (CgJvmEventKind kind : CgJvmEventKind.values()) assertTrue(kind.name(), CgTrace.jvmEventsAvailable(kind));
    }

    @Test
    public void aHotMethodsCompilationIsReportedOnItsFrame() throws InterruptedException {
        Assume.assumeTrue(CgTrace.startJvmEvents());
        List<Long> warm = new ArrayList<>();
        CgTraceSnapshot.JvmEventView compiled = null;
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (compiled == null && System.nanoTime() < deadline) {
            CgTrace.frameBegin();
            warm.add(CgTrace.currentFrameIndex());
            for (int i = 0; i < 200_000; i++) sink += HotA.branch(i & 0xFFFF);
            Thread.sleep(20);
            compiled = findIn(warm.subList(0, warm.size() - 1), CgJvmEventKind.COMPILATION, "HotA.branch", "level 4");
        }
        assertNotNull("a level 4 compilation of the hot method is reported", compiled);
        assertTrue(compiled.durationNanos() >= 0L);
        CgTrace.frameBegin();
        long owner = -1L;
        for (long frame : warm) {
            CgFrameRecord record = CgTrace.frame(frame);
            if (record != null && record.contains(compiled.startNanos())) owner = frame;
        }
        assertNotEquals("the compilation lies inside one of the frames that ran the loop", -1L, owner);
        assertTrue(CgTrace.jvmEventCountIn(CgTrace.frame(owner), CgJvmEventKind.COMPILATION) >= 1L);
    }

    @Test
    public void aForcedDeoptimisationLandsInTheFrameThatForcedIt() throws InterruptedException {
        Assume.assumeTrue(CgTrace.startJvmEvents());
        // Warm until C2 has compiled the method with its rare branch pruned.
        List<Long> warm = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (findIn(warm.isEmpty() ? warm : warm.subList(0, warm.size() - 1), CgJvmEventKind.COMPILATION,
                "HotB.branch", "level 4") == null) {
            assertTrue("C2 compiled the hot method in time", System.nanoTime() < deadline);
            CgTrace.frameBegin();
            warm.add(CgTrace.currentFrameIndex());
            for (int i = 0; i < 200_000; i++) sink += HotB.branch(i & 0xFFFF);
            Thread.sleep(20);
        }

        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        Thread.sleep(100);
        sink += HotB.branch(MAGIC);   // the pruned branch: an uncommon trap
        Thread.sleep(100);
        CgTrace.frameBegin();         // commits the frame
        CgTrace.frameBegin();

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        List<CgTraceSnapshot.JvmEventView> deopts = awaitEvents(record, CgJvmEventKind.DEOPTIMIZATION, "HotB.branch");
        assertFalse("the deoptimisation lands on the frame that forced it", deopts.isEmpty());
        CgTraceSnapshot.JvmEventView deopt = deopts.get(0);
        assertTrue(deopt.detail(), deopt.detail().contains("unstable_if"));
        assertTrue(record.contains(deopt.startNanos()));
        assertTrue(CgTrace.jvmEventCountIn(record, CgJvmEventKind.DEOPTIMIZATION) >= 1L);

        // Not on the frames before or after it.
        for (long other : new long[] {frame - 1, frame + 1}) {
            CgFrameRecord neighbour = CgTrace.frame(other);
            if (neighbour == null) continue;
            for (CgTraceSnapshot.JvmEventView view : CgTrace.jvmEventsIn(neighbour)) {
                assertFalse("only the forcing frame", view.kind() == CgJvmEventKind.DEOPTIMIZATION
                        && view.subject().endsWith("HotB.branch") && view.startNanos() == deopt.startNanos());
            }
        }

        String text = CgTraceReport.of(CgTrace.snapshot()).frame(frame);
        assertTrue("the report names the method:\n" + text, text.contains("HotB.branch"));
        assertTrue("the report names the reason:\n" + text, text.contains("unstable_if"));
        assertTrue("the report counts it:\n" + text, text.contains("deoptimisations 1") || text.matches("(?s).*deoptimisations [1-9].*"));
    }

    @Test
    public void aClassLoadLandsInTheFrameThatLoadedIt() throws Exception {
        Assume.assumeTrue(CgTrace.startJvmEvents());
        String name = CgTraceJvmEventsTest.class.getName() + "$LateOne";
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        Thread.sleep(100);
        Class.forName(name, true, CgTraceJvmEventsTest.class.getClassLoader());
        Thread.sleep(100);
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        List<CgTraceSnapshot.JvmEventView> loads = awaitEvents(record, CgJvmEventKind.CLASS_LOAD, name);
        assertFalse("the class load lands on the frame that did it", loads.isEmpty());
        assertTrue(record.contains(loads.get(0).startNanos()));
        assertTrue(CgTrace.jvmEventCountIn(record, CgJvmEventKind.CLASS_LOAD) >= 1L);
        String text = CgTraceReport.of(CgTrace.snapshot()).frame(frame);
        assertTrue("the report names the class:\n" + text, text.contains(name));
    }

    @Test
    public void aSafepointIsReportedWithItsLength() throws InterruptedException {
        Assume.assumeTrue(CgTrace.startJvmEvents());
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        Thread.sleep(100);
        System.gc();   // a stop-the-world operation: a safepoint
        Thread.sleep(100);
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        List<CgTraceSnapshot.JvmEventView> safepoints = awaitEvents(record, CgJvmEventKind.SAFEPOINT, "");
        assertFalse("the safepoint lands on the frame whose collection caused it", safepoints.isEmpty());
        for (CgTraceSnapshot.JvmEventView sp : safepoints) {
            assertTrue(sp.detail(), sp.detail().startsWith("id "));
            assertTrue("a length, begin to end: " + sp.durationNanos(), sp.durationNanos() >= 0L);
        }
        assertTrue(CgTrace.jvmEventCountIn(record, CgJvmEventKind.SAFEPOINT) >= 1L);
    }

    @Test
    public void theFallbackCountsClassLoadsAndSaysWhatItCannotSee() throws Exception {
        assertFalse("the fallback is not a stream", CgTrace.startJvmEventsFallback());
        assertTrue(CgTrace.jvmEventsAvailable(CgJvmEventKind.CLASS_LOAD));
        assertFalse(CgTrace.jvmEventsAvailable(CgJvmEventKind.COMPILATION));
        assertFalse(CgTrace.jvmEventsAvailable(CgJvmEventKind.DEOPTIMIZATION));
        assertFalse(CgTrace.jvmEventsAvailable(CgJvmEventKind.SAFEPOINT));

        String name = CgTraceJvmEventsTest.class.getName() + "$LateTwo";
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        Class.forName(name, true, CgTraceJvmEventsTest.class.getClassLoader());
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        CgFrameRecord record = CgTrace.frame(frame);
        assertNotNull(record);
        assertTrue("the load is counted on its frame", CgTrace.jvmEventCountIn(record, CgJvmEventKind.CLASS_LOAD) >= 1L);
        assertEquals("absent is not zero", CgFrameRecord.ABSENT, CgTrace.jvmEventCountIn(record, CgJvmEventKind.DEOPTIMIZATION));
        assertEquals(CgFrameRecord.ABSENT, CgTrace.jvmEventCountIn(record, CgJvmEventKind.COMPILATION));
        assertEquals(CgFrameRecord.ABSENT, CgTrace.jvmEventCountIn(record, CgJvmEventKind.SAFEPOINT));
        assertTrue("a count has no per-event rows", CgTrace.jvmEventsIn(record).isEmpty());

        String text = CgTraceReport.of(CgTrace.snapshot()).frame(frame);
        assertTrue("the report says what it cannot see:\n" + text, text.contains("deoptimisations unavailable"));
        assertTrue(text, text.contains("compilations unavailable"));
        assertTrue(text, text.contains("safepoints unavailable"));
        assertFalse("a counted kind is not unavailable:\n" + text, text.contains("class loads unavailable"));
    }

    private static CgTraceSnapshot.JvmEventView findIn(List<Long> frames, CgJvmEventKind kind, String subjectEnd, String detail) {
        for (long index : frames) {
            CgFrameRecord record = CgTrace.frame(index);
            if (record == null) continue;
            for (CgTraceSnapshot.JvmEventView view : CgTrace.jvmEventsIn(record)) {
                if (view.kind() == kind && view.subject().endsWith(subjectEnd) && view.detail().contains(detail)) return view;
            }
        }
        return null;
    }

    /** Waits for the stream's batch: events arrive about a second after they happen. */
    private static List<CgTraceSnapshot.JvmEventView> awaitEvents(CgFrameRecord record, CgJvmEventKind kind, String subjectEnd)
            throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000L;
        while (true) {
            List<CgTraceSnapshot.JvmEventView> out = new ArrayList<>();
            for (CgTraceSnapshot.JvmEventView view : CgTrace.jvmEventsIn(record)) {
                if (view.kind() == kind && view.subject().endsWith(subjectEnd)) out.add(view);
            }
            if (!out.isEmpty() || System.nanoTime() >= deadline) return out;
            Thread.sleep(50);
        }
    }
}
