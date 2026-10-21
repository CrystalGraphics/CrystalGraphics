package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A system instance: its emitters, its execution state by source, its behaviour as a component. */
public class CgVfxSystemInstanceTest {

    private static final CgVfxEmitter PUFF = CgVfxEmitter.builder("sys_puff").state(CgVfxEmitterState.ONCE).capacity(64)
            .burst(0f, 20).life(0.2f, 0.3f).speed(1f, 2f).build();
    private static final CgVfxEmitter AURA = CgVfxEmitter.builder("sys_aura").capacity(256).rate(100f).life(0.5f, 1f).build();
    private static final CgVfxLook PUFF_LOOK = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(PUFF).build();
    private static final CgVfxLook AURA_LOOK = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(AURA).build();

    private CgVfxSystem vfx;
    private double clock;

    @After
    public void clear() {
        if (vfx != null) vfx.delete();
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            clock += 1.0 / 60.0;
            vfx.update(clock);
            // The world's recording, which a headless system has none of.
            for (CgVfxParticlePool pool : CgVfxParticlePool.pools()) pool.discardQueued();
        }
    }

    @Test
    public void aLookAloneIsABurstThatCompletes() {
        vfx = new CgVfxSystem();
        CgVfxSystemInstance<?> puff = vfx.play(new CgVfxSystemInstance<>(PUFF_LOOK, 0, 64, 0));
        assertSame(CgVfxSystemDefinition.of(PUFF_LOOK), puff.definition());
        assertNotNull(puff.emitter("sys_puff"));
        assertEquals(CgVfxExecutionState.ACTIVE, puff.executionState());
        frames(120);
        assertEquals(CgVfxExecutionState.COMPLETE, puff.executionState());
        assertEquals(CgVfxEffect.State.DEAD, puff.state());
    }

    @Test
    public void aScalabilityCullNeverOverridesItsOwner() {
        vfx = new CgVfxSystem();
        CgVfxSystemInstance<?> aura = vfx.play(new CgVfxSystemInstance<>(AURA_LOOK, 0, 64, 0));
        frames(3);
        aura.stop();
        assertEquals(CgVfxExecutionState.INACTIVE, aura.executionState());
        assertEquals(CgVfxExecutionSource.OWNER, aura.executionSource());
        assertFalse(aura.request(CgVfxExecutionState.ACTIVE, CgVfxExecutionSource.SCALABILITY));
        assertFalse(aura.request(CgVfxExecutionState.INACTIVE_CLEAR, CgVfxExecutionSource.SCALABILITY));
        assertEquals(CgVfxEffect.State.STOPPING, aura.state());
        // Stopped, its open rate finishes and it completes on its own.
        frames(120);
        assertEquals(CgVfxExecutionState.COMPLETE, aura.executionState());
        assertEquals(CgVfxExecutionSource.COMPLETION, aura.executionSource());
    }

    @Test
    public void aCullBeforeItsOwnerSpeaksIsHeardAndAStoppedOneNeverRestarts() {
        vfx = new CgVfxSystem();
        CgVfxSystemInstance<?> aura = vfx.play(new CgVfxSystemInstance<>(AURA_LOOK, 0, 64, 0));
        assertTrue(aura.request(CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.SCALABILITY));
        assertFalse(aura.request(CgVfxExecutionState.ACTIVE, CgVfxExecutionSource.OWNER));
        assertTrue(aura.request(CgVfxExecutionState.INACTIVE_CLEAR, CgVfxExecutionSource.OWNER));
        assertEquals(CgVfxExecutionState.COMPLETE, aura.executionState());
        assertThrows(IllegalArgumentException.class,
                () -> new CgVfxSystemInstance<>(AURA_LOOK, 0, 0, 0).request(CgVfxExecutionState.ASLEEP, CgVfxExecutionSource.OWNER));
    }

    @Test
    public void aBehaviourTicksAsAComponentAndHoldsItsInstanceUntilDone() {
        vfx = new CgVfxSystem();
        int[] ticks = {0}, deactivations = {0};
        boolean[] done = {false};
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:held", PUFF_LOOK).withBehaviour(in -> new CgVfxBehaviour() {
            public void tick(CgVfxSystemInstance<?> instance, float dt) {
                ticks[0]++;
            }

            public void deactivated(CgVfxSystemInstance<?> instance) {
                deactivations[0]++;
            }

            public boolean done(CgVfxSystemInstance<?> instance) {
                return done[0];
            }
        });
        CgVfxSystemInstance<?> held = vfx.play(new CgVfxSystemInstance<>(def, 0, 64, 0));
        frames(120);
        assertTrue(ticks[0] > 100);
        assertEquals(CgVfxExecutionState.ACTIVE, held.executionState());   // its emitters finished; its behaviour has not
        held.stop();
        held.stop();
        assertEquals(1, deactivations[0]);
        done[0] = true;
        frames(2);
        assertEquals(CgVfxExecutionState.COMPLETE, held.executionState());
    }
}
