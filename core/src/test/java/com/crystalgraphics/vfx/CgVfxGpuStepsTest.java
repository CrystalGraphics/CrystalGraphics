package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A system's GPU queue (vfx-gpu §13.7): a scheduled instance holds a slot from its first step until it has finished,
 * every step queues one step of its pool, and a tenant its effect stops stepping coasts to its end.
 */
public class CgVfxGpuStepsTest {

    private static final float DT = 1f / 60f;
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("sparks").capacity(400).burst(0f, 120)
            .life(0.4f, 0.9f).speed(2f, 5f).module(new CgVfxModule.Gravity(9.8f)).build();
    private static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers").capacity(400).rate(200f, 0f, 0.5f)
            .life(0.3f, 0.6f).speed(1f, 2f).module(new CgVfxModule.Drag(1f, 0f)).build();

    @Test
    public void aSlotLastsUntilItsInstanceFinishes() {
        CgVfxGpuSteps steps = new CgVfxGpuSteps();
        CgVfxAir air = new CgVfxAir().wind(0f, 0f, 0f);
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(SPARKS, 0.3f);
        instance.start(0f, 1f, 0f);
        CgVfxParticlePool pool = CgVfxParticlePool.of(SPARKS);
        int queued = pool.queuedSteps(), stepped = 0;
        for (int n = 0; n < 600 && (n == 0 || !steps.isEmpty()); n++) {
            instance.schedule(DT, 4.0, 64.0, -2.0);
            if (n == 0) steps.admit(instance);
            assertEquals(1, pool.openSlots());
            assertNotNull(steps.of(instance));
            steps.step(DT, air);
            stepped++;
        }
        assertTrue(instance.finished());
        assertNull(steps.of(instance));
        assertEquals(0, pool.openSlots());
        assertEquals(queued + stepped, pool.queuedSteps());
        assertTrue("finished after " + stepped + " steps", stepped <= Math.ceil(0.9f / DT) + 2);
    }

    @Test
    public void aTenantItsEffectStopsSteppingCoastsToItsEnd() {
        CgVfxGpuSteps steps = new CgVfxGpuSteps();
        CgVfxAir air = new CgVfxAir().wind(0f, 0f, 0f);
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(EMBERS, 0.8f);
        instance.start(0f, 0f, 0f);
        instance.schedule(DT, 0.0, 0.0, 0.0);
        steps.admit(instance);
        steps.step(DT, air);
        int spawned = instance.stepFirstSpawn() + instance.stepCandidates(), coasted = 0;
        // Its effect is gone: nothing schedules it again, and the queue steps it alone.
        while (!steps.isEmpty() && coasted < 600) {
            steps.step(DT, air);
            coasted++;
            assertEquals(0, instance.stepCandidates());
            assertEquals(spawned, instance.stepFirstSpawn());
        }
        assertTrue(steps.isEmpty());
        assertTrue("coasted " + coasted + " steps", coasted <= Math.ceil(0.6f / DT) + 1);
        assertEquals(0, CgVfxParticlePool.of(EMBERS).openSlots());
    }

    @Test
    public void oneSystemStepsAPool() {
        CgVfxGpuSteps first = new CgVfxGpuSteps(), second = new CgVfxGpuSteps();
        CgVfxEmitterInstance a = new CgVfxEmitterInstance(SPARKS, 0.1f), b = new CgVfxEmitterInstance(SPARKS, 0.2f);
        a.start(0f, 0f, 0f);
        b.start(0f, 0f, 0f);
        a.schedule(DT, 0.0, 0.0, 0.0);
        b.schedule(DT, 0.0, 0.0, 0.0);
        first.admit(a);
        try {
            second.admit(b);
            fail("a second system admitted into a pool the first steps");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("another CgVfxSystem"));
        } finally {
            first.clear();
        }
        second.admit(b);
        second.clear();
        assertEquals(0, CgVfxParticlePool.of(SPARKS).openSlots());
    }
}
