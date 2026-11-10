package com.crystalgraphics.trace;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where a timer query's answer lands, on a device whose timers the test answers. */
public class CgGpuTraceTest {

    private static final long MS = 1_000_000L;

    private Timers device;

    @Before
    public void setUp() {
        device = new Timers();
        CgRHI.install(device.rhi);
        CgTrace.resetForTesting();
        CgGpuTrace.dispose();
        CgGpuTrace.assumeSupport(CgGpuTrace.Support.SUPPORTED);
        CgTrace.enable(CgGpuTrace.GPU.name());
    }

    @After
    public void tearDown() {
        CgGpuTrace.dispose();
        CgTrace.resetForTesting();
        CgRHI.install(null);
    }

    @Test
    public void aFrameGetsItsGpuTimeWhenItsQueryLandsAndNotBefore() {
        CgTrace.frameBegin();
        zone("ui");
        CgTrace.frameBegin();
        assertFalse("absent until the GPU answers", frame(0).hasGpu());

        device.ready = true;
        device.nanos = 2 * MS;
        CgTrace.frameBegin();
        assertEquals(2 * MS, frame(0).gpuNanos());
        assertEquals(Long.valueOf(2 * MS), counters(0).get("gpu:ui"));
    }

    @Test
    public void anOpenFrameWaitsForItsBoundaryEvenWhenItsQueriesAreBack() {
        device.ready = true;
        device.nanos = MS;
        CgTrace.frameBegin();
        zone("ui");
        CgGpuTrace.collect();
        CgTrace.frameBegin();
        zone("ui");
        CgGpuTrace.collect();
        // Frame 0 closed with one zone; frame 1 is still open and may issue more.
        assertEquals(MS, frame(0).gpuNanos());
        CgTrace.frameBegin();
        assertEquals(MS, frame(1).gpuNanos());
    }

