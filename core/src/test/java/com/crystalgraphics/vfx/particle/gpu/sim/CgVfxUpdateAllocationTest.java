package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.trace.CgAllocationProof;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import org.junit.After;
import org.junit.Test;

import java.util.List;

/**
 * T5: {@link CgVfxSystem#update} allocates nothing per frame while effects play: their ticks, the emitters' books, the
 * admission into GPU slots and the queued steps. The world's recording is stood in for by taking each pool's steps, as
 * {@code CgVfxParticlePool.record} does once a frame.
 */
public class CgVfxUpdateAllocationTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSchema.of("test:update_allocation")).build();
    private static final CgVfxEmitter SPARKS = CgVfxEmitter.builder("alloc_sparks").capacity(512)
            .rate(200f).burst(0.5f, 20).life(0.4f, 0.8f).speed(1f, 4f)
            .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Drag(0.5f, 0f)).build();
    private static final CgVfxEmitter SMOKE = CgVfxEmitter.builder("alloc_smoke").capacity(256)
            .ratePerBlock(4f).life(1f, 2f).speed(0.2f, 0.6f).module(new CgVfxModule.Buoyancy(2f, 0.5f)).build();

    /** An effect that keeps two emitters running and moves one of them, for ever. */
    static final class Fountain extends CgVfxEffect<Fountain> {
        final CgVfxEmitterInstance sparks = new CgVfxEmitterInstance(SPARKS, 0.21f);
        final CgVfxEmitterInstance smoke = new CgVfxEmitterInstance(SMOKE, 0.73f);

        Fountain(double x) {
            super(LOOK, x, 64, 0);
            sparks.start(0f, 1f, 0f);
            smoke.start(0f, 0f, 0f);
        }

        @Override
        protected void tick(float dt) {
            smoke.moveTo((float) Math.sin(age * 3f) * 2f, 0f, (float) Math.cos(age * 3f) * 2f);
            tick(sparks, dt);
            tick(smoke, dt);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    private CgVfxSystem vfx;

    @After
    public void clear() {
        if (vfx != null) vfx.delete();
    }

    @Test
    public void updatingPlayingEffectsAllocatesNothing() {
        vfx = new CgVfxSystem();
        for (int i = 0; i < 6; i++) vfx.play(new Fountain(i * 4));
        double[] clock = {0.0};
        List<CgVfxParticlePool> pools = CgVfxParticlePool.pools();   // a live view: asked once, its wrapper is no frame's
        Runnable frame = () -> {
            clock[0] += 1.0 / 60.0;
            vfx.update(clock[0]);
            for (int p = 0; p < pools.size(); p++) pools.get(p).takeSteps();
        };
        CgAllocationProof.assertZero("CgVfxSystem.update", 3_000, 1_000, frame);
    }
}
