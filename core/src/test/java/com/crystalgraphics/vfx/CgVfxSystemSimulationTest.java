package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** One simulation per definition playing: instances of one share it, it goes with the last. */
public class CgVfxSystemSimulationTest {

    private static final CgVfxEmitter PUFF = CgVfxEmitter.builder("sim_puff").state(CgVfxEmitterState.ONCE).capacity(64)
            .burst(0f, 10).life(0.2f, 0.3f).build();
    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(PUFF).build();
    private static final CgVfxSystemDefinition A = CgVfxSystemDefinition.of("test:a", LOOK),
            B = CgVfxSystemDefinition.of("test:b", LOOK);

    private CgVfxSystem vfx;

    @After
    public void clear() {
        if (vfx != null) vfx.delete();
    }

    @Test
    public void instancesOfOneDefinitionShareASimulationGoneWithTheLast() {
        vfx = new CgVfxSystem();
        CgVfxSystemInstance a1 = vfx.play(new CgVfxSystemInstance(A, 0, 64, 0));
        CgVfxSystemInstance a2 = vfx.play(new CgVfxSystemInstance(A, 4, 64, 0));
        CgVfxSystemInstance b = vfx.play(new CgVfxSystemInstance(B, 8, 64, 0));
        assertSame(vfx.simulation(a1), vfx.simulation(a2));
        // One look, two definitions: two simulations.
        assertNotSame(vfx.simulation(a1), vfx.simulation(b));
        assertEquals(2, vfx.simulations());
        assertEquals(2, vfx.simulation(a1).instances());
        assertSame(A, vfx.simulation(a1).key());

        frame(0.0);   // the clock's start
        a1.kill();
        frame(1.0 / 60.0);
        assertEquals(1, vfx.simulation(a2).instances());
        assertSame(a2, vfx.simulation(a2).instance(0));
        a2.kill();
        b.kill();
        frame(2.0 / 60.0);
        assertEquals(0, vfx.simulations());
    }

    private void frame(double seconds) {
        vfx.update(seconds);
        for (CgVfxParticlePool pool : CgVfxParticlePool.pools()) pool.discardQueued();
    }
}
