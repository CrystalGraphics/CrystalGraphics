package com.crystalgraphics.trace;

import com.crystalgraphics.platform.gl.RecordingGlBackend;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where a timer query's answer lands, on a backend whose queries the test answers. */
public class CgGpuTraceTest {

    private static final long MS = 1_000_000L;

    private RecordingGlBackend gl;

    @Before
    public void setUp() {
        gl = RecordingGlBackend.install();
        CgTrace.resetForTesting();
        CgGpuTrace.dispose();
        CgGpuTrace.assumeSupport(CgGpuTrace.Support.SUPPORTED);
        CgTrace.enable(CgGpuTrace.GPU.name());
    }

    @After
    public void tearDown() {
        CgGpuTrace.dispose();
        CgTrace.resetForTesting();
    }

    @Test
    public void aFrameGetsItsGpuTimeWhenItsQueryLandsAndNotBefore() {
        CgTrace.frameBegin();
        zone("ui");
        CgTrace.frameBegin();
        assertFalse("absent until the GPU answers", frame(0).hasGpu());

        gl.queriesReady = true;
        gl.queryNanos = 2 * MS;
        CgTrace.frameBegin();
        assertEquals(2 * MS, frame(0).gpuNanos());
        assertEquals(Long.valueOf(2 * MS), counters(0).get("gpu:ui"));
    }

    @Test
    public void anOpenFrameWaitsForItsBoundaryEvenWhenItsQueriesAreBack() {
        gl.queriesReady = true;
        gl.queryNanos = MS;
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
        gl.queriesReady = true;
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void aNestedZonePausesItsParentAndTheTotalCountsEachPieceOnce() {
        gl.queriesReady = true;
        gl.queryNanos = MS;
        CgTrace.frameBegin();
        CgGpuTrace.begin("outer");
        CgGpuTrace.begin("inner");
        CgGpuTrace.end();
        CgGpuTrace.end();
        assertEquals(3, gl.countOf("glBeginTimeElapsedQuery"));
        assertEquals(3, gl.countOf("glEndTimeElapsedQuery"));
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
        assertFalse(gl.sawCall("glBeginTimeElapsedQuery"));
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void aZoneLeftOpenIsClosedAtTheBoundary() {
        CgTrace.frameBegin();
        CgGpuTrace.begin("ui");
        CgTrace.frameBegin();
        assertEquals(1, gl.countOf("glEndTimeElapsedQuery"));
        assertEquals(Long.valueOf(1), counters(0).get("gpu.leakedAcrossFrame"));
    }

    @Test
    public void aQueryIssuedBeforeAClearNeverLandsOnTheFramesAfterIt() {
        gl.queryNanos = 5 * MS;
        CgTrace.frameBegin();
        zone("ui");
        CgTrace.clear();
        gl.queriesReady = true;
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        CgTrace.frameBegin();
        assertFalse(frame(0).hasGpu());
    }

    @Test
    public void totalsKeepEveryResultByName() {
        gl.queriesReady = true;
        gl.queryNanos = MS;
        zone("draw");
        zone("draw");
        CgGpuTrace.collect();
        long[] draw = CgGpuTrace.totals().get("draw");
        assertEquals(2 * MS, draw[0]);
        assertEquals(2L, draw[1]);
        assertTrue(CgGpuTrace.totals().size() == 1);
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
}
