package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The asset format's event triggers onto the GPU's, and P0's limits: levels of children, children an event. */
public class CgVfxEventSpecTest {

    @Test
    public void eachSpecTriggerIsAGpuTriggerAndBack() {
        assertEquals(CgVfxEvent.Trigger.AGE, CgVfxEvent.ofSpec("spawn").trigger());
        assertEquals(CgVfxEvent.Trigger.DEATH, CgVfxEvent.ofSpec("death").trigger());
        assertEquals(CgVfxEvent.Trigger.COLLISION, CgVfxEvent.ofSpec("collision").trigger());
        assertEquals(CgVfxEvent.Trigger.LOCATION, CgVfxEvent.ofSpec("location").trigger());
        assertEquals(CgVfxEvent.Trigger.BURST, CgVfxEvent.ofSpec("burst").trigger());
        for (String t : new String[]{"spawn", "death", "collision", "location", "burst"}) {
            assertEquals(t, CgVfxEvent.ofSpec(t).specTrigger());
        }
        assertNull(CgVfxEvent.onAge(0.5f).specTrigger());
        assertNull(CgVfxEvent.every(0.1f).specTrigger());
        assertNull(CgVfxEvent.onCollision().firings(2).specTrigger());
        assertThrows(IllegalArgumentException.class, () -> CgVfxEvent.ofSpec("rate"));
        assertEquals(CgVfxEvent.MAX_PER_FRAME, CgVfxEvent.onDeath().maxPerFrame());
    }

    @Test
    public void threeLevelsOfChildrenAndNoMore() {
        CgVfxEmitter leaf = emitter("leaf").build();
        CgVfxEmitter third = emitter("third").event(CgVfxEvent.onDeath().spawn(leaf, 1)).build();
        CgVfxEmitter second = emitter("second").event(CgVfxEvent.onDeath().spawn(third, 1)).build();
        CgVfxEmitter first = emitter("first").event(CgVfxEvent.onDeath().spawn(second, 1)).build();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CgVfxEvent.onDeath().spawn(first, 1));
        assertTrue(e.getMessage(), e.getMessage().contains("3 levels"));
    }

    @Test
    public void aThousandChildrenAnEventEachKeyedApart() {
        CgVfxEmitter child = emitter("child").build();
        CgVfxEvent most = CgVfxEvent.onCollision().spawn(child, 3, CgVfxEvent.MAX_CHILDREN);
        assertEquals(3, most.countMin());
        assertEquals(CgVfxEvent.MAX_CHILDREN, most.count());
        assertThrows(IllegalArgumentException.class, () -> CgVfxEvent.onDeath().spawn(child, CgVfxEvent.MAX_CHILDREN + 1));
        Set<Integer> keys = new HashSet<>();
        for (int e = 0; e < CgVfxEvent.MAX_EVENTS; e++) {
            for (int i = 0; i <= CgVfxEvent.MAX_CHILDREN; i++) keys.add(CgVfxEvent.childKey(123456, e, i));
        }
        assertEquals(CgVfxEvent.MAX_EVENTS * (CgVfxEvent.MAX_CHILDREN + 1), keys.size());
    }

    @Test
    public void aRolledCountStaysInItsRange() {
        assertEquals(4, CgVfxEvent.rolledCount(4, 4, 0.7f));
        assertEquals(3, CgVfxEvent.rolledCount(3, 5, 0f));
        assertEquals(5, CgVfxEvent.rolledCount(3, 5, 0.99999994f));
        assertEquals(4, CgVfxEvent.rolledCount(3, 5, 0.5f));
    }

    private static CgVfxEmitter.Builder emitter(String name) {
        return CgVfxEmitter.builder(name).state(CgVfxEmitterState.ONCE).capacity(16).burst(0f, 1).life(1f, 1f);
    }
}
