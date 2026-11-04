package com.crystalgraphics.render.post.volume;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.render.post.volume.CgPostSettings.Control;
import com.crystalgraphics.render.post.volume.CgPostSettings.Region;

/** Override bits per field: two volumes overriding different fields both apply in full; the block round-trips. */
public class CgPostBlockTest {

    private static Map<String, Object> block(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    public void volumesOverridingDifferentFieldsBothApplyInFull() {
        CgPostSettings glow = CgPostBlock.settings(block("bloom", 2.0));
        CgPostSettings dark = CgPostBlock.settings(block("vignette", 0.6));
        CgPostSettings out = new CgPostSettings();
        out.blend(glow, 1f, 0.5f, 0.5f);
        out.blend(dark, 1f, 0.5f, 0.5f);
        assertEquals(2f, out.bloom(), 1e-6f);
        assertEquals("the later volume left bloom alone", 0.6f, out.vignette(), 1e-6f);
        assertEquals(0f, out.flash(), 0f);
        assertFalse(out.overrides(CgPostSettings.FLASH));
        out.reset();
        out.blend(dark, 1f, 0.5f, 0.5f);
        out.blend(glow, 0.5f, 0.5f, 0.5f);
        assertEquals("a half-weight volume moves only its own field", 0.6f, out.vignette(), 1e-6f);
        assertEquals(1.5f, out.bloom(), 1e-6f);
    }

    @Test
    public void theBlockRoundTripsExactly() {
        Map<String, Object> b = block("bloom", 2.0, "exposure", block("flash", 1.5), "chromatic", 0.25,
                "white_balance", block("temperature", 5000.0, "tint", 0.25));
        CgPostSettings s = CgPostBlock.settings(b);
        assertTrue(s.overrides(CgPostSettings.BLOOM) && s.overrides(CgPostSettings.FLASH));
        assertFalse(s.overrides(CgPostSettings.VIGNETTE));
        assertEquals(b, CgPostBlock.block(s));
        assertEquals(block(), CgPostBlock.block(CgPostBlock.settings(block())));
    }

    @Test
    public void theTonemapperAndTheBloomLayersRoundTrip_eachKeyItsOwnField() {
        Map<String, Object> b = block("tonemapper", "film", "tone_curve", 0.0, "film", block("slope", 0.75, "white_clip", 0.0625),
                "film_legacy", block("contrast", 0.25, "heal_amount", 0.5), "blue_correction", 0.0, "expand_gamut", 0.0,
                "display_gamma", 2.5, "bloom_method", "sog", "bloom_threshold", -1.0, "bloom_size_scale", 4.0,
                "bloom1", block("size", 0.25, "tint", List.of(0.5, 0.25, 0.125)), "bloom6", block("size", 64.0));
        CgPostSettings s = CgPostBlock.settings(b);
        assertTrue(s.tonemapsFilmic() && s.bloomsGaussian());
        assertTrue(s.overrides(CgPostSettings.FILM_SLOPE) && !s.overrides(CgPostSettings.FILM_SHOULDER));
        assertEquals("a key alone keeps its curve's other settings", 0.26f, s.filmShoulder(), 0f);
        assertTrue(s.overridesLayer(6, false) && !s.overridesLayer(6, true) && !s.overridesLayer(2, false));
        assertEquals(b, CgPostBlock.block(s));
        // A layer key alone draws the Gaussian bloom; a method not drawn yet fails by name
        assertTrue(CgPostBlock.settings(block("bloom2", block("size", 1.0))).bloomsGaussian());
        assertFalse(CgPostBlock.settings(block("bloom", 2.0)).bloomsGaussian());
        assertThrows(IllegalArgumentException.class, () -> CgPostBlock.settings(block("bloom_method", "cc2")));
    }

    @Test
    public void bloomLayersBlendByWeight_aSizeAndATintApart() {
        CgPostSettings out = new CgPostSettings();
        out.blend(new CgPostSettings().gaussianBloom().bloomSize(1, 1f), 0.5f, 0.5f, 0.5f);
        assertEquals("halfway from Unreal's 0.3", 0.65f, out.bloomSize(1), 1e-6f);
        assertEquals("its tint left Unreal's", 0.3465f, out.bloomTint(1, 0), 1e-6f);
        out.reset();
        assertEquals(0.3f, out.bloomSize(1), 0f);
        assertFalse(out.bloomsGaussian());
    }

    @Test
    public void theGradeRoundTripsExactly_eachControlItsOwnField() {
        Map<String, Object> b = block("colour", block("saturation", List.of(1.0, 1.0, 1.0, 0.75), "tint", List.of(1.0, 0.5, 0.25)),
                "shadows", block("gain", List.of(0.5, 0.75, 1.25, 1.0)), "highlights", block("offset", List.of(0.0, 0.0, 0.125, 0.0)));
        CgPostSettings s = CgPostBlock.settings(b);
        assertTrue(s.grades(Region.SHADOWS, Control.GAIN));
        assertFalse(s.grades(Region.SHADOWS, Control.GAMMA));
        assertFalse(s.grades(Region.MIDTONES, Control.GAIN));
        assertEquals(1.25f, s.grade(Region.SHADOWS, Control.GAIN, 2), 0f);
        assertEquals(b, CgPostBlock.block(s));
        assertThrows(IllegalArgumentException.class, () -> CgPostBlock.settings(block("midtones", block("gamma", List.of(1.0, 1.0)))));
    }
}
