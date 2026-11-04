package com.crystalgraphics.trace;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** GPU zones on a command list: timed by the RHI's queries, nested freely, landing once their frame retires. */
public class CgGpuTraceListZonesTest {

    private final CgNullRHI rhi = new CgNullRHI(8, 8).withoutLog();

    @Before
    public void setUp() {
        CgRHI.install(rhi);
        CgTrace.resetForTesting();
        CgGpuTrace.dispose();
        CgTrace.enable(CgGpuTrace.GPU.name());
    }

    @After
    public void tearDown() {
        CgGpuTrace.dispose();
        CgTrace.resetForTesting();
        CgRHI.install(null);
    }

    @Test
    public void nestedZonesLandWhenTheirFrameRetiresAndNotBefore() {
        CgCommandList cmd = rhi.encoder();
        int shadows = CgGpuTrace.name("shadows"), cascade = CgGpuTrace.name("cascade");
        CgTrace.frameBegin();
        CgGpuTrace.begin(cmd, shadows);
        CgGpuTrace.begin(cmd, cascade);                  // inside: its own query, the outer one not paused
        CgGpuTrace.end(cmd);
        CgGpuTrace.end(cmd);
        rhi.endFrame();
        CgTrace.frameBegin();
        assertFalse("absent while its frame is in flight", frame(0).hasGpu());

        rhi.endFrame();
        rhi.endFrame();
        CgTrace.frameBegin();
        assertTrue(frame(0).hasGpu());
        Map<String, Long> counters = counters(0);
        assertTrue(counters.toString(), counters.containsKey("gpu:shadows") && counters.containsKey("gpu:cascade"));
    }

    @Test
    public void aZoneWithTheChannelOffRecordsNoQuery() {
        CgTrace.resetForTesting();
        int live = rhi.liveObjects();
        CgCommandList cmd = rhi.encoder();
        CgGpuTrace.begin(cmd, CgGpuTrace.name("off"));
        CgGpuTrace.end(cmd);
        assertEquals(live, rhi.liveObjects());
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
