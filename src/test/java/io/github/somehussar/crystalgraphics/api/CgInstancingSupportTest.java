package io.github.somehussar.crystalgraphics.api;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for instancing support pure logic (no GL context needed).
 */
public class CgInstancingSupportTest {

    private static CgCapabilities caps(boolean drawInstanced, boolean vertexAttribDivisor, int maxAttribs) {
        return CgCapabilities.createForTest(
            false, false, false, true, false,
            1, 8, true, true, false, false,
            4096, 4096, 8, true, true, false,
            drawInstanced, vertexAttribDivisor, maxAttribs
        );
    }

    @Test
    public void testSupportedWhenBothCapabilitiesPresent() {
        assertTrue(CgInstanceVertexArrayBinding.isSupported(caps(true, true, 16)));
    }

    @Test
    public void testUnsupportedWhenDrawInstancedMissing() {
        assertFalse(CgInstanceVertexArrayBinding.isSupported(caps(false, true, 16)));
    }

    @Test
    public void testUnsupportedWhenDivisorMissing() {
        assertFalse(CgInstanceVertexArrayBinding.isSupported(caps(true, false, 16)));
    }

    @Test
    public void testUnsupportedWhenBothMissing() {
        assertFalse(CgInstanceVertexArrayBinding.isSupported(caps(false, false, 16)));
    }

    @Test
    public void testValidateAttributeSlotsPassesUnderLimit() {
        CgVertexFormat base = CgVertexFormat.POS2_UV2_COL4UB;
        CgInstanceFormat instance = CgInstanceFormat.TRANSFORM_COLOR_CUSTOM;
        CgInstanceVertexArrayBinding.validateAttributeSlots(base, instance, 16);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateAttributeSlotsRejectsOverLimit() {
        CgVertexFormat base = CgVertexFormat.POS2_UV2_COL4UB;
        CgInstanceFormat instance = CgInstanceFormat.TRANSFORM_COLOR_CUSTOM;
        CgInstanceVertexArrayBinding.validateAttributeSlots(base, instance, 5);
    }

    @Test
    public void testValidateAttributeSlotsExactlyAtLimit() {
        CgVertexFormat base = CgVertexFormat.POS2_UV2_COL4UB;
        CgInstanceFormat instance = CgInstanceFormat.TRANSFORM_COLOR_CUSTOM;
        int maxAttribs = base.getAttributeCount() + instance.getAttributeCount();
        CgInstanceVertexArrayBinding.validateAttributeSlots(base, instance, maxAttribs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidateAttributeSlotsRejectsOneLessThanNeeded() {
        CgVertexFormat base = CgVertexFormat.POS2_UV2_COL4UB;
        CgInstanceFormat instance = CgInstanceFormat.TRANSFORM_COLOR_CUSTOM;
        int maxAttribs = base.getAttributeCount() + instance.getAttributeCount() - 1;
        CgInstanceVertexArrayBinding.validateAttributeSlots(base, instance, maxAttribs);
    }
}
