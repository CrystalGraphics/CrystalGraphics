package com.crystalgraphics.loading.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

public class CgLoadPlanTest {

    @Before
    public void reset() {
        CgLoadTracker.resetForTesting(null);
    }

    @Test
    public void theBarIsTimeDoneOverTimeExpectedAndSlowsWhenWorkRunsLong() {
        CgLoadTracker.resetForTesting(Map.of("kit:scan", 1000L, "kit:load", 9000L));
        long t0 = System.nanoTime(), ms = 1_000_000L;
        CgLoadTask scan = CgLoadTracker.add("kit:scan", List.of());
        scan.started(t0);
        scan.ended(true, t0 + 1000 * ms);
        // a tenth of last load's time done: the kit's load has not started, but the bar knows it is coming
        assertEquals(0.1f, CgLoadTracker.progress(t0 + 1000 * ms), 1e-4f);

        CgLoadTask load = CgLoadTracker.add("kit:load", List.of());
        load.started(t0 + 1000 * ms);
        load.progress(0.5f);
        // half done in 4 s: its own 8 s weighed half against last time's 9, a whole of 8.5
        assertEquals(5000f / 9500f, CgLoadTracker.progress(t0 + 5000 * ms), 1e-4f);
        // still at half after 11 s: its own 22 s weighed half, 15.5, so the bar slows rather than pegging
        assertEquals(12000f / 16500f, CgLoadTracker.progress(t0 + 12000 * ms), 1e-4f);

        load.ended(true, t0 + 13000 * ms);
        assertEquals(1f, CgLoadTracker.progress(t0 + 13000 * ms), 1e-4f);
    }

    @Test
    public void workPastItsLastTimeWithNoProgressHoldsTheBar() {
        CgLoadTracker.resetForTesting(Map.of("kit:scan", 1000L));
        long t0 = System.nanoTime(), ms = 1_000_000L;
        CgLoadTask scan = CgLoadTracker.add("kit:scan", List.of());
        scan.started(t0);
        assertEquals(0.5f, CgLoadTracker.progress(t0 + 500 * ms), 1e-4f);
        // three times its last second: taken as half done, not nine tenths
        assertEquals(0.6f, CgLoadTracker.progress(t0 + 3000 * ms), 1e-4f);
    }

    @Test
    public void aTaskStartsAfterTheOnesItNamesAndItsRenderPartRunsInSlices() throws Exception {
        int[] slices = {0};
        CgLoadTask second = CgLoadPlan.get().task("mod:second").after("mod:first")
                .render(task -> ++slices[0] == 3).start();
        CgLoadTask first = CgLoadPlan.get().task("mod:first").render(task -> true).start();
        CgLoadPlan.get().frame();
        assertEquals(CgLoadTask.State.DONE, first.state());
        assertEquals("starts the frame after its first ended", CgLoadTask.State.WAITING, second.state());
        for (int i = 0; i < 4 && !second.done(); i++) CgLoadPlan.get().frame();
        assertEquals(CgLoadTask.State.DONE, second.state());
        assertEquals(3, slices[0]);
        assertTrue(second.startedAt() >= first.endedAt());
    }

    @Test
    public void aFailureFailsWhatComesAfterIt() {
        CgLoadTask broken = CgLoadPlan.get().task("mod:broken").render(task -> {
            throw new IllegalStateException("no atlas");
        }).start();
        CgLoadTask after = CgLoadPlan.get().task("mod:after").after("mod:broken").render(task -> true).start();
        CgLoadPlan.get().frame();
        CgLoadPlan.get().frame();
        assertEquals(CgLoadTask.State.FAILED, broken.state());
        assertEquals(CgLoadTask.State.FAILED, after.state());
    }
}
