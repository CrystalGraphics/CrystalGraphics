package com.crystalgraphics.vfx.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.cue.CgCueDefinition.Lens;
import com.crystalgraphics.cue.CgCueDefinition.Where;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostVolume;

/** A looping cue's lens: who is within its reach as it starts, and the volume it opens for them. */
public class CgVfxCueLensTest {

    @Test
    public void reach_fallsFromInnerToOuter() {
        assertEquals(1f, CgVfxCueEffects.falloff(3, 4f, 12f), 1e-6f);
        assertEquals(0.5f, CgVfxCueEffects.falloff(8, 4f, 12f), 1e-6f);
        assertEquals(0f, CgVfxCueEffects.falloff(12, 4f, 12f), 1e-6f);
    }

    @Test
    public void anOuterNotPastInner_isACliffAtInner_andNoReachIsEverywhere() {
        assertEquals(1f, CgVfxCueEffects.falloff(3.9, 4f, 2f), 0f);
        assertEquals(0f, CgVfxCueEffects.falloff(4.1, 4f, 2f), 0f);
        assertEquals(1f, CgVfxCueEffects.falloff(1e6, 0f, 0f), 0f);
    }

    @Test
    public void aPlayingLens_isItsLookEverywhere_atFullWeight() {
        Lens lens = new Lens(Map.of("vignette", 0.5), true, 4f, 12f, Where.DEFAULT);
        try (CgPostVolume v = CgVfxCueEffects.openLens(CgPostStack.get(), lens)) {
            assertFalse(v.placed());
            assertEquals(0.5f, v.settings().vignette(), 1e-6f);
            assertEquals(1f, v.weightAt(1e6, 0, 0), 0f);
        }
    }
}
