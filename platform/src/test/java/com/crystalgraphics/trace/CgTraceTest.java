package com.crystalgraphics.trace;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The trace engine's gates — T0 (zones and the mask), T1 (the frame ring) and T2 (counters, markers
 * and spans), from {@code plan/platform-trace-engine.md} §5.
 *
 * <p>Everything here drives a synthetic clock or a synthetic frame loop, because the one thing that
 * cannot be asserted against is whatever the machine running the suite happened to do.</p>
 */
public class CgTraceTest {

    private static final CgTraceChannel UI = CgTrace.channel("test.ui");
    private static final CgTraceChannel GL = CgTrace.channel("test.gl");
    private static final CgTraceChannel OTHER = CgTrace.channel("other.thing");

    @Before
    public void quiet() {
        CgTrace.resetForTesting();
    }

    // ── T0: channels and the mask ───────────────────────────────────────────────────────────

    @Test
    public void aChannelIsRegisteredOnceHoweverOftenItIsDeclared() {
        assertEquals(UI, CgTrace.channel("test.ui"));
        assertEquals(UI.index(), CgTrace.channel("test.ui").index());
    }

    @Test
    public void nothingRecordsUntilAChannelIsEnabled() {
        assertFalse(CgTrace.isRecording());
        try (CgTrace.Zone z = CgTrace.zone(UI, "ignored")) {
            // the whole point: this costs a bit test and stores nothing
        }
        assertTrue(CgTrace.snapshot().zones().isEmpty());
    }

    @Test
    public void aPrefixEnablesEveryChannelBeneathItAndNoOther() {
        CgTrace.enable("test");
        assertTrue(UI.isEnabled());
        assertTrue(GL.isEnabled());
        // THE WHOLE REASON THE MASK EXISTS: asking for one owner's instrumentation must not turn on
        // everybody else's.
        assertFalse(OTHER.isEnabled());

        CgTrace.disable("test.gl");
        assertTrue(UI.isEnabled());
        assertFalse(GL.isEnabled());
    }

    @Test
    public void aPrefixDoesNotMatchAChannelThatMerelyStartsWithTheSameLetters() {
        CgTraceChannel similar = CgTrace.channel("testing.other");
        CgTrace.enable("test");
        assertTrue(UI.isEnabled());
        assertFalse("`test` must not reach `testing.other`", similar.isEnabled());
    }

    @Test
    public void theEnabledSetRoundTripsThroughNamesRatherThanBits() {
        CgTrace.enable("test.ui");
        List<String> saved = CgTrace.enabledNames();
        assertEquals(List.of("test.ui"), saved);

        CgTrace.disableAll();
        assertFalse(UI.isEnabled());

        // A BIT INDEX IS NOT STABLE ACROSS RUNS -- class-load order decides it -- so a setting that
        // persisted a long would read back meaning a different channel. Names are the contract.
        CgTrace.enableOnly(saved);
        assertTrue(UI.isEnabled());
    }

    // ── T0: zones ───────────────────────────────────────────────────────────────────────────

    @Test
    public void aZoneRecordsItsNameDepthAndChannel() {
        CgTrace.enable("test");
        try (CgTrace.Zone outer = CgTrace.zone(UI, "outer")) {
            try (CgTrace.Zone inner = CgTrace.zone(UI, "inner")) {
                spin();
            }
        }
        List<CgTraceSnapshot.ZoneView> zones = CgTrace.snapshot().zones();
        assertEquals(2, zones.size());
        assertEquals("outer", zones.get(0).name());
        assertEquals(0, zones.get(0).depth());
        assertEquals("inner", zones.get(1).name());
        assertEquals(1, zones.get(1).depth());
        assertEquals("test.ui", zones.get(1).channel());
        assertTrue(zones.get(0).durationNanos() >= zones.get(1).durationNanos());
    }

    @Test
    public void aDisabledZoneNestedInsideAnEnabledOneStillPairs() {
        CgTrace.enable("test.ui");
        try (CgTrace.Zone outer = CgTrace.zone(UI, "outer")) {
            // GL is off, so this hands back the shared no-op. If its close popped anything, `outer`
            // would be closed here and every zone after it would sit at the wrong depth.
            try (CgTrace.Zone off = CgTrace.zone(GL, "invisible")) {
                spin();
            }
            try (CgTrace.Zone sibling = CgTrace.zone(UI, "sibling")) {
                spin();
            }
        }
        List<CgTraceSnapshot.ZoneView> zones = CgTrace.snapshot().zones();
        assertEquals(2, zones.size());
        assertEquals("outer", zones.get(0).name());
        assertEquals("sibling", zones.get(1).name());
        assertEquals("the no-op close popped a real zone", 1, zones.get(1).depth());
    }

