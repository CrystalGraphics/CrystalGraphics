package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.vfx.CgVfxSystem;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgVfxEmitterShareTest {

    private static final CgVfxEmitter BURST = CgVfxEmitter.builder("burst").capacity(2000).burst(0f, 2000).build();

    private static CgVfxParticleSet spawn(float share) {
        CgVfxEmitterInstance instance = new CgVfxEmitterInstance(BURST, 0.37f).share(share);
        instance.start(0f, 0f, 0f);
        instance.tick(CgVfxSystem.TICK, new CgVfxAir(), 0.0, 0.0, 0.0);
        return instance.particles();
    }

    private static Set<Float> seeds(CgVfxParticleSet p) {
        Set<Float> seeds = new HashSet<>();
        for (int i = 0; i < p.count(); i++) seeds.add(p.seed[i]);
        return seeds;
    }

    @Test
    public void theShareSpawnsThatShare() {
        assertEquals(2000, spawn(1f).count());
        assertEquals(0, spawn(0f).count());
        int half = spawn(0.5f).count();
        assertTrue("half spawned " + half, half > 900 && half < 1100);
    }

    @Test
    public void aSparserBurstKeepsTheSameParticles() {
        Set<Float> all = seeds(spawn(1f)), half = seeds(spawn(0.5f)), quarter = seeds(spawn(0.25f));
        assertTrue(all.containsAll(half));
        assertTrue(half.containsAll(quarter));
        assertEquals(half, seeds(spawn(0.5f)));
    }

    @Test
    public void theClockStopsWhilePausedOrFrozenAndFollowsTheTickRate() {
        assertEquals(1f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 20f, false)), 0f);
        assertEquals(0.25f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 5f, false)), 0f);
        assertEquals(0f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, true, 20f, false)), 0f);
        assertEquals(0f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 20f, true)), 0f);
        assertEquals(1f, CgVfxSystem.pace(new CgHostEnvironment()), 0f);
    }
}
