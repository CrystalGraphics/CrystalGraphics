package io.github.somehussar.crystalgraphics.api.vertex;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgInstanceFormat}.
 */
public class CgInstanceLayoutTest {

    @Test
    public void testMat4ExpandsFourAttributes() {
        CgInstanceFormat layout = CgInstanceFormat.builder("test").mat4("a_model").build();
        assertEquals("mat4 must expand to 4 physical attributes", 4, layout.getAttributeCount());
    }

    @Test
    public void testMat4AttributeNames() {
        CgInstanceFormat layout = CgInstanceFormat.builder("test").mat4("a_model").build();
        assertEquals("a_model0", layout.getAttribute(0).getName());
        assertEquals("a_model1", layout.getAttribute(1).getName());
        assertEquals("a_model2", layout.getAttribute(2).getName());
        assertEquals("a_model3", layout.getAttribute(3).getName());
    }

    @Test
    public void testMat4Offsets() {
        CgInstanceFormat layout = CgInstanceFormat.builder("test").mat4("a_model").build();
        assertEquals(0,  layout.getAttribute(0).getOffset());
        assertEquals(16, layout.getAttribute(1).getOffset());
        assertEquals(32, layout.getAttribute(2).getOffset());
        assertEquals(48, layout.getAttribute(3).getOffset());
    }

    @Test
    public void testMat4Stride() {
        CgInstanceFormat layout = CgInstanceFormat.builder("test").mat4("a_model").build();
        assertEquals("mat4 stride must be 64 bytes (4 × vec4 × 4 bytes)", 64, layout.getStride());
    }

    @Test
    public void testTransformColorCustomStride() {
        assertEquals("TRANSFORM_COLOR_CUSTOM stride must be 84 bytes (64 + 4 + 16)",
                84, CgInstanceFormat.TRANSFORM_COLOR_CUSTOM.getStride());
    }

    @Test
    public void testTransformColorCustomAttributeCount() {
        assertEquals("TRANSFORM_COLOR_CUSTOM must have 6 physical attributes",
                6, CgInstanceFormat.TRANSFORM_COLOR_CUSTOM.getAttributeCount());
    }

    @Test
    public void testDefaultDivisorIsOne() {
        CgInstanceFormat layout = CgInstanceFormat.builder("test").vec4("a_x").build();
        assertEquals(1, layout.getDivisor());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDivisorTwoThrows() {
        CgInstanceFormat.builder("test").vec4("a_x").divisor(2).build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDivisorZeroThrows() {
        CgInstanceFormat.builder("test").vec4("a_x").divisor(0).build();
    }

    @Test(expected = IllegalStateException.class)
    public void testZeroAttributesBuildThrows() {
        CgInstanceFormat.builder("test").build();
    }

    @Test
    public void testValueEquality() {
        CgInstanceFormat a = CgInstanceFormat.builder("x").vec4("a_pos").build();
        CgInstanceFormat b = CgInstanceFormat.builder("y").vec4("a_pos").build();
        assertEquals("Equal layouts must compare equal regardless of debug name", a, b);
        assertEquals("Equal layouts must have same hash code", a.hashCode(), b.hashCode());
    }

    @Test
    public void testInequalityDifferentAttributes() {
        CgInstanceFormat a = CgInstanceFormat.builder("test").vec4("a_pos").build();
        CgInstanceFormat b = CgInstanceFormat.builder("test").color4UB("a_color").build();
        assertNotEquals("Layouts with different attributes must not be equal", a, b);
    }
}