    @Test
    public void theTokenFormIsTheSameZone() {
        CgTrace.enable("test");
        long token = CgTrace.begin(UI, "tokened");
        assertTrue(token != 0L);
        spin();
        CgTrace.end(token);

        assertEquals(1, CgTrace.snapshot().zones().size());
        assertEquals("tokened", CgTrace.snapshot().zones().get(0).name());
    }

    @Test
    public void aTokenFromADisabledChannelIsZeroAndClosesNothing() {
        CgTrace.enable("test.ui");
        try (CgTrace.Zone outer = CgTrace.zone(UI, "outer")) {
            long token = CgTrace.begin(GL, "off");
            assertEquals(0L, token);
            CgTrace.end(token);
            spin();
        }
        List<CgTraceSnapshot.ZoneView> zones = CgTrace.snapshot().zones();
        assertEquals(1, zones.size());
        assertFalse("the outer zone was closed by a zero token", zones.get(0).isOpen());
    }

    @Test
    public void everyNameCarriesTheSourceItWasFirstUsedFrom() {
        CgTrace.enable("test");
        try (CgTrace.Zone z = CgTrace.zone(UI, "located")) {
            spin();
        }
        String source = CgTrace.snapshot().zones().get(0).source();
        // THIS IS WHAT MAKES A REPORT JUMPABLE -- a line ending in File.java:line is a next step,
        // where a bare duration is only a fact.
        assertNotNull("no source location was captured", source);
        // THE CALLER, not JUnit and not the engine: the whole value is that it points at the line that
        // opened the zone. The line NUMBER is deliberately not pinned -- that would fail on every edit
        // to this file and would be testing the compiler rather than the walk.
        assertTrue("not this test's own call site: " + source,
                source.startsWith("com/crystalgraphics/trace/CgTraceTest.java:"));
        int line = Integer.parseInt(source.substring(source.lastIndexOf(':') + 1));
        assertTrue("no line number in " + source, line > 0);
    }

    // ── T1: the frame ring ──────────────────────────────────────────────────────────────────

    @Test
    public void aFrameIsCommittedByTheNextOneAndCarriesItsWallTime() {
        CgTrace.enable("test");
        CgTrace.frameBegin();
        spin();
        CgTrace.frameEnd();
        CgTrace.frameBegin();
        spin();
        CgTrace.frameEnd();
        CgTrace.frameBegin();

        List<CgFrameRecord> frames = CgTrace.snapshot().frames();
        assertEquals("the open frame must not be reported", 2, frames.size());
        assertEquals(0L, frames.get(0).index());
        assertEquals(1L, frames.get(1).index());
        assertTrue(frames.get(0).wallNanos() > 0L);
        assertTrue(frames.get(0).hasCpu());
    }

    @Test
    public void aFrameThatIsNeverPaintedStillReports() {
        CgTrace.enable("test");
        // NO frameEnd AT ALL -- a document framed without being painted, which is what a headless step
        // or a minimised window is. The old collector recorded nothing here and sat on "warming up".
        CgTrace.frameBegin();
        spin();
        CgTrace.frameBegin();

        List<CgFrameRecord> frames = CgTrace.snapshot().frames();
        assertEquals(1, frames.size());
        assertTrue(frames.get(0).wallNanos() > 0L);
        assertFalse("an absent CPU mark must read as absent", frames.get(0).hasCpu());
        assertEquals(CgFrameRecord.ABSENT, frames.get(0).cpuNanos());
    }