    @Test
    public void aFrameWithNoGpuZoneHasNoGpuFigure() {
        device.ready = true;
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void aNestedZonePausesItsParentAndTheTotalCountsEachPieceOnce() {
        device.ready = true;
        device.nanos = MS;
        CgTrace.frameBegin();
        CgGpuTrace.begin("outer");
        CgGpuTrace.begin("inner");
        CgGpuTrace.end();
        CgGpuTrace.end();
        assertEquals(3, device.count("beginTimer"));
        assertEquals(3, device.count("endTimer"));
        CgTrace.frameBegin();

        assertEquals(3 * MS, frame(0).gpuNanos());
        Map<String, Long> counters = counters(0);
        assertEquals(Long.valueOf(2 * MS), counters.get("gpu:outer"));
        assertEquals(Long.valueOf(MS), counters.get("gpu:inner"));
        assertEquals(Long.valueOf(1), counters.get("gpu.nestedFlattened"));
    }

    @Test
    public void withoutTimerQueriesNothingIsIssuedAndNothingIsZero() {
        CgGpuTrace.assumeSupport(CgGpuTrace.Support.UNSUPPORTED);
        assertFalse(CgGpuTrace.isMeasuring());
        CgTrace.frameBegin();
        zone("ui");
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        assertFalse(device.count("beginTimer") > 0);
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void aZoneLeftOpenIsClosedAtTheBoundary() {
        CgTrace.frameBegin();
        CgGpuTrace.begin("ui");
        CgTrace.frameBegin();
        assertEquals(1, device.count("endTimer"));
        assertEquals(Long.valueOf(1), counters(0).get("gpu.leakedAcrossFrame"));
    }

    @Test
    public void aQueryIssuedBeforeAClearNeverLandsOnTheFramesAfterIt() {
        device.nanos = 5 * MS;
        CgTrace.frameBegin();
        zone("ui");
        CgTrace.clear();
        device.ready = true;
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void totalsKeepEveryResultByName() {
        device.ready = true;
        device.nanos = MS;
        zone("draw");
        zone("draw");
        CgGpuTrace.collect();
        long[] draw = CgGpuTrace.totals().get("draw");
        assertEquals(2 * MS, draw[0]);
        assertEquals(2L, draw[1]);
        assertTrue(CgGpuTrace.totals().size() == 1);
    }

    @Test
    public void marksChargeEachRunToItsLabelAndLeaveTheFrameTotalToTheZone() {
        device.ready = true;
        device.nanos = 10 * MS;
        int opaque = CgGpuTrace.label("opaque"), glass = CgGpuTrace.label("glass");
        CgTrace.frameBegin();
        CgGpuTrace.begin("world");
        CgGpuTrace.mark(opaque);
        device.clock = 3 * MS;
        CgGpuTrace.mark(opaque);       // the same run
        device.clock = 4 * MS;
        CgGpuTrace.mark(glass);
        device.clock = 10 * MS;
        CgGpuTrace.end();
        CgTrace.frameBegin();

        assertEquals(3, device.count("timestamp"));
        assertEquals(10 * MS, frame(0).gpuNanos());
        Map<String, Long> counters = counters(0);
        assertEquals(Long.valueOf(4 * MS), counters.get("gpu:world/opaque"));
        assertEquals(Long.valueOf(6 * MS), counters.get("gpu:world/glass"));
    }

    @Test
    public void markEndLeavesWhatFollowsUncharged() {
        device.ready = true;
        int a = CgGpuTrace.label("a");
        CgTrace.frameBegin();
        CgGpuTrace.begin("pass");
        CgGpuTrace.mark(a);
        device.clock = 2 * MS;
        CgGpuTrace.markEnd();
        device.clock = 9 * MS;
        CgGpuTrace.end();
        CgTrace.frameBegin();

        assertEquals(2, device.count("timestamp"));
        assertEquals(Long.valueOf(2 * MS), counters(0).get("gpu:pass/a"));
    }

    @Test
    public void aMarkOutsideAMeasuredZoneIssuesNothing() {
        CgGpuTrace.mark(CgGpuTrace.label("a"));
        CgTrace.disable(CgGpuTrace.GPU.name());
        CgGpuTrace.begin("pass");
        CgGpuTrace.mark(CgGpuTrace.label("a"));
        CgGpuTrace.end();
        assertFalse(device.count("timestamp") > 0);
    }

    @Test
    public void aBudgetZoneIsTimedWithTheChannelOff_andAFrameCountsOnceItsSuccessorAnswers() {
        CgTrace.resetForTesting();
        int slot = CgGpuTrace.budgetSlot();
        device.ready = true;
        device.nanos = MS;
        long first = CgGpuTrace.budgetFrame();
        budgetZone(slot);
        budgetZone(slot);
        zone("ui");
        CgGpuTrace.collect();
        assertEquals(2, device.count("beginTimer"));
        assertEquals("its frame may still issue more", 0L, CgGpuTrace.budgetNanos(slot));
        assertTrue("nothing reaches the trace with the channel off", CgGpuTrace.totals().isEmpty());

        CgGpuTrace.nextBudgetFrame();
        CgGpuTrace.nextBudgetFrame();   // a frame with no work of the slot: zero
        budgetZone(slot);
        CgGpuTrace.collect();
        assertEquals(2 * MS, CgGpuTrace.budgetNanos(slot));
        assertEquals("the first frame and the empty one", first + 2, CgGpuTrace.budgetThrough(slot));
    }

    @Test
    public void aZoneInsideABudgetZoneIsChargedToItsBudget_andThePausedZoneResumes() {
        int slot = CgGpuTrace.budgetSlot();
        device.ready = true;
        device.nanos = MS;
        CgTrace.frameBegin();
        CgGpuTrace.begin(CgGpuTrace.name("sparks"), slot);
        CgGpuTrace.begin("sparks.step");
        CgGpuTrace.end();
        CgGpuTrace.end();
        zone("ui");
        CgGpuTrace.nextBudgetFrame();
        budgetZone(slot);
        CgGpuTrace.collect();
        assertEquals("sparks, then sparks.step, then sparks resumed", 3 * MS, CgGpuTrace.budgetNanos(slot));
        assertEquals(5, device.count("beginTimer"));
    }

    @Test
    public void theNearestTimedZoneResumes_pastAnUntimedOneBetween() {
        CgTrace.resetForTesting();
        int slot = CgGpuTrace.budgetSlot();
        CgGpuTrace.begin(CgGpuTrace.name("sparks"), slot);
        CgGpuTrace.begin("untimed with the channel off");
        CgGpuTrace.begin(CgGpuTrace.name("inner"), slot);
        CgGpuTrace.end();
        CgGpuTrace.end();
        CgGpuTrace.end();
        assertEquals("sparks, inner, sparks resumed", 3, device.count("beginTimer"));
        assertEquals(3, device.count("endTimer"));
    }

    private static void budgetZone(int slot) {
        CgGpuTrace.begin(CgGpuTrace.name("sparks"), slot);
        CgGpuTrace.end();
    }

    private static void zone(String name) {
        CgGpuTrace.begin(name);
        CgGpuTrace.end();
    }

    private static CgFrameRecord frame(long index) {
        List<CgFrameRecord> frames = CgTrace.frames();
        for (CgFrameRecord frame : frames) {
            if (frame.index() == index) return frame;
        }
        throw new AssertionError("no frame " + index + " in " + frames);
    }

    private static Map<String, Long> counters(long index) {
        Map<String, Long> out = new HashMap<>();
        for (CgTraceSnapshot.CounterView counter : CgTrace.countersIn(frame(index))) {
            out.merge(counter.name(), counter.value(), Long::sum);
        }
        return out;
    }

    /**
     * A device of timers alone: an elapsed timer answers {@link #nanos}, a timestamp the {@link #clock} it was written at,
     * each once {@link #ready}; every command-list call counted by name.
     */
    private static final class Timers {
        boolean ready;
        long nanos, clock;
        private final Map<String, Integer> calls = new HashMap<>();
        final CgCommandList list = (CgCommandList) Proxy.newProxyInstance(CgCommandList.class.getClassLoader(),
                new Class<?>[] {CgCommandList.class}, (proxy, method, args) -> {
                    calls.merge(method.getName(), 1, Integer::sum);
                    if (method.getName().equals("timestamp")) ((Timer) args[0]).stamped = clock;
                    return null;
                });
        final CgRHI rhi = (CgRHI) Proxy.newProxyInstance(CgRHI.class.getClassLoader(), new Class<?>[] {CgRHI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createTimerQuery" -> new Timer(this);
                    case "encoder" -> list;
                    default -> null;
                });

        int count(String call) {
            return calls.getOrDefault(call, 0);
        }
    }

    private static final class Timer implements CgTimerQuery {
        private final Timers device;
        /** The clock it was written at as a timestamp; -1 for an elapsed timer. */
        long stamped = -1;

        Timer(Timers device) {
            this.device = device;
        }

        @Override public long resultNanos() { return !device.ready ? -1 : stamped >= 0 ? stamped : device.nanos; }

        @Override public String label() { return "test timer"; }
    }

}
