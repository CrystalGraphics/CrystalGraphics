package com.crystalgraphics.vfx.look;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CgVfxTuningTest {

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.6f);
    private static final CgVfxParam CORE = SCHEMA.color("core", 1f, 0.5f, 0.25f, 1f);

    @Test
    public void aTunedValueReachesTheLookAndEveryPlayingCopy() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxValues playing = look.copyValues();
        playing.set(RADIUS, 2f);
        try (CgVfxTuning ignored = CgVfxTuning.open("test.tune1", look)) {
            assertTrue(CgVars.get("test.tune1.radius").setText(CgVar.Source.CONSOLE, "0.9"));
            assertTrue(CgVars.get("test.tune1.core.g").setText(CgVar.Source.CONSOLE, "0.8"));

            assertEquals(0.9f, look.get(RADIUS), 0f);
            assertEquals(2f, playing.get(RADIUS), 0f);     // until its next tick
            playing.takeTuning();
            assertEquals(0.9f, playing.get(RADIUS), 0f);
            assertEquals(0.8f, playing.get(CORE, 1), 0f);
            assertEquals(1f, playing.get(CORE, 0), 0f);    // untouched components stay
            assertEquals(0.9f, look.copyValues().get(RADIUS), 0f);
        }
    }

    @Test
    public void clearingPutsBackTheOpenedValueAndClosingUnregisters() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxTuning tuning = CgVfxTuning.open("test.tune2", look);
        CgVar<?> radius = CgVars.get("test.tune2.radius");
        radius.setText(CgVar.Source.CONSOLE, "3");
        radius.clear(CgVar.Source.CONSOLE);
        assertEquals(0.6f, look.get(RADIUS), 0f);

        radius.setText(CgVar.Source.CONSOLE, "3");
        tuning.close();
        assertNull(CgVars.get("test.tune2.radius"));
        assertEquals(3f, look.get(RADIUS), 0f);
        CgVfxTuning.open("test.tune2", look).close();      // the prefix is free again
    }

    @Test
    public void aCopyMadeWithCopyFollowsNothing() {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).build();
        CgVfxValues detached = look.copyValues().copy();
        try (CgVfxTuning ignored = CgVfxTuning.open("test.tune3", look)) {
            CgVars.get("test.tune3.radius").setText(CgVar.Source.CONSOLE, "5");
            detached.takeTuning();
            assertEquals(0.6f, detached.get(RADIUS), 0f);
        }
    }
}
