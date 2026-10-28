package com.crystalgraphics.vfx.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.cue.CgCueDefinition.Lens;
import com.crystalgraphics.cue.CgCueDefinition.Where;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostVolume;

/** A looping cue's lens as the post volume it opens: where it reaches, and what it sets. */
public class CgVfxCueLensTest {

    private static CgPostVolume open(boolean inWorld, float inner, float outer) {
        return CgVfxCueEffects.openLens(CgPostStack.get(), new Lens(Map.of("vignette", 0.5), inWorld, inner, outer, Where.DEFAULT),
                10, 0, 0);
    }

    @Test
    public void inTheWorld_fullWithinInner_goneByOuter() {
        try (CgPostVolume v = open(true, 4f, 12f)) {
            assertEquals(0.5f, v.settings().vignette(), 1e-6f);
            assertEquals(1f, v.weightAt(13, 0, 0), 1e-6f);
            assertEquals(0.5f, v.weightAt(18, 0, 0), 1e-6f);
            assertEquals(0f, v.weightAt(23, 0, 0), 1e-6f);
        }
    }

    @Test
    public void anOuterNotPastInner_isACliffAtInner() {
        try (CgPostVolume v = open(true, 4f, 2f)) {
            assertEquals(1f, v.weightAt(13.9, 0, 0), 1e-6f);
            assertEquals(0f, v.weightAt(14.1, 0, 0), 1e-6f);
        }
    }

    @Test
    public void withNoReach_orOutOfTheWorld_itIsEverywhere() {
        try (CgPostVolume world = open(true, 0f, 0f); CgPostVolume screen = open(false, 4f, 12f)) {
            assertFalse(world.placed());
            assertFalse(screen.placed());
            assertTrue(screen.weightAt(1e6, 0, 0) > 0f);
        }
    }
}
