package io.github.somehussar.crystalgraphics.api.material;

import org.junit.Test;

import static org.junit.Assert.*;

public class CgRenderQueueTest {

    @Test
    public void GEOMETRY_value() {
        assertEquals(2000, CgRenderQueue.GEOMETRY.getValue());
    }

    @Test
    public void TRANSPARENT_value() {
        assertEquals(3000, CgRenderQueue.TRANSPARENT.getValue());
    }

    @Test
    public void BACKGROUND_value() {
        assertEquals(1000, CgRenderQueue.BACKGROUND.getValue());
    }

    @Test
    public void ALPHA_TEST_value() {
        assertEquals(2450, CgRenderQueue.ALPHA_TEST.getValue());
    }

    @Test
    public void OVERLAY_value() {
        assertEquals(4000, CgRenderQueue.OVERLAY.getValue());
    }

    @Test
    public void allConstantsHaveDistinctNonZeroValues() {
        CgRenderQueue[] all = CgRenderQueue.values();
        for (int i = 0; i < all.length; i++) {
            assertTrue(all[i].getValue() > 0);
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals(all[i].getValue(), all[j].getValue());
            }
        }
    }

    @Test
    public void fromName_exactMatch() {
        assertSame(CgRenderQueue.GEOMETRY, CgRenderQueue.fromName("Geometry"));
    }

    @Test
    public void fromName_caseInsensitive() {
        assertSame(CgRenderQueue.TRANSPARENT, CgRenderQueue.fromName("transparent"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromName_unknownThrows() {
        CgRenderQueue.fromName("InvalidName");
    }

    @Test
    public void fromName_alphaTest() {
        assertSame(CgRenderQueue.ALPHA_TEST, CgRenderQueue.fromName("AlphaTest"));
    }
}
