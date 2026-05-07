package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgShaderBufferRegistryTest {

    @Test(expected = IllegalArgumentException.class)
    public void validateBindingPointBelowUserStartThrows() {
        CgBindingPoints.validateBindingPoint(9);
    }

    @Test
    public void validateBindingPointAtUserStartDoesNotThrow() {
        CgBindingPoints.validateBindingPoint(10);
    }

    @Test
    public void validateBindingPointAboveUserStartDoesNotThrow() {
        CgBindingPoints.validateBindingPoint(100);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validateBindingPointZeroThrows() {
        CgBindingPoints.validateBindingPoint(0);
    }

    @Test
    public void exceptionMessageContainsUserStartValue() {
        try {
            CgBindingPoints.validateBindingPoint(5);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue("Message should contain '10' (USER_START)",
                    e.getMessage().contains("10"));
        }
    }
}
