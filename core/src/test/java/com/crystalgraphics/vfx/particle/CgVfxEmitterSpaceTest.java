package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.CgVfxSystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * An effect that moves (follow): its particles stay in the world, unless its emitter rides it. What the pool reads of a
 * step is the instance's origin and its source relative to it.
 */
public class CgVfxEmitterSpaceTest {

    private static final CgVfxEmitter STILL = CgVfxEmitter.builder("still").capacity(64)
            .rate(120f, 0f, Float.POSITIVE_INFINITY).speed(0f, 0f).life(5f, 5f).build();

    /** A step at x 0, then one at x 10. */
    private static CgVfxEmitterInstance stepAcross(CgVfxEmitter emitter) {
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(emitter, 0.37f);
        instance.start(0f, 0f, 0f);
        instance.schedule(CgVfxSystem.TICK, 0.0, 64.0, 0.0);
        instance.schedule(CgVfxSystem.TICK, 10.0, 64.0, 0.0);
        return instance;
    }

    @Test
    public void particlesStayWhereTheySpawned() {
        CgVfxEmitterInstance instance = stepAcross(STILL);
        assertEquals("the origin stays where the effect was", 0.0, instance.originX(), 0.0);
        assertEquals("the source is carried to where the effect is", 10f, instance.sourceX(), 0f);
    }

    @Test
    public void aLocalEmitterRidesTheEffect() {
        CgVfxEmitterInstance instance = stepAcross(STILL.toBuilder().local().build());
        assertEquals(10.0, instance.originX(), 0.0);
        assertEquals(0f, instance.sourceX(), 0f);
    }
}
