package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
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
        check(CgVfxEmitter.builder("bursts").state(CgVfxEmitterState.ONCE)
                .capacity(4000).burst(0f, 300).burst(0.5f, 200).burst(2.4f, 120)
                .life(1f, 2f).speed(2f, 6f).module(new CgVfxModule.Gravity(9.8f)).build(), 620, 1f);
    }

    @Test
    public void ratesAndBurstsThinnedByShare() {
        check(CgVfxEmitter.builder("rate").state(CgVfxEmitterState.once(1.5f)).capacity(4000).rate(400f).burst(0.2f, 50)
                .life(0.5f, 1.2f).speed(1f, 3f).module(new CgVfxModule.Drag(2f, 0f)).build(), 650, 0.6f);
    }

    @Test
    public void groundedParticlesStillDieOfAge() {
        check(CgVfxEmitter.builder("debris").state(CgVfxEmitterState.ONCE)
                .capacity(4000).burst(0f, 400).life(2f, 3.5f).speed(4f, 12f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0.3f, 0.5f, 0.6f)).build(), 400, 1f);
    }

    @Test
    public void anOpenRateFinishesOnceStoppedOrCoasting() {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").state(CgVfxEmitterState.ONCE).capacity(400).rate(60f)
                .life(0.5f, 1f).build();
        CgVfxEmitterInstance stopped = new CgVfxEmitterInstance(wake, 0.3f), coasting = new CgVfxEmitterInstance(wake, 0.3f);
        stopped.start(0f, 0f, 0f);
        coasting.start(0f, 0f, 0f);
        for (int i = 0; i < 120; i++) {
            stopped.books().schedule(DT, 0.0, 0.0, 0.0);
            coasting.books().schedule(DT, 0.0, 0.0, 0.0);
        }
        assertFalse(stopped.finished());
        stopped.stop();
        for (int i = 0; i < 90; i++) {
            stopped.books().schedule(DT, 0.0, 0.0, 0.0);
            coasting.books().coast(DT);
            assertEquals(0, stopped.books().stepCandidates());
        }
        assertTrue(stopped.finished());
        assertTrue(coasting.finished());
    }

    @Test
    public void rateScaleScalesTheRateAlone() {
        CgVfxEmitter definition = CgVfxEmitter.builder("scaled").state(CgVfxEmitterState.ONCE).capacity(4000).rate(120f)
                .burst(0f, 10).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.3f).rateScale(0f);
        instance.start(0f, 0f, 0f);
        instance.books().schedule(DT, 0.0, 0.0, 0.0);
        assertEquals(10, instance.books().stepCandidates());
        int spawned = 0;
        instance.rateScale(3f);
        for (int i = 0; i < 60; i++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            spawned += instance.books().stepCandidates();
        }
        assertEquals(360, spawned, 1);
    }

    @Test
    public void childrenStepAtTheirParentsOrigin() {
        CgVfxEmitter flash = CgVfxEmitter.builder("flash").capacity(64).life(0.1f, 0.1f).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE)
                .capacity(64).burst(0f, 10).life(1f, 1f)
                .event(CgVfxEvent.onCollision().spawn(flash, 1)).build();
        CgVfxEmitterInstance parent = new CgVfxEmitterInstance(sparks, 0.5f);
        parent.start(0f, 7f, 0f);
        parent.books().schedule(DT, -21.0, 0.0, 14.0);
        CgVfxEmitterInstance child = parent.child(0);
        assertTrue(child.books().scheduled());
        assertEquals(-21.0, child.originX(), 0.0);
        assertEquals(14.0, child.originZ(), 0.0);
    }

    @Test
    public void curveRowSamplesShapeThenTintThenStarts() {
        CgKeyframes rising = CgKeyframes.start(0f, 0.5f).to(1f, 2f, CgEasings.LINEAR).build();
        CgKeyframes falling = CgKeyframes.start(0f, 1f).to(1f, 0.25f, CgEasings.LINEAR).build();
        CgVfxEmitter definition = CgVfxEmitter.builder("curves").size(rising)
                .scale(1f, 2f, 6f, 1f, 3f, 9f).scaleOverLife(falling, CgKeyframes.constant(1f), rising)
                .color(0x80FF8000, 2f, 0xFF0000FF, 1f).colorOverLife(falling, rising, CgKeyframes.constant(1f))
                .intensityOverLife(CgKeyframes.constant(3f)).build();
        int texels = CgVfxGpuEmitter.CURVE_TEXELS, starts = 8 * texels;
        float[] row = new float[4 * (2 * texels + CgVfxGpuEmitter.CURVE_STARTS)];
        definition.writeCurves(row, 0, texels);
        for (int i = 0; i < texels; i++) {
            float p = i / (texels - 1f);
            assertEquals(falling.at(p), row[4 * i], 0f);
            assertEquals(1f, row[4 * i + 1], 0f);
            assertEquals(rising.at(p), row[4 * i + 2], 0f);
            assertEquals(definition.sizeAt(p), row[4 * i + 3], 0f);
            assertEquals(falling.at(p) * 3f, row[4 * texels + 4 * i], 1e-6f);
            assertEquals(rising.at(p) * 3f, row[4 * texels + 4 * i + 1], 1e-6f);
            assertEquals(3f, row[4 * texels + 4 * i + 2], 0f);
            assertEquals(definition.opacityAt(p), row[4 * texels + 4 * i + 3], 0f);
        }
        // 0x80FF8000 times 2: orange past white, half its alpha; then blue
        assertArrayEquals(new float[]{2f, 128f / 255f * 2f, 0f, 128f / 255f, 0f, 0f, 1f, 1f},
                Arrays.copyOfRange(row, starts, starts + 8), 1e-6f);
        assertArrayEquals(new float[]{1f, 2f, 6f, 0f, 1f, 3f, 9f, 0f}, Arrays.copyOfRange(row, starts + 8, starts + 16), 0f);
        // Its largest: size 2 at the end, times z's 2 there, times the widest start, 9
        assertEquals(2f * 2f * 9f, definition.largestSize(), 1e-4f);
    }

    @Test
    public void unsetLooksDrawAsBefore() {
        CgVfxEmitter plain = CgVfxEmitter.builder("plain").size(0.2f, 0.4f).build();
        int texels = CgVfxGpuEmitter.CURVE_TEXELS, starts = 8 * texels;
        float[] row = new float[4 * (2 * texels + CgVfxGpuEmitter.CURVE_STARTS)];
        plain.writeCurves(row, 0, texels);
        for (int i = 0; i < texels; i++) {
            for (int a = 0; a < 3; a++) {
                assertEquals(1f, row[4 * i + a], 0f);
                assertEquals(1f, row[4 * texels + 4 * i + a], 0f);
            }
        }
        assertArrayEquals(new float[]{1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 1f, 1f, 1f, 0f},
                Arrays.copyOfRange(row, starts, starts + 16), 0f);
        assertEquals(0.4f, plain.largestSize(), 1e-6f);
    }

    @Test
    public void aMovingSourceSpreadsItsRateFromWhereTheStepBegan() {
        CgVfxEmitter trail = CgVfxEmitter.builder("trail").state(CgVfxEmitterState.ONCE).capacity(2000).rate(600f)
                .burst(0.04f, 5).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(trail, 0.3f);
        instance.start(0f, 0f, 0f);
        instance.books().schedule(DT, 0.0, 0.0, 0.0);
        assertEquals("the first step has no motion", instance.sourceX(), instance.books().fromX(), 0f);
        for (int step = 1; step < 6; step++) {
            instance.moveTo(step, 0f, 0f);
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            assertEquals(step - 1f, instance.books().fromX(), 0f);
            assertEquals(step, instance.sourceX(), 0f);
            assertEquals(step == 3 ? 5 : 0, instance.books().stepBursts());
            assertEquals(instance.books().stepBursts() + 10, instance.books().stepCandidates(), 1);
        }
    }

    @Test
    public void ratePerBlockCountsHowFarItWentAndATeleportNothing() {
        CgVfxEmitter wake = CgVfxEmitter.builder("wake").capacity(5000).ratePerBlock(4f).maxStep(8f).life(1f, 1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(wake, 0.3f);
        instance.start(0f, 0f, 0f);
        instance.books().schedule(DT, 0.0, 0.0, 0.0);
        assertEquals(0, instance.books().stepCandidates());
        int spawned = 0;
        for (int step = 1; step <= 10; step++) {
            instance.moveTo(step * 0.5f, 0f, 0f);
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            spawned += instance.books().stepCandidates();
        }
        assertEquals("four a block over five blocks", 20, spawned);
        instance.moveTo(100f, 0f, 0f);
        instance.books().schedule(DT, 0.0, 0.0, 0.0);
        assertEquals("a teleport spawns nothing", 0, instance.books().stepCandidates());
        assertEquals(100f, instance.books().fromX(), 0f);
        assertFalse("by distance alone it spawns until stopped", instance.finished());
    }

    @Test
    public void capacityDropsWhatWouldPassIt() {
        CgVfxEmitter capped = CgVfxEmitter.builder("capped").state(CgVfxEmitterState.ONCE)
                .capacity(100).burst(0f, 500).burst(0.49f, 50)
                .burst(1.49f, 50).life(1f, 1f).build();
        assertEquals(100, capped.peakAlive());
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(capped, 0.3f);
        instance.start(0f, 0f, 0f);
        int[] candidates = new int[120];
        for (int step = 0; step < candidates.length; step++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            candidates[step] = instance.books().stepCandidates();
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
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(definition, 0.71f);
        instance.books().share(share);
        instance.start(0f, 0f, 0f);
        int seed = instance.books().seedBits(), handed = 0, peak = 0, finishedAt = -1;
        List<Float> deaths = new ArrayList<>();
        float lastDeath = 0f;
        for (int step = 0; step < 600 && finishedAt < 0; step++) {
            float now = step * DT;
            instance.books().schedule(DT, 10.0, 64.0, -5.0);
            assertEquals(definition.name() + " step " + step + "'s first spawn", handed, instance.books().stepFirstSpawn());
            int bursts = instance.books().stepBursts(), spread = instance.books().stepCandidates() - bursts;
            for (int k = handed; k < handed + instance.books().stepCandidates(); k++) {
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
            handed += instance.books().stepCandidates();
            int alive = 0;
            for (float death : deaths) if (death > now + DT) alive++;
            peak = Math.max(peak, alive);
            if (instance.finished()) finishedAt = step;
        }
        // A rate's window may end a step late, as its clock adds up step lengths in float.
        assertEquals(definition.name() + " spawned", expected, handed, definition.rate.perSecond() * DT + 1f);
        assertTrue(definition.name() + " finished", finishedAt >= 0);
        assertTrue(definition.name() + " finished at step " + finishedAt + " for its last death at " + lastDeath,
                Math.abs((finishedAt + 1) * DT - (lastDeath + DT)) <= DT * 1.01f);
        assertTrue(definition.name() + " had " + peak + " alive, past peakAlive " + definition.peakAlive(),
                peak <= definition.peakAlive());
    }
}
