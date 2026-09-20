package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T5a — the aggregation under the report, and the report's five rules.
 *
 * <p>Everything is driven on a synthetic clock, because a distribution cannot be asserted against
 * whatever the machine running the suite happened to do.</p>
 */
public class CgTraceReportTest {

    private static final CgTraceChannel UI = CgTrace.channel("report.ui");

    private long clock = 4_000_000_000L;

    @Before
    public void quiet() {
        CgTrace.resetForTesting();
        CgTraceHints.clear();
        CgTrace.enable("report");
        clock = 4_000_000_000L;
    }

    @After
    public void closed() {
        CgTraceHints.clear();
        CgTrace.resetForTesting();
    }

    /**
     * A zone at {@code startNanos}, lasting {@code micros}.
     *
     * <p>Every name here is prefixed {@code report:} because a name is interned ONCE per process and
     * carries the location of whoever first used it — so a name another test class also uses would
     * attribute this file's zones to that one, and the source assertion below would pass or fail on
     * test order.</p>
     */
    private void zone(String name, int depth, long startNanos, long micros) {
        // The arena records the depth that is open, so nesting is driven by real pushes.
        CgTrace.zoneDone(UI, name, startNanos, startNanos + micros * 1_000L);
    }

    // ── The aggregation ─────────────────────────────────────────────────────────────────────

    @Test
    public void aTreeIsBuiltFromRecordedDepthAndSelfTimeExcludesChildren() {
        CgTrace.frameBegin(clock);
        // paint:tree { layer:clear, layer:blit } -- opened for real so the depths are the arena's.
        try (CgTrace.Zone outer = CgTrace.zone(UI, "report:paint")) {
            try (CgTrace.Zone a = CgTrace.zone(UI, "report:clear")) {
                burn(2000);
            }
            try (CgTrace.Zone b = CgTrace.zone(UI, "report:blit")) {
                burn(2000);
            }
            burn(1000);
        }
        CgTrace.frameEnd(System.nanoTime());
        CgTrace.frameBegin(System.nanoTime());

        CgFrameRecord frame = CgTrace.snapshot().frames().get(0);
        List<CgTraceAggregate.Node> roots = CgTraceAggregate.tree(CgTrace.zonesIn(frame));
        assertEquals(1, roots.size());
        CgTraceAggregate.Node paint = roots.get(0);
        assertEquals("report:paint", paint.name());
        assertEquals(2, paint.children().size());
        // SELF IS THE BODY, not the subtree: the whole reason a flame chart and a report both need this
        // computed once rather than derived twice.
        assertTrue("self " + paint.selfNanos() + " was not less than total " + paint.durationNanos(),
                paint.selfNanos() < paint.durationNanos());
        long childSum = paint.children().get(0).durationNanos() + paint.children().get(1).durationNanos();
        assertEquals(paint.durationNanos() - childSum, paint.selfNanos());
    }

    @Test
    public void aZoneThatBeganAfterItsApparentParentEndedIsReparentedOutward() {
        CgTrace.frameBegin(clock);
        // Depth says "child", time says otherwise -- the shape a force-closed leak leaves behind.
        CgTrace.zoneDone(UI, "parent", clock, clock + 1_000_000L);
        CgTrace.zoneDone(UI, "after", clock + 5_000_000L, clock + 6_000_000L);
        CgTrace.frameBegin(clock + 20_000_000L);

        List<CgTraceAggregate.Node> roots =
                CgTraceAggregate.tree(CgTrace.zonesIn(CgTrace.snapshot().frames().get(0)));
        // Both are roots: containment is the guard that stops a whole frame nesting under one row.
        assertEquals(2, roots.size());
    }

    @Test
    public void statisticsRollUpEveryInstanceAndSortDeterministically() {
        CgTrace.frameBegin(clock);
        zone("report:clear", 0, clock, 3000);
        zone("report:clear", 0, clock + 4_000_000L, 1000);
        zone("report:layout", 0, clock + 6_000_000L, 5000);
        CgTrace.frameBegin(clock + 20_000_000L);

        List<CgTraceAggregate.Stat> stats =
                CgTraceAggregate.byCost(CgTrace.zonesIn(CgTrace.snapshot().frames().get(0)));
        assertEquals(2, stats.size());
        // Sorted by total cost descending: layout at 5ms beats two clears totalling 4ms.
        assertEquals("report:layout", stats.get(0).name());
        assertEquals("report:clear", stats.get(1).name());
        assertEquals(2, stats.get(1).count());
        assertEquals(4.0d, stats.get(1).totalMillis(), 0.01d);
        assertEquals(1.0d, stats.get(1).minNanos() / 1_000_000d, 0.01d);
        assertEquals(3.0d, stats.get(1).maxMillis(), 0.01d);
    }

    // ── The report ──────────────────────────────────────────────────────────────────────────

