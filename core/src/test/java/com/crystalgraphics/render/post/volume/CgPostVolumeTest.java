package com.crystalgraphics.render.post.volume;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class CgPostVolumeTest {

    private static CgPostVolume volume(int priority, CgPostSettings settings) {
        return new CgPostVolume(priority, settings, v -> { });
    }

    @Test
    public void weight_fallsOffPastTheRadius_overTheBlendDistance() {
        CgPostVolume v = volume(0, new CgPostSettings().flash(1f)).at(0, 0, 0).radius(10f).blend(10f).weight(0.8f);
        assertEquals(0.8f, v.weightAt(0, 0, 5), 1e-6f);
        assertEquals(0.4f, v.weightAt(15, 0, 0), 1e-6f);
        assertEquals(0f, v.weightAt(25, 0, 0), 1e-6f);
        assertEquals("unplaced: everywhere", 0.8f, volume(0, new CgPostSettings()).weight(0.8f).weightAt(1e6, 0, 0), 1e-6f);
    }

    @Test
    public void blend_movesOnlyWhatAVolumeOverrides_higherPriorityLast() {
        CgPostSettings resolved = new CgPostSettings();
        resolved.reset();
        resolved.blend(new CgPostSettings().vignette(0.2f).bloom(2f), 1f, 0.5f, 0.5f);
        resolved.blend(new CgPostSettings().vignette(1f), 0.5f, 0.5f, 0.5f);
        assertEquals(0.6f, resolved.vignette(), 1e-6f);
        assertEquals("left alone by the volume that does not set it", 2f, resolved.bloom(), 1e-6f);
        assertEquals("neutral where nothing sets it", 0f, resolved.flash(), 0f);
    }

    @Test
    public void focus_ofOneFadingVolume_staysItsOwn() {
        CgPostSettings resolved = new CgPostSettings();
        resolved.reset();
        resolved.blend(new CgPostSettings().impact(CgImpact.LINES, 1f), 0.2f, 0.8f, 0.3f);
        assertEquals(0.8f, resolved.focusX(), 1e-6f);
        assertEquals(0.3f, resolved.focusY(), 1e-6f);
        resolved.blend(new CgPostSettings().chromatic(1f), 0.6f, 0.2f, 0.7f);
        assertEquals("weighted by 0.2 and 0.6", 0.35f, resolved.focusX(), 1e-6f);
    }

    @Test
    public void impactLook_isTheHeavierVolumes() {
        CgPostSettings resolved = new CgPostSettings();
        resolved.reset();
        resolved.blend(new CgPostSettings().impact(CgImpact.LINES, 1f), 0.3f, 0.5f, 0.5f);
        resolved.blend(new CgPostSettings().impact(CgImpact.INVERT, 1f), 0.9f, 0.5f, 0.5f);
        assertSame(CgImpact.INVERT.frame(), resolved.impactLook());
    }
}
