package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.CgVfxSystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** An effect that moves (follow): its particles stay in the world, unless its emitter rides it. */
public class CgVfxEmitterSpaceTest {

    private static final CgVfxEmitter STILL = CgVfxEmitter.builder("still").capacity(64)
            .rate(120f, 0f, Float.POSITIVE_INFINITY).speed(0f, 0f).life(5f, 5f).build();

    /** World x of the first and the last particle after a step at x 0 and a step at x 10. */
    private static double[] stepAcross(CgVfxEmitter emitter) {
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(emitter, 0.37f);
        instance.start(0f, 0f, 0f);
        CgVfxAir air = new CgVfxAir();
        instance.tick(CgVfxSystem.TICK, air, 0.0, 64.0, 0.0);
        instance.tick(CgVfxSystem.TICK, air, 10.0, 64.0, 0.0);
        CgVfxParticleSet p = instance.particles();
        return new double[]{instance.originX() + p.x[0], instance.originX() + p.x[p.count() - 1]};
    }

    @Test
    public void particlesStayWhereTheySpawned() {
        double[] x = stepAcross(STILL);
        assertEquals("the first stays where the effect was", 0.0, x[0], 1.0e-4);
        assertEquals("the next spawns where the effect is", 10.0, x[1], 1.0e-4);
    }

    @Test
    public void aLocalEmitterRidesTheEffect() {
        double[] x = stepAcross(STILL.toBuilder().local().build());
        assertEquals(10.0, x[0], 1.0e-4);
        assertEquals(10.0, x[1], 1.0e-4);
    }
}