    @Test
    public void theRingKeepsTheNewestFramesAndForgetsTheOldest() {
        CgTrace.enable("test");
        int capacity = CgTrace.frameCapacity();
        int drive = capacity + 400;
        for (int i = 0; i < drive; i++) {
            CgTrace.frameBegin();
            CgTrace.frameEnd();
        }
        CgTrace.frameBegin();

        CgTraceSnapshot snap = CgTrace.snapshot();
        List<CgFrameRecord> frames = snap.frames();
        assertEquals(capacity, frames.size());
        // The oldest held is `drive - capacity`, and indices never repeat.
        assertEquals(drive - capacity, frames.get(0).index());
        assertEquals(drive - 1, frames.get(frames.size() - 1).index());
        assertNull("a frame that aged out must not be findable", snap.frame(0));
        assertNotNull(snap.frame(drive - 1));
    }

    @Test
    public void aZoneIsAttributedToTheFrameItStartedIn() {
        CgTrace.enable("test");
        CgTrace.frameBegin();
        try (CgTrace.Zone z = CgTrace.zone(UI, "inFrameZero")) {
            spin();
        }
        CgTrace.frameBegin();
        try (CgTrace.Zone z = CgTrace.zone(UI, "inFrameOne")) {
            spin();
        }
        CgTrace.frameBegin();

        CgTraceSnapshot snap = CgTrace.snapshot();
        assertEquals(List.of("inFrameZero"), names(snap.zonesIn(snap.frame(0))));
        assertEquals(List.of("inFrameOne"), names(snap.zonesIn(snap.frame(1))));
    }

    @Test
    public void aZoneLeftOpenAcrossAFrameBoundaryIsClosedAndCounted() {
        CgTrace.enable("test");
        CgTrace.frameBegin();
        // Deliberately unbalanced: the token is thrown away without an end().
        CgTrace.begin(UI, "leaked");
        spin();
        CgTrace.frameBegin();
        CgTrace.frameBegin();

        CgTraceSnapshot snap = CgTrace.snapshot();
        assertEquals(1, snap.frames().get(0).leakedZones());
        // AND IT IS CLOSED, not left open: in a depth-carrying arena a zone that stays open shifts
        // every zone after it, so the boundary ends it rather than letting the damage spread.
        assertFalse(snap.zones().get(0).isOpen());
    }

    // ── T2: counters, markers, spans ────────────────────────────────────────────────────────

    @Test
    public void aCounterMayTakeSeveralValuesInOneFrame() {
        CgTrace.enable("test");
        CgTrace.frameBegin();
        CgTrace.counter(UI, "layers", 3);
        CgTrace.counter(UI, "layers", 9);
        CgTrace.counter(UI, "drawcalls", 31);
        CgTrace.frameBegin();

        CgTraceSnapshot snap = CgTrace.snapshot();
        List<CgTraceSnapshot.CounterView> counters = snap.countersIn(snap.frame(0));
        assertEquals(3, counters.size());
        // THIS IS WHAT ABSORBS THE OLD `sample()`: its callers record a distribution, and one value
        // per frame per name would lose that at every one of them.
        long layers = counters.stream().filter(c -> c.name().equals("layers")).count();
        assertEquals(2, layers);
    }

    @Test
    public void aMarkerCarriesItsAttribution() {
        CgTrace.enable("test");
        CgTrace.marker(UI, "rematched", "Tooltip.reposition:214");

        // Enabling a channel writes a `trace:mask` marker of its own, so this asks for its own.
        List<CgTraceSnapshot.MarkerView> markers = CgTrace.snapshot().markers().stream()
                .filter(m -> m.name().equals("rematched")).toList();
        assertEquals(1, markers.size());
        assertEquals("Tooltip.reposition:214", markers.get(0).detail());
    }

    @Test
    public void spansNestAndOutliveTheFrameTheyStartedIn() {
        CgTrace.enable("test");
        CgTrace.frameBegin();
        long open = CgTrace.spanBegin(UI, "open file");
        long search = CgTrace.spanBegin(UI, "search");
        CgTrace.frameBegin();          // the chain crosses a frame boundary, which is the point
        CgTrace.spanEnd(search);
        CgTrace.spanEnd(open);
        CgTrace.frameBegin();

        List<CgTraceSnapshot.SpanView> spans = CgTrace.snapshot().spans();
        assertEquals(2, spans.size());
        CgTraceSnapshot.SpanView root = spans.get(0);
        CgTraceSnapshot.SpanView child = spans.get(1);
        assertEquals("open file", root.name());
        assertEquals("search", child.name());
        assertEquals("the chain lost its nesting", root.id(), child.parent());
        assertEquals(CgTraceEvents.NO_PARENT, root.parent());
        assertFalse(root.isOpen());
    }

