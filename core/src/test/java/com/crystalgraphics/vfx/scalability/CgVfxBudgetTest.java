package com.crystalgraphics.vfx.scalability;

import com.crystalgraphics.render.CgCpuMeter;
import com.crystalgraphics.settings.CgQuality;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The effects' CPU budget, as Unreal's FX budget: averaged lines, an adjusted usage up at once and down slowly. */
public class CgVfxBudgetTest {

    private static final long FRAME_NANOS = 16_666_667L;

    private final CgCpuMeter frame = new CgCpuMeter(), workers = new CgCpuMeter();
    private final CgVfxBudget budget = new CgVfxBudget(frame, workers);
    private long index, nanos;

    @After
    public void noOverrides() {
        CgVfxBudget.FRAME_OVERRIDE.set(-1f);
        CgVfxBudget.CONCURRENT_OVERRIDE.set(-1f);
    }

    /** One host frame at 60 a second, {@code frameMs} on the frame thread and {@code workerMs} on the workers. */
    private void frame(float frameMs, float workerMs, CgQuality tier) {
        frame.add(Math.round(frameMs * 1e6));
        workers.add(Math.round(workerMs * 1e6));
        budget.tick(index++, nanos, tier);
        nanos += FRAME_NANOS;
    }

    @Test
    public void eachLineIsItsTimeOverItsTiersBudget() {
        for (int i = 0; i < CgVfxBudget.HISTORY; i++) frame(1.5f, 0.75f, CgQuality.HIGH);
        assertEquals(1.5f, budget.time(CgVfxBudget.Line.FRAME), 1e-4f);
        assertEquals(2.25f, budget.time(CgVfxBudget.Line.CONCURRENT), 1e-4f);   // the workers' and the frame's
        assertEquals(1.5f / budget.budget(CgVfxBudget.Line.FRAME), budget.usage(CgVfxBudget.Line.FRAME), 1e-5f);
        assertEquals(2.25f / budget.budget(CgVfxBudget.Line.CONCURRENT), budget.usage(CgVfxBudget.Line.CONCURRENT), 1e-5f);
    }

    @Test
    public void theTimeIsTheLastHistoryOfFramesAveraged() {
        for (int i = 0; i < CgVfxBudget.HISTORY; i++) frame(4f, 0f, CgQuality.HIGH);
        for (int i = 0; i < CgVfxBudget.HISTORY / 2; i++) frame(0f, 0f, CgQuality.HIGH);
        assertEquals(2f, budget.time(CgVfxBudget.Line.FRAME), 1e-4f);
        for (int i = 0; i < CgVfxBudget.HISTORY / 2; i++) frame(0f, 0f, CgQuality.HIGH);
        assertEquals(0f, budget.time(CgVfxBudget.Line.FRAME), 1e-4f);
    }

    @Test
    public void adjustedUsageRisesAtOnceAndFallsAtTheDecayRate() {
        frame(0f, 0f, CgQuality.HIGH);
        CgVfxBudget.FRAME_OVERRIDE.set(budget.budget(CgVfxBudget.Line.FRAME) * 1.5f);
        frame(0f, 0f, CgQuality.HIGH);
        assertEquals(1.5f, budget.worstAdjustedUsage(), 1e-5f);
        CgVfxBudget.FRAME_OVERRIDE.set(0f);
        for (int i = 0; i < 60; i++) frame(0f, 0f, CgQuality.HIGH);   // a second
        assertEquals(0f, budget.usage(CgVfxBudget.Line.FRAME), 0f);
        assertEquals(1.5f - CgVfxBudget.DECAY, budget.worstAdjustedUsage(), 1e-3f);
    }

    @Test
    public void adjustedUsageStopsAtItsMost() {
        CgVfxBudget.FRAME_OVERRIDE.set(budget.budget(CgVfxBudget.Line.FRAME) * 10f);
        frame(0f, 0f, CgQuality.HIGH);
        assertEquals(10f, budget.usage(CgVfxBudget.Line.FRAME), 1e-4f);
        assertEquals(CgVfxBudget.MAX_USAGE, budget.worstAdjustedUsage(), 0f);
    }

    @Test
    public void theWorseLineIsTheOneRead() {
        CgVfxBudget.CONCURRENT_OVERRIDE.set(budget.budget(CgVfxBudget.Line.CONCURRENT) * 1.25f);
        frame(0f, 0f, CgQuality.HIGH);
        assertEquals(1.25f, budget.worstAdjustedUsage(), 1e-5f);
    }

    @Test
    public void aSecondTickInOneFrameDoesNothing() {
        frame.add(6_000_000L);
        budget.tick(7, 0L, CgQuality.HIGH);
        frame.add(6_000_000L);
        budget.tick(7, 0L, CgQuality.HIGH);
        assertEquals(6f / CgVfxBudget.HISTORY, budget.time(CgVfxBudget.Line.FRAME), 1e-5f);
    }

    @Test
    public void aColdFirstFrameCountsAsOneOfTheHistory() {
        frame(120f, 0f, CgQuality.HIGH);   // class loads and a first compile: one frame of a full history
        assertEquals(2f, budget.time(CgVfxBudget.Line.FRAME), 1e-4f);
        assertEquals(2f / budget.budget(CgVfxBudget.Line.FRAME), budget.worstAdjustedUsage(), 1e-5f);
    }

    @Test
    public void lowerTiersHoldTighterBudgets() {
        frame(0f, 0f, CgQuality.LOW);
        float low = budget.budget(CgVfxBudget.Line.FRAME);
        frame(0f, 0f, CgQuality.ULTRA);
        float ultra = budget.budget(CgVfxBudget.Line.FRAME);
        assertEquals(true, low < ultra);
        assertEquals(true, budget.budget(CgVfxBudget.Line.CONCURRENT) >= ultra);
    }

    @Test
    public void aRampHoldsItsEndsAndRunsStraightBetween() {
        CgVfxLinearRamp ramp = CgVfxLinearRamp.DEFAULT;
        assertEquals(1f, ramp.at(0.25f), 0f);
        assertEquals(0.75f, ramp.at(0.75f), 1e-6f);
        assertEquals(0.5f, ramp.at(2f), 0f);
    }
}
