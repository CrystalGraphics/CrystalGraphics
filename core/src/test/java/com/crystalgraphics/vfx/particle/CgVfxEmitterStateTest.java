package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Emitter time (aaa-design §2.14): Niagara's Emitter State loops, delays and spawn modules, as an instance's books
 * schedule them.
 */
public class CgVfxEmitterStateTest {

    private static final float DT = 1f / 60f;

    /** The steps (by index) in which {@code instance} booked bursts, over {@code steps} steps. */
    private static List<Integer> burstSteps(CgVfxEmitterInstance instance, int steps) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            if (instance.books().stepBursts() > 0) at.add(i);
        }
        return at;
    }

    @Test
    public void anInfiniteLoopRenewsItsBurstUntilStopped() {
        // Kakarot's beam core: one mesh particle, renewed every 2.9 s by Cascade's looping (aaa-import §6 B2).
        CgVfxEmitter core = CgVfxEmitter.builder("core").state(CgVfxEmitterState.infinite(2.9f)).capacity(4)
                .burst(0f, 1).life(2.9f, 2.9f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(core, 0.4f);
        instance.start(0f, 0f, 0f);
        List<Integer> at = burstSteps(instance, 600);
        assertEquals(4, at.size());
        for (int loop = 0; loop < at.size(); loop++) assertEquals(loop * 2.9f, at.get(loop) * DT, DT * 1.01f);
        assertFalse(instance.finished());
        instance.stop();
        for (int i = 0; i < 200; i++) instance.books().schedule(DT, 0.0, 0.0, 0.0);
        assertTrue(instance.finished());
    }

    @Test
    public void multipleLoopsRunTheirCountThenFinish() {
        CgVfxEmitter puffs = CgVfxEmitter.builder("puffs").state(CgVfxEmitterState.loops(3, 0.5f)).capacity(100)
                .burst(0.1f, 5).life(0.2f, 0.2f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(puffs, 0.4f);
        instance.start(0f, 0f, 0f);
        int finishedAt = -1;
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < 300 && finishedAt < 0; i++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            if (instance.books().stepBursts() > 0) at.add(i);
            if (instance.finished()) finishedAt = i;
        }
        assertEquals(3, at.size());
        assertEquals(1.1f, at.get(2) * DT, DT * 1.01f);
        assertTrue("finished once its last burst has died", finishedAt >= 0 && finishedAt * DT < 1.5f);
    }

    @Test
    public void aDelayRangeIsPickedEachLoopTheSameOnEverySide() {
        CgVfxEmitterState state = CgVfxEmitterState.infinite(1f).delay(0.2f, 0.8f).recalculateDelayEachLoop();
        CgVfxEmitter ticks = CgVfxEmitter.builder("ticks").state(state).capacity(100).burst(0f, 1).life(0.1f, 0.1f).build();
        CgVfxEmitterInstance a = new CgVfxEmitterInstance(ticks, 0.7f), b = new CgVfxEmitterInstance(ticks, 0.7f);
        a.start(0f, 0f, 0f);
        b.start(0f, 0f, 0f);
        List<Integer> first = burstSteps(a, 900), second = burstSteps(b, 900);
        assertEquals(first, second);
        float start = 0f;
        for (int loop = 0; loop < first.size(); loop++) {
            float delay = state.delayBefore(a.books().seedBits(), loop);
            assertTrue(delay >= 0.2f && delay <= 0.8f);
            start += delay;
            assertEquals(start, first.get(loop) * DT, DT * 1.01f * (loop + 1));
            start += 1f;
        }
    }

    @Test
    public void aFirstLoopOnlyDelayWaitsOnce() {
        CgVfxEmitter ticks = CgVfxEmitter.builder("ticks").state(CgVfxEmitterState.infinite(1f).delay(0.5f).delayFirstLoopOnly())
                .capacity(100).burst(0f, 1).life(0.1f, 0.1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(ticks, 0.2f);
        instance.start(0f, 0f, 0f);
        List<Integer> at = burstSteps(instance, 200);
        assertEquals(0.5f, at.get(0) * DT, DT * 1.01f);
        assertEquals(1.5f, at.get(1) * DT, DT * 1.01f);
        assertEquals(2.5f, at.get(2) * DT, DT * 1.01f);
    }

    @Test
    public void theRateFollowsItsCurveThroughEachLoopAndStopsAtAOnceLoopsEnd() {
        // Nothing for its first half second, then 120 a second, ended by the loop at 1.5 s.
        CgKeyframes late = CgKeyframes.start(0.5f, 0f).to(0.5f, 1f, CgEasings.LINEAR).build();
        CgVfxEmitter smoke = CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.5f)).capacity(4000)
                .rate(120f, late).life(0.5f, 0.5f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(smoke, 0.3f);
        instance.start(0f, 0f, 0f);
        int before = 0, after = 0, finishedAt = -1;
        for (int i = 0; i < 180 && finishedAt < 0; i++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            if (i * DT < 0.49f) before += instance.books().stepCandidates();
            else after += instance.books().stepCandidates();
            if (instance.finished()) finishedAt = i;
        }
        assertEquals(0, before);
        assertEquals(120, after, 2);
        assertTrue("finished after the loop and its last life", finishedAt >= 0 && finishedAt * DT < 2.1f);
    }

    @Test
    public void aBurstsCountRangeIsRolledEachLoopAndItsProbabilitySkipsLoops() {
        CgVfxEmitter sparks = CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.infinite(0.25f)).capacity(10000)
                .burst(CgVfxSpawnBurst.at(0f, 8, 16)).burst(CgVfxSpawnBurst.at(0.1f, 1).probability(0.3f))
                .life(0.1f, 0.1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(sparks, 0.9f);
        instance.start(0f, 0f, 0f);
        int loops = 0, fired = 0, smallest = Integer.MAX_VALUE, largest = 0;
        for (int i = 0; i < 6000; i++) {
            instance.books().schedule(DT, 0.0, 0.0, 0.0);
            int n = instance.books().stepBursts();
            if (n >= 8) {
                loops++;
                smallest = Math.min(smallest, n);
                largest = Math.max(largest, n);
            } else if (n == 1) {
                fired++;
            }
        }
        assertEquals(400, loops);
        assertEquals(8, smallest);
        assertEquals(16, largest);
        assertEquals(0.3f, fired / (float) loops, 0.08f);
    }

    @Test
    public void aLoopingEmittersSlotHoldsEveryLoopItsParticlesOutliveTheirNext() {
        // Each loop's burst outlives the loop: the slot holds two loops' worth at once.
        CgVfxEmitter overlap = CgVfxEmitter.builder("overlap").state(CgVfxEmitterState.infinite(0.5f)).capacity(10000)
                .burst(0f, 40).life(0.8f, 0.8f).build();
        assertEquals(120, overlap.peakAlive());
        CgVfxEmitter once = overlap.toBuilder().state(CgVfxEmitterState.ONCE).build();
        assertEquals(40, once.peakAlive());
    }

    @Test
    public void theDefaultIsNiagarasALoopASecondForEver() {
        // FNiagaraEmitterStateData: LoopBehavior Infinite, LoopDuration 1, no delay, Complete.
        CgVfxEmitter puff = CgVfxEmitter.builder("puff").capacity(100).burst(0f, 3).life(0.1f, 0.1f).build();
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(puff, 0.5f);
        instance.start(0f, 0f, 0f);
        List<Integer> at = burstSteps(instance, 200);
        assertEquals(4, at.size());
        for (int loop = 0; loop < at.size(); loop++) assertEquals(loop, at.get(loop) * DT, DT * 1.01f);
    }

    @Test
    public void aDurationRangeIsRolledOnceUnlessRecalculatedEachLoop() {
        CgVfxEmitterState once = CgVfxEmitterState.infinite(0.5f, 1.5f), each = once.recalculateDurationEachLoop();
        int seed = new CgVfxEmitterInstance(CgVfxEmitter.builder("seed").build(), 0.42f).books().seedBits();
        float first = once.durationOf(seed, 0);
        boolean varies = false;
        for (int loop = 0; loop < 8; loop++) {
            assertEquals(first, once.durationOf(seed, loop), 0f);
            float d = each.durationOf(seed, loop);
            assertTrue(d >= 0.5f && d <= 1.5f);
            varies |= d != each.durationOf(seed, 0);
        }
        assertTrue("a recalculated duration varies by loop", varies);
    }

    @Test(expected = IllegalStateException.class)
    public void loopsInNoTimeAreRefused() {
        CgVfxEmitter.builder("never").state(CgVfxEmitterState.infinite(0f)).burst(0f, 1).build();
    }
}
