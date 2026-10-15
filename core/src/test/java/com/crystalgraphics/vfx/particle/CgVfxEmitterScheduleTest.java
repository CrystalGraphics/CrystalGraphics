package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * An instance's books (vfx-gpu §13.7): what {@code schedule} queues each step, when it says it has finished, and that
 * no more are alive than its definition's {@code peakAlive}, each from the hashes the Step kernel draws by.
 */
public class CgVfxEmitterScheduleTest {

    private static final float DT = 1f / 60f;

    @Test
    public void burstsScheduleTheSpawnsTickMakes() {
        check(CgVfxEmitter.builder("bursts").capacity(4000).burst(0f, 300).burst(0.5f, 200).burst(2.4f, 120)
                .life(1f, 2f).speed(2f, 6f).module(new CgVfxModule.Gravity(9.8f)).build(), 620, 1f);
    }

    @Test
    public void ratesAndBurstsThinnedByShare() {
        check(CgVfxEmitter.builder("rate").capacity(4000).rate(400f, 0f, 1.5f).burst(0.2f, 50)
                .life(0.5f, 1.2f).speed(1f, 3f).module(new CgVfxModule.Drag(2f, 0f)).build(), 650, 0.6f);
    }

    @Test
    public void groundedParticlesStillDieOfAge() {
        check(CgVfxEmitter.builder("debris").capacity(4000).burst(0f, 400).life(2f, 3.5f).speed(4f, 12f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)).build(), 400, 1f);
    }

    @Test
    public void anOpenRateFinishesOnceStoppedOrCoasting() {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").capacity(400).rate(60f, 0f, Float.POSITIVE_INFINITY)
                .life(0.5f, 1f).build();
        CgVfxEmitterInstance stopped = new CgVfxEmitterInstance(wake, 0.3f), coasting = new CgVfxEmitterInstance(wake, 0.3f);
        stopped.start(0f, 0f, 0f);
        coasting.start(0f, 0f, 0f);
        for (int i = 0; i < 120; i++) {
            stopped.schedule(DT, 0.0, 0.0, 0.0);
            coasting.schedule(DT, 0.0, 0.0, 0.0);
        }
        assertFalse(stopped.finished());
        stopped.stop();
        for (int i = 0; i < 90; i++) {
            stopped.schedule(DT, 0.0, 0.0, 0.0);
            coasting.coast(DT);
            assertEquals(0, stopped.stepCandidates());
        }
        assertTrue(stopped.finished());
        assertTrue(coasting.finished());
    }

    @Test
    public void rateScaleScalesTheRateAlone() {
        CgVfxEmitter definition = CgVfxEmitter.builder("scaled").capacity(4000).rate(120f, 0f, Float.POSITIVE_INFINITY)
                .burst(0f, 10).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.3f).rateScale(0f);
        instance.start(0f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        assertEquals(10, instance.stepCandidates());
        int spawned = 0;
        instance.rateScale(3f);
        for (int i = 0; i < 60; i++) {
            instance.schedule(DT, 0.0, 0.0, 0.0);
            spawned += instance.stepCandidates();
        }
        assertEquals(360, spawned, 1);
    }

    @Test
    public void childrenStepAtTheirParentsOrigin() {
        CgVfxEmitter flash = CgVfxEmitter.builder("flash").capacity(64).life(0.1f, 0.1f).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").capacity(64).burst(0f, 10).life(1f, 1f)
                .event(CgVfxEvent.onCollision().spawn(flash, 1)).build();
        CgVfxEmitterInstance parent = new CgVfxEmitterInstance(sparks, 0.5f);
        parent.start(0f, 7f, 0f);
        parent.schedule(DT, -21.0, 0.0, 14.0);
        CgVfxEmitterInstance child = parent.child(0);
        assertTrue(child.scheduled());
        assertEquals(-21.0, child.originX(), 0.0);
        assertEquals(14.0, child.originZ(), 0.0);
    }

    @Test
    public void curveRowSamplesSizeThenOpacity() {
        CgVfxEmitter definition = CgVfxEmitter.builder("curves")
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2f, CgEasings.LINEAR).build()).build();
        float[] row = new float[2 + 2 * CgVfxGpuEmitter.CURVE_TEXELS];
        definition.writeCurves(row, 2, CgVfxGpuEmitter.CURVE_TEXELS);
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            float progress = i / (CgVfxGpuEmitter.CURVE_TEXELS - 1f);
            assertEquals(definition.sizeAt(progress), row[2 + 2 * i], 0f);
            assertEquals(definition.opacityAt(progress), row[3 + 2 * i], 0f);
        }
        assertEquals(0.5f, row[2], 0f);
        assertEquals(2f, row[row.length - 2], 1e-6f);
    }

    @Test
    public void aMovingSourceSpreadsItsRateFromWhereTheStepBegan() {
        CgVfxEmitter trail = CgVfxEmitter.builder("trail").capacity(2000).rate(600f, 0f, Float.POSITIVE_INFINITY)
                .burst(0.04f, 5).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(trail, 0.3f);
        instance.start(0f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        assertEquals("the first step has no motion", instance.sourceX(), instance.fromX(), 0f);
        for (int step = 1; step < 6; step++) {
            instance.moveTo(step, 0f, 0f);
            instance.schedule(DT, 0.0, 0.0, 0.0);
            assertEquals(step - 1f, instance.fromX(), 0f);
            assertEquals(step, instance.sourceX(), 0f);
            assertEquals(step == 3 ? 5 : 0, instance.stepBursts());
            assertEquals(instance.stepBursts() + 10, instance.stepCandidates(), 1);
        }
    }

    @Test
    public void ratePerBlockCountsHowFarItWentAndATeleportNothing() {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").capacity(5000).ratePerBlock(4f).maxStep(8f).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(wake, 0.3f);
        instance.start(0f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        assertEquals(0, instance.stepCandidates());
        int spawned = 0;
        for (int step = 1; step <= 10; step++) {
            instance.moveTo(step * 0.5f, 0f, 0f);
            instance.schedule(DT, 0.0, 0.0, 0.0);
            spawned += instance.stepCandidates();
        }
        assertEquals("four a block over five blocks", 20, spawned);
        instance.moveTo(100f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        assertEquals("a teleport spawns nothing", 0, instance.stepCandidates());
        assertEquals(100f, instance.fromX(), 0f);
        assertFalse("by distance alone it spawns until stopped", instance.finished());
    }

    @Test
    public void capacityDropsWhatWouldPassIt() {
        CgVfxEmitter capped = CgVfxEmitter.builder("capped").capacity(100).burst(0f, 500).burst(0.49f, 50)
                .burst(1.49f, 50).life(1f, 1f).build();
        assertEquals(100, capped.peakAlive());
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(capped, 0.3f);
        instance.start(0f, 0f, 0f);
        int[] candidates = new int[120];
        for (int step = 0; step < candidates.length; step++) {
            instance.schedule(DT, 0.0, 0.0, 0.0);
            candidates[step] = instance.stepCandidates();
        }
        assertEquals("the first burst up to the capacity", 100, candidates[0]);
        assertEquals("full half a second later", 0, candidates[30]);
        assertEquals("room once the first have died", 50, candidates[90]);
    }

    /**
     * Steps {@code definition} by its books alone: spawn indices handed out without gaps, as many as its bursts and rate
     * make, and {@code finished} a step after the latest death, each kept spawn's life drawn by the hash the kernel
     * draws it by. What is alive at each step, from those deaths, stays within {@code peakAlive}.
     */
    private static void check(CgVfxEmitter definition, int expected, float share) {
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.71f).share(share);
        instance.start(0f, 0f, 0f);
        int seed = instance.seedBits(), handed = 0, peak = 0, finishedAt = -1;
        List<Float> deaths = new ArrayList<>();
        float lastDeath = 0f;
        for (int step = 0; step < 600 && finishedAt < 0; step++) {
            float now = step * DT;
            instance.schedule(DT, 10.0, 64.0, -5.0);
            assertEquals(definition.name() + " step " + step + "'s first spawn", handed, instance.stepFirstSpawn());
            int bursts = instance.stepBursts(), spread = instance.stepCandidates() - bursts;
            for (int k = handed; k < handed + instance.stepCandidates(); k++) {
                if (share < 1f && CgVfxEmitterInstance.rand(seed, k, 10) >= share) continue;
                float life = definition.lifeMin + (definition.lifeMax - definition.lifeMin)
                        * CgVfxEmitterInstance.rand(seed, k, 4);
                // A burst is born as the step begins; rate spawn i of n, (i + 1) / n of the way through it.
                int i = k - handed - bursts;
                float born = i < 0 ? 0f : (float) (i + 1) / spread;
                float death = now + DT * (float) Math.ceil(life / DT + born);
                deaths.add(death);
                lastDeath = Math.max(lastDeath, death);
            }
            handed += instance.stepCandidates();
            int alive = 0;
            for (float death : deaths) if (death > now + DT) alive++;
            peak = Math.max(peak, alive);
            if (instance.finished()) finishedAt = step;
        }
        // A rate's window may end a step late, as its clock adds up step lengths in float.
        assertEquals(definition.name() + " spawned", expected, handed, definition.rate * DT + 1f);
        assertTrue(definition.name() + " finished", finishedAt >= 0);
        assertTrue(definition.name() + " finished at step " + finishedAt + " for its last death at " + lastDeath,
                Math.abs((finishedAt + 1) * DT - (lastDeath + DT)) <= DT * 1.01f);
        assertTrue(definition.name() + " had " + peak + " alive, past peakAlive " + definition.peakAlive(),
                peak <= definition.peakAlive());
    }
}
