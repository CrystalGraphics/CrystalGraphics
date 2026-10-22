package com.crystalgraphics.vfx.system;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.sun.management.ThreadMXBean;
import org.junit.After;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A system's instance pool: taken, played, handed back once its slots close, kept under its max, primed. */
public class CgVfxSystemInstancePoolTest {

    private static final CgVfxEmitter PUFF = CgVfxEmitter.builder("pool_puff").state(CgVfxEmitterState.ONCE).capacity(64)
            .burst(0f, 20).life(0.2f, 0.3f).speed(1f, 2f).build();
    private static final CgVfxLook LOOK = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).emitter(PUFF).build();

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
            List<CgVfxParticlePool> pools = CgVfxParticlePool.pools();
            for (int k = 0; k < pools.size(); k++) pools.get(k).discardQueued();
        }
    }

    @Test
    public void aCompletedInstanceGoesBackAndPlaysAgainFromItsStart() {
        vfx = new CgVfxSystem();
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:pooled", LOOK);
        CgVfxSystemInstance<?> first = vfx.spawn(def, 0, 64, 0);
        frames(120);
        assertEquals(CgVfxEffect.State.DEAD, first.state());
        assertEquals(1, vfx.pool(def).idle());

        CgVfxSystemInstance<?> again = vfx.spawn(def, 5, 64, 0);
        assertSame(first, again);
        assertEquals(0, vfx.pool(def).idle());
        assertEquals(CgVfxExecutionState.ACTIVE, again.executionState());
        assertEquals(0f, again.age(), 0f);
        assertEquals(5.0, again.originX(), 0.0);
        assertEquals(0f, again.emitter(0).time(), 0f);   // started again, its books empty
        // Its emitters' books start over, so it completes again rather than at once.
        frames(2);
        assertEquals(CgVfxEffect.State.PLAYING, again.state());
        frames(120);
        assertEquals(CgVfxEffect.State.DEAD, again.state());
        assertEquals(1, vfx.pool(def).idle());
    }

    @Test
    public void playingAgainFromThePoolAllocatesNothing() {
        vfx = new CgVfxSystem();
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:replayed", LOOK);
        // Warm: the classes, the JIT, the pools' and the lists' first growth.
        for (int i = 0; i < 20; i++) {
            vfx.spawn(def, 0, 64, 0);
            frames(120);
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId(), mark = threads.getThreadAllocatedBytes(thread);
        frames(120);
        long idle = threads.getThreadAllocatedBytes(thread) - mark;   // the frames' own: pools()'s view each frame
        mark = threads.getThreadAllocatedBytes(thread);
        vfx.spawn(def, 0, 64, 0);
        frames(120);
        long replay = threads.getThreadAllocatedBytes(thread) - mark;
        assertEquals(1, vfx.pool(def).idle());
        assertEquals("bytes a replay from the pool allocates past idle frames, spawn to hand back", 0, replay - idle);
    }

    @Test
    public void aKilledInstanceGoesBackOnceItsSlotsClose() {
        vfx = new CgVfxSystem();
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:killed", LOOK);
        CgVfxSystemInstance<?> puff = vfx.spawn(def, 0, 64, 0);
        frames(3);
        puff.kill();
        frames(1);
        assertEquals(CgVfxEffect.State.DEAD, puff.state());
        frames(2);
        assertEquals(1, vfx.pool(def).idle());
    }

    @Test
    public void itKeepsNoMoreThanItsMaxAndPrimesUpToIt() {
        vfx = new CgVfxSystem();
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:capped", LOOK).withPool(0, 2);
        CgVfxSystemInstance<?> a = vfx.spawn(def, 0, 64, 0), b = vfx.spawn(def, 0, 64, 0), c = vfx.spawn(def, 0, 64, 0);
        assertNotSame(a, b);
        assertNotSame(b, c);
        frames(120);
        assertEquals(2, vfx.pool(def).idle());

        CgVfxSystemDefinition primed = CgVfxSystemDefinition.of("test:primed", LOOK).withPool(1, 3);
        vfx.pool(primed).prime(8);
        assertEquals(3, vfx.pool(primed).idle());
        vfx.spawn(primed, 0, 64, 0);
        assertEquals(2, vfx.pool(primed).idle());
    }

    @Test
    public void aClearHandsEveryPooledInstanceBack() {
        vfx = new CgVfxSystem();
        CgVfxSystemDefinition def = CgVfxSystemDefinition.of("test:cleared", LOOK);
        vfx.spawn(def, 0, 64, 0);
        vfx.spawn(def, 0, 64, 0);
        frames(3);
        vfx.clear();
        assertEquals(2, vfx.pool(def).idle());
        assertTrue(vfx.spawn(def, 0, 64, 0).executionState() == CgVfxExecutionState.ACTIVE);
    }
}
