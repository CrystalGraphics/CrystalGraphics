package com.crystalgraphics.render;

import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import org.junit.Test;

import java.util.ArrayDeque;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The budget's controller against a GPU whose cost follows the scale, its answers landing a few frames late. */
public class CgGpuBudgetTest {

    private static final int LAG = 3;

    @Test
    public void overBudget_dropsToWhatFits_andHoldsThere() {
        Gpu gpu = new Gpu(1f, 4f, 0f);
        gpu.run(400, 2f);
        assertEquals(0.5f, gpu.scale, 0.06f);
        assertEquals("held once settled", 0, gpu.run(200, 2f));
        assertTrue(gpu.controller.median() <= 2f * CgGpuBudget.Controller.OVER);
    }

    @Test
    public void underBudget_risesBackToOne_andNeverPastIt() {
        Gpu gpu = new Gpu(0.25f, 1f, 0f);
        gpu.run(600, 2f);
        assertEquals(1f, gpu.scale, 0f);
    }

    @Test
    public void resultsLandingTwoAtATime_doNotReadAsOverBudget() {
        Gpu gpu = new Gpu(1f, 1.9f, 0f);
        gpu.uneven = true;
        assertEquals("no change at 95% of the budget", 0, gpu.run(300, 2f));
        assertEquals(1f, gpu.scale, 0f);
    }

    @Test
    public void aFrameTheDriverStretched_movesNothing() {
        Gpu gpu = new Gpu(1f, 1.8f, 0f);
        gpu.stretchEvery = 7;
        assertEquals(0, gpu.run(300, 2f));
    }

    @Test
    public void aCostThatNeverFits_stopsAtTheFloor() {
        Gpu gpu = new Gpu(1f, 40f, 0f);
        gpu.run(400, 2f);
        assertEquals(0.1f, gpu.scale, 0f);
    }

    @Test
    public void aFixedCostBesideTheScaledOne_stillConverges() {
        Gpu gpu = new Gpu(1f, 3f, 0.5f);
        gpu.run(600, 2f);
        assertTrue("scale " + gpu.scale, gpu.scale > 0.4f && gpu.scale < 0.62f);
        assertEquals(0, gpu.run(200, 2f));
    }

    @Test
    public void eachTierStartsAtItsShare() {
        assertEquals(1f, CgGpuBudget.share(ComputeTier.V), 0f);
        assertEquals(1f, CgGpuBudget.share(ComputeTier.G43), 0f);
        assertEquals(0.5f, CgGpuBudget.share(ComputeTier.G40), 0f);
        assertEquals(0.5f, CgGpuBudget.share(ComputeTier.G33), 0f);
        assertEquals(0.25f, CgGpuBudget.share(ComputeTier.CPU), 0f);
    }

    /** Costs {@code fixed + full * scale} ms a frame, each frame's answer arriving {@link #LAG} frames later. */
    private static final class Gpu {
        final CgGpuBudget.Controller controller = new CgGpuBudget.Controller();
        final float full, fixed;
        final ArrayDeque<float[]> inFlight = new ArrayDeque<>();
        float scale;
        boolean uneven;
        /** Every nth frame takes ten times as long, as a driver's flush makes one. */
        int stretchEvery;
        int frame;

        Gpu(float start, float full, float fixed) {
            this.full = full;
            this.fixed = fixed;
            scale = controller.start(start, 0);
        }

        /** Frames run; answers how many times the scale changed. */
        int run(int frames, float budget) {
            int changes = 0;
            for (int i = 0; i < frames; i++, frame++) {
                float cost = fixed + full * scale;
                if (stretchEvery > 0 && frame % stretchEvery == 0) cost *= 10f;
                inFlight.addLast(new float[] {frame, cost});
                // Two answers in one frame, then none.
                int landing = inFlight.size() <= LAG ? 0 : uneven ? ((frame & 1) == 1 ? 2 : 0) : 1;
                float before = scale;
                for (int k = 0; k < landing && !inFlight.isEmpty(); k++) {
                    float[] done = inFlight.pollFirst();
                    scale = controller.sample((long) done[0], done[1], frame + 1, budget, 0.1f);
                }
                if (scale != before) changes++;
            }
            return changes;
        }
    }
}
