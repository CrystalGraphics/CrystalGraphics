package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        CgVfxParticlePool pool = CgVfxParticlePool.of(steps, SPARKS);
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
    public void aSpawningEventsChildrenHoldAFedSlotUntilTheirParentFinishes() {
        CgVfxEmitter dust = CgVfxEmitter.builder("dust").life(0.5f, 0.8f).speed(0f, 1f).build();
        // Spawns late: a child left to finish alone would close before the first death fires.
        CgVfxEmitter flares = CgVfxEmitter.builder("flares").capacity(400).burst(1.5f, 60).life(0.3f, 0.6f)
                .speed(2f, 4f).event(CgVfxEvent.onDeath().spawn(dust, 2)).build();
        CgVfxGpuSteps steps = new CgVfxGpuSteps();
        CgVfxAir air = new CgVfxAir().wind(0f, 0f, 0f);
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(flares, 0.6f);
        instance.start(0f, 0f, 0f);
        CgVfxParticlePool parents = CgVfxParticlePool.of(steps, flares), children = CgVfxParticlePool.of(steps, dust);
        int stepped = 0;
        for (int n = 0; n < 600 && (n == 0 || !steps.isEmpty()); n++) {
            instance.schedule(DT, 0.0, 0.0, 0.0);
            if (n == 0) {
                steps.admit(instance);
                assertEquals(flares.peakChildren(0), children.capacity(steps.of(instance.child(0)).slot));
            }
            assertEquals(1, parents.openSlots());
            assertEquals(1, children.openSlots());
            steps.step(DT, air);
            stepped++;
        }
        assertTrue(instance.finished());
        assertNull(steps.of(instance.child(0)));
        assertEquals(0, parents.openSlots());
        assertEquals(0, children.openSlots());
        // The last flare dies by 2.1 s and its dust a step after 0.8 s more.
        assertTrue("finished after " + stepped + " steps", stepped >= Math.floor(2.1f / DT) && stepped <= Math.ceil(2.9f / DT) + 3);
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
        assertEquals(0, CgVfxParticlePool.of(steps, EMBERS).openSlots());
    }
}
