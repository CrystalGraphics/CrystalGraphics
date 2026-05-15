package com.crystalgraphics.api.material;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgRenderQueue} constant values and {@code fromName()} lookup.
 *
 * <p>{@code CgRenderQueue} is a final utility class with static {@code int} constants
 * (not an enum). Tests verify the canonical values and name-lookup behaviour.</p>
 */
public class CgRenderQueueTest {

    @Test
    public void GEOMETRY_value() {
        assertEquals(2000, CgRenderQueue.GEOMETRY);
    }

    @Test
    public void TRANSPARENT_value() {
        assertEquals(3000, CgRenderQueue.TRANSPARENT);
    }

    @Test
    public void BACKGROUND_value() {
        assertEquals(1000, CgRenderQueue.BACKGROUND);
    }

    @Test
    public void ALPHA_TEST_value() {
        assertEquals(2450, CgRenderQueue.ALPHA_TEST);
    }

    @Test
    public void OVERLAY_value() {
        assertEquals(4000, CgRenderQueue.OVERLAY);
    }

    @Test
    public void allConstantsHaveDistinctPositiveValues() {
        int[] all = {
            CgRenderQueue.BACKGROUND,
            CgRenderQueue.GEOMETRY,
            CgRenderQueue.ALPHA_TEST,
            CgRenderQueue.TRANSPARENT,
            CgRenderQueue.OVERLAY
        };
        for (int v : all) assertTrue("Queue value must be positive: " + v, v > 0);
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals("Constants must be distinct", all[i], all[j]);
            }
        }
    }

    @Test
    public void fromName_exactMatch() {
        assertEquals(CgRenderQueue.GEOMETRY, CgRenderQueue.fromName("Geometry"));
    }

    @Test
    public void fromName_caseInsensitive() {
        assertEquals(CgRenderQueue.TRANSPARENT, CgRenderQueue.fromName("transparent"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromName_unknownThrows() {
        CgRenderQueue.fromName("InvalidName");
    }

    @Test
    public void fromName_alphaTest() {
        assertEquals(CgRenderQueue.ALPHA_TEST, CgRenderQueue.fromName("AlphaTest"));
    }

    // ── Threshold constants sanity ────────────────────────────────────────────

    @Test
    public void transparentThreshold_betweenAlphaTestAndTransparent() {
        assertTrue(CgRenderQueue.ALPHA_TEST_THRESHOLD <= CgRenderQueue.ALPHA_TEST);
        assertTrue(CgRenderQueue.TRANSPARENT_THRESHOLD > CgRenderQueue.ALPHA_TEST);
        assertTrue(CgRenderQueue.TRANSPARENT_THRESHOLD <= CgRenderQueue.TRANSPARENT);
    }

    @Test
    public void overlayThreshold_equalsOverlay() {
        assertEquals(CgRenderQueue.OVERLAY_THRESHOLD, CgRenderQueue.OVERLAY);
    }
}
