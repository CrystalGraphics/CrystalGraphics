package com.crystalgraphics.vfx.particle.gpu.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.world.CgCharacterClock;

/**
 * A frozen character's particles hold still and unaged: its slots keep their instance rows with a step scale of 0 and
 * queue no spawns, while another character's slots in the same pools step at 1.
 */
public class CgVfxHeldSlotTest {

    /** The step scale's word in an instance row: the third vector's z. */
    private static final int STEP_SCALE = 10;

    private CgVfxSystem vfx;

    @After
    public void clear() {
        if (vfx != null) vfx.delete();
    }

    @Test
    public void aFrozenCharactersSlotsHoldWhileAnothersStep() {
        vfx = new CgVfxSystem();
        CgCharacterClock frozen = new CgCharacterClock(), free = new CgCharacterClock();
        vfx.play(new CgVfxUpdateAllocationTest.Fountain(0).clock(frozen));
        vfx.play(new CgVfxUpdateAllocationTest.Fountain(8).clock(free));
        List<CgVfxParticlePool> pools = CgVfxParticlePool.pools();
        double clock = 0;
        for (int f = 0; f < 30; f++) {
            vfx.update(clock += 1.0 / 60.0);
            for (int p = 0; p < pools.size(); p++) pools.get(p).takeSteps();
        }
        frozen.freeze(1000);
        int held = 0, stepping = 0;
        for (int f = 0; f < 30; f++) {
            vfx.update(clock += 1.0 / 60.0);
            for (int p = 0; p < pools.size(); p++) {
                CgVfxParticlePool pool = pools.get(p);
                int[] rows = pool.instanceWords(), spawns = pool.spawnWords();
                int width = rowWords(pool);
                for (int s = 0; s < pool.queuedSteps(); s++) {
                    for (int slot = 0; slot < pool.stepSlots(s); slot++) {
                        int at = pool.stepInstanceAt(s) + slot * width;
                        if (rows[at + 2] != slot) continue;                       // a slot with no row this step
                        float scale = Float.intBitsToFloat(rows[at + STEP_SCALE]);
                        boolean spawned = false;
                        for (int r = 0; r < pool.stepSpawnRows(s); r++) {
                            if (spawns[pool.stepSpawnAt(s) + r * CgVfxParticlePool.SPAWN_ROW_WORDS] == slot) spawned = true;
                        }
                        if (scale == 0f) {
                            held++;
                            assertTrue("a held slot spawns nothing", !spawned);
                        } else {
                            assertEquals(1f, scale, 0f);
                            stepping++;
                        }
                    }
                }
                pool.takeSteps();
            }
        }
        assertTrue("held rows " + held, held > 0);
        assertTrue("stepping rows " + stepping, stepping > 0);
    }

    private static int rowWords(CgVfxParticlePool pool) {
        return pool.shape().instanceRowVectors() * 4;
    }
}