    /** A short run with a slow frame in it. */
    private void run() {
        for (int i = 0; i < 20; i++) {
            clock += (i == 9 ? 40_000_000L : 8_000_000L);
            CgTrace.frameBegin(clock);
            zone("report:paint", 0, clock, i == 9 ? 30_000 : 4_000);
            CgTrace.counter(UI, "drawcalls", 31);
            CgTrace.counter(UI, "layer-clear-kpx", i == 9 ? 24_000 : 900);
            CgTrace.frameEnd(clock + (i == 9 ? 35_000_000L : 5_000_000L));
        }
        clock += 8_000_000L;
        CgTrace.frameBegin(clock);
    }

    @Test
    public void theVerdictIsShortEnoughToRead() {
        run();
        String verdict = CgTraceReport.of(CgTrace.snapshot()).budget(16.7d).verdict();
        int lines = verdict.split("\n", -1).length;
        // THE ANALOGUE OF THE HUD'S 46-CHARACTER RULE: a report that floods its reader has destroyed
        // the reader's ability to act on it.
        assertTrue("the verdict was " + lines + " lines:\n" + verdict, lines <= 25);
        assertTrue(verdict, verdict.contains("VERDICT"));
        assertTrue(verdict, verdict.contains("report:paint"));
    }

    @Test
    public void everyZoneLineEndsInTheSourceThatOpenedIt() {
        run();
        String verdict = CgTraceReport.of(CgTrace.snapshot()).verdict();
        // The whole reason a name carries a location: a duration is a fact, a duration with a file and
        // a line is a next step.
        assertTrue(verdict, verdict.contains("CgTraceReportTest.java:"));
    }

    @Test
    public void twoRendersOfOneSnapshotAreIdentical() {
        run();
        CgTraceSnapshot snapshot = CgTrace.snapshot();
        String first = CgTraceReport.of(snapshot).verdict();
        String second = CgTraceReport.of(snapshot).verdict();
        // DETERMINISM IS THE POINT: a before-and-after is a `diff`, which needs no tooling and no
        // window -- and which a wall-clock or an unstable sort would destroy.
        assertEquals(first, second);
        assertFalse("a wall-clock leaked into the body", first.contains("20260"));
    }

    @Test
    public void theHeaderNamesWhatWasNotRecording() {
        // REGISTERED AND LEFT OFF, by this test rather than by whichever other test class happened to
        // run first in this JVM: the first version asserted on channels it did not create.
        CgTraceChannel quiet = CgTrace.channel("report.quiet");
        CgTrace.setEnabled(quiet, false);
        run();

        String verdict = CgTraceReport.of(CgTrace.snapshot()).verdict();
        // A reader who cannot see that a channel was off reads a gap as work that did not happen.
        assertTrue(verdict, verdict.contains("recording: "));
        assertTrue(verdict, verdict.contains("NOT recording:"));
        assertTrue(verdict, verdict.contains("report.quiet"));
    }

    @Test
    public void aRuleAccusesTheFrameItWasGiven() {
        CgTraceHints.register((frame, zones, counters, out) -> {
            if (counters.getOrDefault("layer-clear-kpx", 0L) > 8_000L) {
                out.add(new CgTraceHints.Hint("LAYER-BOUND", "too much clearing", "§8"));
            }
        });
        run();
        String verdict = CgTraceReport.of(CgTrace.snapshot()).verdict();
        assertTrue(verdict, verdict.contains("HINT"));
        assertTrue(verdict, verdict.contains("LAYER-BOUND"));
        assertTrue("the link is what makes a hint actionable", verdict.contains("->"));
    }

    @Test
    public void aRuleThatThrowsCostsItsOwnFindingAndNothingElse() {
        CgTraceHints.register((frame, zones, counters, out) -> {
            throw new IllegalStateException("broken rule");
        });
        CgTraceHints.register((frame, zones, counters, out) ->
                out.add(new CgTraceHints.Hint("STILL-HERE", "the other rule ran")));
        run();
        String verdict = CgTraceReport.of(CgTrace.snapshot()).verdict();
        assertTrue(verdict, verdict.contains("STILL-HERE"));
    }

    @Test
    public void compareSortsByTheSizeOfTheChange() {
        run();
        String delta = CgTraceReport.of(CgTrace.snapshot()).compare(0, 9, 10, 19);
        assertTrue(delta, delta.contains("COMPARE"));
        assertTrue(delta, delta.contains("report:paint"));
        assertTrue("no delta column", delta.contains("delta"));
    }

    @Test
    public void askingForAFrameTheRingHasLostSaysSo() {
        run();
        String missing = CgTraceReport.of(CgTrace.snapshot()).frame(99_999L);
        assertTrue(missing, missing.contains("not in the ring"));
    }

    @Test
    public void aZoneNobodyRecordedSaysSoRatherThanPrintingZeroes() {
        run();
        String none = CgTraceReport.of(CgTrace.snapshot()).zone("never:happened");
        assertTrue(none, none.contains("no zone named"));
        String real = CgTraceReport.of(CgTrace.snapshot()).zone("report:paint");
        assertNotNull(real);
        assertTrue(real, real.contains("count "));
    }

    private static void burn(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            // deliberate
        }
    }
}
