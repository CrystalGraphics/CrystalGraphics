package com.crystalgraphics.vfx.particle.gpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** The asset format's event triggers, onto the GPU's. */
public class CgVfxEventSpecTest {

    @Test
    public void eachSpecTriggerIsAGpuTrigger() {
        assertEquals(CgVfxEvent.Trigger.AGE, CgVfxEvent.ofSpec("spawn", 0f).trigger());
        assertEquals(0f, CgVfxEvent.ofSpec("spawn", 0f).age(), 0f);
        assertEquals(CgVfxEvent.Trigger.DEATH, CgVfxEvent.ofSpec("death", 0f).trigger());
        assertEquals(CgVfxEvent.Trigger.COLLISION, CgVfxEvent.ofSpec("collision", 0f).trigger());
        assertEquals(CgVfxEvent.Trigger.RATE, CgVfxEvent.ofSpec("location", 0.1f).trigger());
        assertEquals(0.1f, CgVfxEvent.ofSpec("location", 0.1f).age(), 0f);
        assertThrows(IllegalArgumentException.class, () -> CgVfxEvent.ofSpec("burst", 0f));
    }
}
