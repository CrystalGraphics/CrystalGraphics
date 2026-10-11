package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.CgVfxSystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** A scaled emitter plays the same burst at another size: every particle where the original's is, times the scale. */
public class CgVfxEmitterScaleTest {

    private static final CgVfxEmitter BURST = CgVfxEmitter.builder("burst").capacity(200).burst(0f, 200)
            .shape(1f, 3f).launch(-0.2f, 1f, 1f).speed(4f, 12f).life(5f, 5f).size(0.2f, 0.6f, 1f)
            .module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Drag(0.8f, 0.08f))
            .module(new CgVfxModule.Turbulence(3f, 0.15f, 0.4f))
            .build();

    private static CgVfxParticleSet play(CgVfxEmitter emitter) {
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(emitter, 0.37f);
        instance.start(0f, 0f, 0f);
        CgVfxAir air = new CgVfxAir();
        for (int t = 0; t < 120; t++) instance.tick(CgVfxSystem.TICK, air, 0.0, 0.0, 0.0);
        return instance.particles();
    }

    @Test
    public void aScaledBurstIsTheSameBurstScaled() {
        float scale = 0.375f;
        CgVfxParticleSet full = play(BURST), small = play(BURST.scaled(scale));
        assertEquals(full.count(), small.count());
        for (int i = 0; i < full.count(); i++) {
            float tolerance = 1.0e-3f * (1f + Math.abs(full.x[i]) + Math.abs(full.y[i]) + Math.abs(full.z[i]));
            assertEquals(full.x[i] * scale, small.x[i], tolerance);
            assertEquals(full.y[i] * scale, small.y[i], tolerance);
            assertEquals(full.z[i] * scale, small.z[i], tolerance);
            assertEquals(full.size[i] * scale, small.size[i], 1.0e-5f);
        }
    }

    @Test
    public void oneCopyPerScale() {
        assertSame(BURST, BURST.scaled(1f));
        assertSame(BURST.scaled(0.5f), BURST.scaled(0.5f));
    }
}