    @Test
    public void aCompletedStepNestsUnderWhateverChainIsOpen() {
        CgTrace.enable("test");
        long chain = CgTrace.spanBegin(UI, "open file");
        long started = System.nanoTime();
        spin();
        CgTrace.spanDone(UI, "parse", started);
        CgTrace.spanEnd(chain);

        List<CgTraceSnapshot.SpanView> spans = CgTrace.snapshot().spans();
        assertEquals(2, spans.size());
        CgTraceSnapshot.SpanView parse = spans.stream()
                .filter(s -> s.name().equals("parse")).findFirst().orElseThrow();
        assertEquals(chain, parse.parent());
        assertTrue(parse.durationNanos() > 0L);
    }

    @Test
    public void changingTheMaskIsAnEventRatherThanASilentMutation() {
        CgTrace.enable("test.ui");
        CgTrace.enable("test.gl");

        List<CgTraceSnapshot.MarkerView> masks = CgTrace.snapshot().markers().stream()
                .filter(m -> m.name().equals("trace:mask")).toList();
        // A RANGE THAT SPANS ONE IS NOT COMPARABLE WITH ITSELF, and a viewer that did not know would
        // draw the difference as a performance change.
        assertEquals(2, masks.size());
        assertEquals("test.ui,test.gl", masks.get(1).detail());
    }

    // ── T0: what it costs ───────────────────────────────────────────────────────────────────

    /**
     * The cost gate, measured the way this build can measure it.
     *
     * <p>There is no JMH here, so this is a warmed loop timed as a whole and divided — crude against a
     * real harness, and decisive at the scale that matters, because the two numbers being told apart
     * are "free" and "two clock reads". The assertions are deliberately loose (roughly 3x the target)
     * so ordinary jitter does not fail a build while a real regression still does; the printed figures
     * are the ones to read.</p>
     */
    @Test
    public void aDisabledZoneIsFreeAndAnEnabledOneIsTwoClockReads() {
        final int runs = 5;
        final int iterations = 2_000_000;

        double off = Double.MAX_VALUE;
        double on = Double.MAX_VALUE;
        // INTERLEAVED AND BEST-OF, because the two numbers are measured on a machine that is also doing
        // something else. Taking all the disabled runs first would charge one of them whatever the
        // machine was busy with at the time, and the comparison below is between them.
        for (int r = 0; r < runs; r++) {
            CgTrace.disableAll();
            off = Math.min(off, timeZones(iterations));
            CgTrace.enable("test.ui");
            on = Math.min(on, timeZones(iterations));
        }

        System.out.printf("[trace] zone cost: disabled %.2fns, enabled %.2fns (ratio %.1fx)%n",
                off, on, on / off);

        // THE GATE IS A RATIO, and that is the honest form of it. There is no JMH here, so an absolute
        // nanosecond figure is a statement about this machine on this afternoon -- measured at 0.50ns
        // on an idle one and 8.11ns on the same code while a build ran. What does not move is the
        // SHAPE: a disabled zone is a mask test and an enabled one is two clock reads, so the second
        // must cost several times the first. A regression that made the disabled path allocate, look
        // up a thread-local or build a string would collapse that ratio on any machine.
        assertTrue("disabled " + off + "ns vs enabled " + on + "ns: the disabled path is doing work",
                off * 4.0d < on);
        // And a loose absolute ceiling, to catch the case where BOTH got slow together.
        assertTrue("a disabled zone cost " + off + "ns; it should be a bit test", off < 25.0d);
        assertTrue("an enabled zone cost " + on + "ns; budget is two nanoTime() calls", on < 400.0d);
    }

    private static double timeZones(int iterations) {
        int name = CgTrace.name("bench");
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (CgTrace.Zone z = CgTrace.zone(UI, name)) {
                // empty on purpose: what is being measured is the bracket itself
            }
        }
        return (System.nanoTime() - start) / (double) iterations;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private static List<String> names(List<CgTraceSnapshot.ZoneView> zones) {
        return zones.stream().map(CgTraceSnapshot.ZoneView::name).toList();
    }

    /** Burns a little wall time so a zone has a duration the clock can see. */
    private static void spin() {
        long until = System.nanoTime() + 20_000L;
        while (System.nanoTime() < until) {
            // deliberate
        }
    }
}
