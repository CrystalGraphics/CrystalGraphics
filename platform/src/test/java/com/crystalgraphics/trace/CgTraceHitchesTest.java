package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The trace's hitches: which frames are one, what each is blamed on, and the tail of the frame-time distribution.
 *
 * <h3>Where the rule comes from</h3>
 *
 * <p>Unreal classifies hitches in {@code Engine/Source/Runtime/Engine/Private/ChartCreation.cpp}
 * ({@code FPerformanceTrackingSystem::AnalyzeFrame}, release branch), after {@code EFrameHitchType} in
 * {@code ChartCreation.h}:</p>
 * <ul>
 *   <li>{@code :1352-1384}: a frame is only a candidate when its delta is at least
 *       {@code GetHitchFrameTimeThresholdMS()} (an absolute floor), more than {@code GetHitchToNonHitchRatio()} times
 *       the <em>previous</em> frame's delta (a relative test), and at least {@code GetMinTimeBetweenHitchesMS()}
 *       after the last hitch (a debounce);</li>
 *   <li>{@code :1377-1403}: the hitch is attributed to the first of game thread, render thread, RHI thread, GPU whose
 *       time reaches the largest thread time (within an epsilon), in that order; none of them is {@code UnknownUnit};</li>
 *   <li>{@code :848-868}: each hitch is counted by its bound and its delta added to a histogram.</li>
 * </ul>
 *
 * <h3>What is taken, and what is not</h3>
 * <ul>
 *   <li>The floor and the ratio are taken, from the frame-time contract: a frame is a hitch when its wall time is
 *       <b>strictly over</b> {@code max(2 x the rolling median, target + 4 ms)}. The median is over the previous
 *       {@link CgHitches#WINDOW} frames, the element at index {@code n/2} of the sorted window (the upper median, as the
 *       report's own median), and no frame is judged before {@link CgHitches#MIN_HISTORY} frames exist.</li>
 *   <li><b>Departure from Unreal</b>: the median replaces the previous frame, because two slow frames in a row would
 *       otherwise judge the second against the first and miss it; and there is no debounce, because two hitches
 *       a few frames apart are two hitches. A run that is slow throughout stops being one once the window's median has
 *       caught up: sustained slowness is a different failure from a hitch.</li>
 *   <li>The bound follows Unreal's order of precedence: the largest of the frame thread's CPU time and the GPU time
 *       wins, a tie going to the frame thread; a frame with neither is {@code UNKNOWN} (Unreal's {@code UnknownUnit}).</li>
 * </ul>
 *
 * <h3>The causes, and their rank</h3>
 *
 * <p>A hitch's causes are what the trace saw in the frame; a frame with none is <em>unexplained</em> and says so. A
 * cause carries its overlap with the frame: a pause that began before the frame counts only the part inside it. The
 * JVM's compilations run beside the frame thread, so they are listed as context and never explain a hitch alone.</p>
 *
 * <p>Order: the stopping causes ({@code GC_PAUSE}, {@code SAFEPOINT}, {@code CLASS_LOAD}) whose overlap is at least
 * 10% of the frame's excess over its median (wall minus median: <em>the floor</em>, relative so it holds at 20 fps and
 * at 300), longest first, ties in that order; then the untimed ones, {@code ENGINE_WAIT}, {@code ALLOCATION},
 * {@code DEOPTIMISATION}, in that order; then the stopping causes under the floor, longest first, kept as context; then
 * {@code COMPILATION}. Evidence under the floor still explains the hitch when it is all there is, the largest on top. A cause is <em>ours</em> when it is an {@code ENGINE_WAIT} (a counter ending
 * {@code .compile-waits} or {@code .sync-loads} above zero) or an {@code ALLOCATION} burst (at least
 * {@link CgHitches#ALLOCATION_BURST_BYTES} on the frame thread) of which at least that much was inside named allocation
 * groups. A hitch is ours when any cause is.</p>
 *
 * <h3>The API this fixes</h3>
 * <ul>
 *   <li>{@code CgHitchCause}: {@code GC_PAUSE, SAFEPOINT, CLASS_LOAD, ENGINE_WAIT, ALLOCATION, DEOPTIMISATION,
 *       COMPILATION}; {@code CgHitchBound}: {@code FRAME_THREAD, GPU, UNKNOWN};</li>
 *   <li>{@code CgHitch(CgFrameRecord frame, long medianNanos, CgHitchBound bound, List<Cause> causes)} with
 *       {@code Cause(CgHitchCause kind, long overlapNanos, boolean ours, String detail)}; {@code explained()},
 *       {@code ours()}, {@code top()} (null when unexplained). {@code causes} is already ranked;</li>
 *   <li>{@code CgHitches}: constants {@code WINDOW = 120}, {@code MIN_HISTORY = 20},
 *       {@code MARGIN_NANOS = 4 ms}, {@code ALLOCATION_BURST_BYTES = 8 MiB};
 *       {@code thresholdNanos(median, target)}; {@code percentile(long[] values, int permille)};
 *       {@code find(snapshot, targetNanos)}; {@code causesOf(frame, 10 * MS, pauses, jvmEvents, counters, groupedBytes)};</li>
 *   <li>{@code CgTraceSnapshot.hitches(targetNanos)}; {@code CgTraceReport.hitches()}, which {@code render} includes.</li>
 * </ul>
 *
 * <h3>The percentiles</h3>
 *
 * <p>Nearest rank, in integers: the {@code permille}/1000 percentile of {@code n} values is the one at 1-based rank
 * {@code ceil(permille x n / 1000)} of the sorted values. Floating point would put 0.999 x 1000 at 999.0000000000001
 * and read the wrong frame. {@code permille} is in 1..1000, else {@code IllegalArgumentException}; no values read
 * {@link CgFrameRecord#ABSENT}.</p>
 */
public class CgTraceHitchesTest {

    private static final CgTraceChannel CH = CgTrace.channel("t3gate");
    private static final long MS = 1_000_000L;
    private static final long MIB = 1L << 20;
    private static final long T0 = 1_000_000_000L;

    private static volatile Object sink;

    @Before
    public void on() {
        CgTrace.resetForTesting();
        CgTrace.enable("t3gate");
        CgTrace.stopJvmEvents();   // no class-load noise in the scripted frames
    }

    @After
    public void off() {
        sink = null;
        CgTrace.resetForTesting();
    }

    // ── the rule ───────────────────────────────────────────────────────────

    @Test
    public void theThresholdIsTheLargerOfTwiceTheMedianAndTheTargetPlusFourMs() {
        assertEquals(20 * MS, CgHitches.thresholdNanos(10 * MS, 10 * MS));
        assertEquals(20_700_000L, CgHitches.thresholdNanos(8 * MS, 16_700_000L));
        assertEquals(22 * MS, CgHitches.thresholdNanos(11 * MS, 16_700_000L));
        assertEquals(4 * MS, CgHitches.MARGIN_NANOS);
    }

    @Test
    public void aFrameIsAHitchOnlyStrictlyOverTheThreshold() {
        // target 10 ms and a 10 ms median: the threshold is 20 ms.
        long[] walls = steady(200, 10 * MS);
        walls[100] = 20 * MS;
        walls[101] = 20 * MS + 1;
        assertEquals(List.of(101L), indices(CgTraceSnapshot.of(frames(walls), List.of(), List.of(), List.of(), List.of())
                .hitches(10 * MS)));
    }

    @Test
    public void noFrameIsJudgedBeforeItHasTwentyFramesOfHistory() {
        long[] walls = steady(60, 10 * MS);
        walls[5] = 500 * MS;
        walls[19] = 500 * MS;
        walls[20] = 500 * MS;
        assertEquals(List.of(20L), indices(CgTraceSnapshot.of(frames(walls), List.of(), List.of(), List.of(), List.of())
                .hitches(10 * MS)));
    }

    @Test
    public void slownessThatLastsStopsBeingAHitchOnceTheWindowsMedianHasCaughtUp() {
        long[] walls = new long[400];
        for (int i = 0; i < walls.length; i++) walls[i] = i < 200 ? 10 * MS : 40 * MS;
        List<Long> found = indices(CgTraceSnapshot.of(frames(walls), List.of(), List.of(), List.of(), List.of())
                .hitches(10 * MS));
        // At frame 260 the window of 120 holds 60 fast frames and 60 slow ones: its element 60 is slow.
        List<Long> expected = new ArrayList<>();
        for (long i = 200; i <= 259; i++) expected.add(i);
        assertEquals(expected, found);
    }

    @Test
    public void theBoundFollowsTheLargestThreadAndTiesGoToTheFrameThread() {
        assertEquals(CgHitchBound.FRAME_THREAD, boundOf(30 * MS, 5 * MS));
        assertEquals(CgHitchBound.GPU, boundOf(5 * MS, 30 * MS));
        assertEquals(CgHitchBound.FRAME_THREAD, boundOf(20 * MS, 20 * MS));
        assertEquals(CgHitchBound.GPU, boundOf(CgFrameRecord.ABSENT, 30 * MS));
        assertEquals(CgHitchBound.FRAME_THREAD, boundOf(30 * MS, CgFrameRecord.ABSENT));
        assertEquals(CgHitchBound.UNKNOWN, boundOf(CgFrameRecord.ABSENT, CgFrameRecord.ABSENT));
    }

    // ── the tail ───────────────────────────────────────────────────────────

    @Test
    public void percentilesAreNearestRankAndExactOverAKnownDistribution() {
        long[] ms = new long[1000];
        for (int i = 0; i < ms.length; i++) ms[i] = (1000 - i) * MS;   // 1000..1 ms, unsorted on purpose
        assertEquals(990 * MS, CgHitches.percentile(ms, 990));
        assertEquals(999 * MS, CgHitches.percentile(ms, 999));
        assertEquals(500 * MS, CgHitches.percentile(ms, 500));
        assertEquals(1000 * MS, CgHitches.percentile(ms, 1000));
        assertEquals(1 * MS, CgHitches.percentile(ms, 1));

        long[] odd = new long[1001];
        for (int i = 0; i < odd.length; i++) odd[i] = (i + 1) * MS;
        assertEquals(991 * MS, CgHitches.percentile(odd, 990));    // ceil(990 x 1001 / 1000) = 991
        assertEquals(1000 * MS, CgHitches.percentile(odd, 999));   // ceil(999.999) = 1000

        long[] ten = new long[10];
        for (int i = 0; i < ten.length; i++) ten[i] = (i + 1) * MS;
        assertEquals("p99.9 of ten frames is the slowest", 10 * MS, CgHitches.percentile(ten, 999));

        assertEquals(CgFrameRecord.ABSENT, CgHitches.percentile(new long[0], 990));
        assertEquals(7 * MS, CgHitches.percentile(new long[] {7 * MS}, 999));
        assertTrue(ms[0] == 1000 * MS);   // not sorted in place
        for (int bad : new int[] {0, -1, 1001}) {
            try {
                CgHitches.percentile(ms, bad);
                throw new AssertionError("permille " + bad + " must be refused");
            } catch (IllegalArgumentException expected) {
                // refused by name, not clamped
            }
        }
    }

    // ── the causes ─────────────────────────────────────────────────────────

    @Test
    public void everyCauseTogetherRankByOverlapThenByKind() {
        CgFrameRecord frame = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 20 * MIB);
        List<CgHitch.Cause> causes = CgHitches.causesOf(frame, 10 * MS,
                List.of(pause(10 * MS, 12 * MS)),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 30 * MS, 3 * MS),
                        jvm(CgJvmEventKind.DEOPTIMIZATION, 35 * MS, 0L),
                        jvm(CgJvmEventKind.COMPILATION, 5 * MS, 6 * MS)),
                List.of(counter("material.compile-waits", 7, 1), counter("mesh.upload-bytes", 7, 5)),
                0L);

        assertEquals(List.of(CgHitchCause.GC_PAUSE, CgHitchCause.CLASS_LOAD, CgHitchCause.ENGINE_WAIT,
                CgHitchCause.ALLOCATION, CgHitchCause.DEOPTIMISATION, CgHitchCause.COMPILATION), kinds(causes));
        assertEquals(12 * MS, causes.get(0).overlapNanos());
        assertEquals(3 * MS, causes.get(1).overlapNanos());
        assertEquals(6 * MS, causes.get(5).overlapNanos());
        assertTrue(causes.get(0).detail().contains("System.gc()"));
        assertTrue(causes.get(2).detail().contains("material.compile-waits"));
        assertFalse("upload bytes are not a wait", causes.get(2).detail().contains("mesh.upload-bytes"));
        assertTrue(causes.get(2).ours());
        assertFalse("a GC pause is never ours", causes.get(0).ours());
        assertFalse("20 MiB with no group of ours is the host's", causes.get(3).ours());
    }

    @Test
    public void aLongerClassLoadOutranksAShorterPauseAndATieGoesToTheKindOrder() {
        CgFrameRecord frame = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 0L);
        List<CgHitch.Cause> longer = CgHitches.causesOf(frame, 10 * MS,
                List.of(pause(10 * MS, 4 * MS)),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 20 * MS, 15 * MS)), List.of(), 0L);
        assertEquals(List.of(CgHitchCause.CLASS_LOAD, CgHitchCause.GC_PAUSE), kinds(longer));

        List<CgHitch.Cause> tie = CgHitches.causesOf(frame, 10 * MS,
                List.of(pause(10 * MS, 5 * MS)),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 20 * MS, 5 * MS), jvm(CgJvmEventKind.SAFEPOINT, 30 * MS, 5 * MS)),
                List.of(), 0L);
        assertEquals(List.of(CgHitchCause.GC_PAUSE, CgHitchCause.SAFEPOINT, CgHitchCause.CLASS_LOAD), kinds(tie));
    }

    @Test
    public void aSmallClassLoadDoesNotOutrankABurstInABigHitch() {
        // 30 ms over a 10 ms median: the floor is 10% of 20 ms = 2 ms. A 0.2 ms class load is under it.
        CgFrameRecord frame = frame(7, T0, 30 * MS, 28 * MS, CgFrameRecord.ABSENT, 96 * MIB);
        List<CgHitch.Cause> causes = CgHitches.causesOf(frame, 10 * MS, List.of(),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 5 * MS, 200_000L)), List.of(), 96 * MIB);
        assertEquals(List.of(CgHitchCause.ALLOCATION, CgHitchCause.CLASS_LOAD), kinds(causes));
        assertTrue(new CgHitch(frame, 10 * MS, CgHitchBound.FRAME_THREAD, causes).explained());
    }

    @Test
    public void theFloorIsInclusiveAndRelativeToTheFramesExcess() {
        CgFrameRecord frame = frame(7, T0, 30 * MS, 28 * MS, CgFrameRecord.ABSENT, 96 * MIB);
        List<CgHitch.Cause> at = CgHitches.causesOf(frame, 10 * MS, List.of(),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 5 * MS, 2 * MS)), List.of(), 96 * MIB);
        assertEquals("exactly the floor passes", List.of(CgHitchCause.CLASS_LOAD, CgHitchCause.ALLOCATION), kinds(at));
        List<CgHitch.Cause> under = CgHitches.causesOf(frame, 10 * MS, List.of(),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 5 * MS, 2 * MS - 1)), List.of(), 96 * MIB);
        assertEquals(List.of(CgHitchCause.ALLOCATION, CgHitchCause.CLASS_LOAD), kinds(under));

        // At 300 fps: 6 ms over a 3.3 ms median, a floor of 0.27 ms.
        CgFrameRecord fast = frame(7, T0, 6 * MS, 5 * MS, CgFrameRecord.ABSENT, 96 * MIB);
        assertEquals(CgHitchCause.CLASS_LOAD, CgHitches.causesOf(fast, 3_300_000L, List.of(),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, MS, 300_000L)), List.of(), 96 * MIB).get(0).kind());
        assertEquals(CgHitchCause.ALLOCATION, CgHitches.causesOf(fast, 3_300_000L, List.of(),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, MS, 200_000L)), List.of(), 96 * MIB).get(0).kind());
    }

    @Test
    public void evidenceUnderTheFloorAloneStillExplainsWithTheLargestOnTop() {
        CgFrameRecord frame = frame(7, T0, 30 * MS, 28 * MS, CgFrameRecord.ABSENT, 0L);
        List<CgHitch.Cause> causes = CgHitches.causesOf(frame, 10 * MS, List.of(pause(1 * MS, 100_000L)),
                List.of(jvm(CgJvmEventKind.CLASS_LOAD, 5 * MS, 200_000L)), List.of(), 0L);
        assertEquals(List.of(CgHitchCause.CLASS_LOAD, CgHitchCause.GC_PAUSE), kinds(causes));
        CgHitch hitch = new CgHitch(frame, 10 * MS, CgHitchBound.FRAME_THREAD, causes);
        assertTrue(hitch.explained());
        assertEquals(CgHitchCause.CLASS_LOAD, hitch.top().kind());
    }

    @Test
    public void aPauseCountsOnlyThePartInsideTheFrameAndOneOutsideCountsForNothing() {
        CgFrameRecord frame = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 0L);
        List<CgHitch.Cause> clipped = CgHitches.causesOf(frame, 10 * MS, List.of(pause(-4 * MS, 10 * MS)), List.of(), List.of(), 0L);
        assertEquals(6 * MS, clipped.get(0).overlapNanos());

        List<CgHitch.Cause> after = CgHitches.causesOf(frame, 10 * MS,
                List.of(pause(40 * MS, 10 * MS), pause(-10 * MS, 10 * MS)), List.of(), List.of(), 0L);
        assertTrue("a pause that ends where the frame begins, or begins where it ends, is not in it", after.isEmpty());
    }

    @Test
    public void anAllocationBurstIsACauseFromEightMiBAndOursOnlyForTheBytesInsideNamedGroups() {
        CgFrameRecord small = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 8 * MIB - 1);
        assertTrue(CgHitches.causesOf(small, 10 * MS, List.of(), List.of(), List.of(), 0L).isEmpty());

        CgFrameRecord absent = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, CgFrameRecord.ABSENT);
        assertTrue("an uncounted frame is not a burst", CgHitches.causesOf(absent, 10 * MS, List.of(), List.of(), List.of(), 0L).isEmpty());

        CgFrameRecord burst = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 64 * MIB);
        assertTrue(CgHitches.causesOf(burst, 10 * MS, List.of(), List.of(), List.of(), 8 * MIB).get(0).ours());
        assertFalse(CgHitches.causesOf(burst, 10 * MS, List.of(), List.of(), List.of(), 8 * MIB - 1).get(0).ours());
    }

    @Test
    public void compilationsAloneDoNotExplainAHitchAndNoEvidenceAtAllIsUnexplained() {
        CgFrameRecord frame = frame(7, T0, 40 * MS, 38 * MS, CgFrameRecord.ABSENT, 0L);
        List<CgHitch.Cause> onlyJit = CgHitches.causesOf(frame, 10 * MS, List.of(), List.of(jvm(CgJvmEventKind.COMPILATION, 5 * MS, 6 * MS)),
                List.of(), 0L);
        CgHitch listed = new CgHitch(frame, 10 * MS, CgHitchBound.FRAME_THREAD, onlyJit);
        assertEquals(List.of(CgHitchCause.COMPILATION), kinds(listed.causes()));
        assertFalse(listed.explained());
        assertEquals(null, listed.top());

        CgHitch nothing = new CgHitch(frame, 10 * MS, CgHitchBound.FRAME_THREAD,
                CgHitches.causesOf(frame, 10 * MS, List.of(), List.of(), List.of(), 0L));
        assertTrue(nothing.causes().isEmpty());
        assertFalse(nothing.explained());
        assertFalse(nothing.ours());
        assertEquals(null, nothing.top());
    }

    // ── the report ─────────────────────────────────────────────────────────

    @Test
    public void theReportListsEachHitchAndSaysUnexplainedWhenTheTraceSawNothing() {
        long[] walls = steady(200, 10 * MS);
        walls[150] = 30 * MS;
        CgTraceSnapshot snap = CgTraceSnapshot.of(frames(walls), List.of(), List.of(), List.of(), List.of());
        String text = CgTraceReport.of(snap).budget(10d).hitches();

        assertTrue(text, text.contains("HITCHES  1 "));
        assertTrue(text, text.contains("HITCH #150  30.0 ms wall (median 10.0)   bound: unknown"));
        assertTrue(text, text.contains("=> unexplained"));
        assertFalse("nothing is guessed", text.contains("=> ours") || text.contains("=> not ours"));
        assertTrue("the render includes it", CgTraceReport.of(snap).budget(10d).render(CgTraceReport.Tier.BREAKDOWN).contains("HITCH #150"));
    }

    @Test
    public void theReportHeaderGivesTheTailAndASteadyRunSaysNone() {
        long[] walls = new long[1000];
        for (int i = 0; i < walls.length; i++) walls[i] = (i + 1) * MS;
        String tail = CgTraceReport.of(CgTraceSnapshot.of(frames(walls), List.of(), List.of(), List.of(), List.of()))
                .budget(10d).hitches();
        assertTrue(tail, tail.contains("p99 990.0 ms"));
        assertTrue(tail, tail.contains("p99.9 999.0 ms"));

        String steady = CgTraceReport.of(CgTraceSnapshot.of(frames(steady(100, 10 * MS)), List.of(), List.of(), List.of(), List.of()))
                .budget(10d).hitches();
        assertTrue(steady, steady.contains("HITCHES  none"));
    }

    // ── in a real run ──────────────────────────────────────────────────────

    @Test
    public void aScriptedAllocationBurstIsNamedAsTheCauseOfItsHitchAndIsOurs() throws InterruptedException {
        long frame = runWithOneHitch(() -> {
            long mark = CgTrace.allocationBegin(CH);
            byte[][] held = new byte[96][];
            for (int i = 0; i < held.length; i++) {
                held[i] = new byte[(int) MIB];
                java.util.Arrays.fill(held[i], (byte) i);
            }
            sink = held;
            CgTrace.allocationEnd(CH, "t3.burst", mark);
        }, false);

        CgTraceSnapshot snap = CgTrace.snapshot();
        CgHitch hitch = hitchOf(snap, frame);
        CgHitch.Cause burst = causeOf(hitch, CgHitchCause.ALLOCATION);
        assertTrue("96 MiB inside a named group is ours: " + hitch, burst.ours());
        assertTrue(hitch.ours());
        // The burst may itself have cost a young collection. That leads only when it is over the floor (a tenth of the
        // frame's excess); a short one is context behind the burst.
        long floorNanos = (hitch.frame().wallNanos() - hitch.medianNanos()) / 10;
        long pauseNanos = 0L;
        for (CgHitch.Cause each : hitch.causes()) if (each.kind() == CgHitchCause.GC_PAUSE) pauseNanos = each.overlapNanos();
        CgHitchCause expectedTop = pauseNanos * 10 >= hitch.frame().wallNanos() - hitch.medianNanos() && pauseNanos > 0
                ? CgHitchCause.GC_PAUSE : CgHitchCause.ALLOCATION;
        assertEquals(hitch.toString() + " floor " + floorNanos, expectedTop, hitch.top().kind());
        assertTrue(CgTraceReport.of(snap).budget(4d).hitches().contains("=> ours"));
    }

    @Test
    public void aForcedFullCollectionIsNamedAsTheCauseOfItsHitchWithTheJvmsOwnCause() throws InterruptedException {
        Object[] live = new Object[4_000_000];   // a heap worth collecting: marking cost is per object, not per byte
        for (int i = 0; i < live.length; i++) live[i] = new Object();
        sink = live;

        long frame = runWithOneHitch(System::gc, true);

        CgTraceSnapshot snap = CgTrace.snapshot();
        CgHitch hitch = hitchOf(snap, frame);
        assertEquals(hitch.toString(), CgHitchCause.GC_PAUSE, hitch.top().kind());
        assertTrue(hitch.top().detail(), hitch.top().detail().contains("System.gc()"));
        assertTrue("the pause is over the floor", hitch.top().overlapNanos() * 10 >= hitch.frame().wallNanos() - hitch.medianNanos());
        assertFalse("a collection is not ours", hitch.top().ours());
        assertEquals(CgHitchBound.FRAME_THREAD, hitch.bound());
        String text = CgTraceReport.of(snap).budget(4d).hitches();
        assertTrue(text, text.contains("HITCH #" + frame));
        assertTrue(text, text.contains("System.gc()"));
    }

    @Test
    public void aRunWithJvmEventsOffSaysSoWhenItCannotExplainAHitch() throws InterruptedException {
        long frame = runWithOneHitch(() -> spin(30 * MS), false);
        String text = CgTraceReport.of(CgTrace.snapshot()).budget(4d).hitches();
        assertNotNull(hitchOf(CgTrace.snapshot(), frame));
        assertTrue(text, text.contains("=> unexplained (JVM events were not recorded)"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Forty steady frames of about 2 ms, one frame running {@code action}, then five more and the commit. Returns the
     * scripted frame's index; with {@code waitForGc} the run waits for the collection's notification before it commits.
     */
    private static long runWithOneHitch(Runnable action, boolean waitForGc) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            CgTrace.frameBegin();
            spin(2 * MS);
            CgTrace.frameEnd();
        }
        CgTrace.frameBegin();
        long frame = CgTrace.currentFrameIndex();
        action.run();
        CgTrace.frameEnd();
        for (int i = 0; i < 5; i++) {
            CgTrace.frameBegin();
            spin(2 * MS);
            CgTrace.frameEnd();
        }
        if (waitForGc) {
            // The notification arrives on a JMX thread after the pause and lands on its frame by time, so the wait
            // happens after the frames rather than inside the hitch.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline && CgTrace.pendingGcNotifications() == 0) Thread.sleep(5);
            Thread.sleep(50);
        }
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        return frame;
    }

    private static CgHitch hitchOf(CgTraceSnapshot snap, long frame) {
        for (CgHitch each : snap.hitches(4 * MS)) if (each.frame().index() == frame) return each;
        throw new AssertionError("frame " + frame + " is not a hitch; found " + indices(snap.hitches(4 * MS)));
    }

    private static CgHitch.Cause causeOf(CgHitch hitch, CgHitchCause kind) {
        for (CgHitch.Cause each : hitch.causes()) if (each.kind() == kind) return each;
        throw new AssertionError(kind + " is not among " + hitch);
    }

    private static void spin(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) Thread.onSpinWait();
    }

    private static long[] steady(int count, long wall) {
        long[] walls = new long[count];
        java.util.Arrays.fill(walls, wall);
        return walls;
    }

    private static List<CgFrameRecord> frames(long[] walls) {
        List<CgFrameRecord> out = new ArrayList<>();
        long at = T0;
        for (int i = 0; i < walls.length; i++) {
            out.add(frame(i, at, walls[i], CgFrameRecord.ABSENT, CgFrameRecord.ABSENT, CgFrameRecord.ABSENT));
            at += walls[i];
        }
        return out;
    }

    private static CgFrameRecord frame(long index, long begin, long wall, long cpu, long gpu, long allocated) {
        return new CgFrameRecord(index, begin, begin + wall, cpu, gpu, 0L, 0, 0, 0L, allocated, CgFrameRecord.ABSENT);
    }

    private static CgHitchBound boundOf(long cpu, long gpu) {
        List<CgFrameRecord> frames = new ArrayList<>(frames(steady(30, 10 * MS)));
        long at = T0 + 30 * 10 * MS;
        frames.add(frame(30, at, 40 * MS, cpu, gpu, CgFrameRecord.ABSENT));
        List<CgHitch> found = CgTraceSnapshot.of(frames, List.of(), List.of(), List.of(), List.of()).hitches(10 * MS);
        assertEquals(1, found.size());
        return found.get(0).bound();
    }

    private static List<Long> indices(List<CgHitch> hitches) {
        List<Long> out = new ArrayList<>();
        for (CgHitch each : hitches) out.add(each.frame().index());
        return out;
    }

    private static List<CgHitchCause> kinds(List<CgHitch.Cause> causes) {
        List<CgHitchCause> out = new ArrayList<>();
        for (CgHitch.Cause each : causes) out.add(each.kind());
        return out;
    }

    private static CgTraceSnapshot.GcPauseView pause(long startOffset, long duration) {
        return new CgTraceSnapshot.GcPauseView("G1 Old Generation", "end of major GC", "System.gc()", T0 + startOffset, duration);
    }

    private static CgTraceSnapshot.JvmEventView jvm(CgJvmEventKind kind, long startOffset, long duration) {
        return new CgTraceSnapshot.JvmEventView(kind, "Subject", "detail", T0 + startOffset, duration);
    }

    private static CgTraceSnapshot.CounterView counter(String name, long frame, long value) {
        return new CgTraceSnapshot.CounterView(name, frame, value);
    }
}
