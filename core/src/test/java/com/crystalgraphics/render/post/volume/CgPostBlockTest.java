package com.crystalgraphics.render.post.volume;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
