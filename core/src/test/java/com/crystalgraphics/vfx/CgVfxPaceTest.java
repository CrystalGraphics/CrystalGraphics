package com.crystalgraphics.vfx;

import com.crystalgraphics.render.stage.CgHostEnvironment;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The effects' clock follows the host's: stopped while paused or frozen, scaled by its tick rate. */
public class CgVfxPaceTest {

    @Test
    public void theClockStopsWhilePausedOrFrozenAndFollowsTheTickRate() {
        assertEquals(1f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 20f, false)), 0f);
        assertEquals(0.25f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 5f, false)), 0f);
        assertEquals(0f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, true, 20f, false)), 0f);
        assertEquals(0f, CgVfxSystem.pace(new CgHostEnvironment().time(0, 0, false, 20f, true)), 0f);
        assertEquals(1f, CgVfxSystem.pace(new CgHostEnvironment()), 0f);
    }
}
