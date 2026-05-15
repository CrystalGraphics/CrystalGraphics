package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.CgBindingPoints;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgShaderBufferRegistryTest {

    @Test
    public void userStartSsboIsZero() {
        assertEquals("USER_START_SSBO should be 0 (SSBO user slots from bottom)", 0, CgBindingPoints.USER_START_SSBO);
    }

    @Test
    public void userStartTboIsAboveMcUnits() {
        assertTrue("USER_START_TBO must be above Minecraft texture units 0–4",
                CgBindingPoints.USER_START_TBO >= 5);
    }

    @Test
    public void userStartUboIsZero() {
        assertEquals("USER_START_UBO should be 0 (UBO user slots from bottom)", 0, CgBindingPoints.USER_START_UBO);
    }

    @Test
    public void isInitializedReturnsFalseBeforeInit() {
        // CgBindingPoints runtime fields start at -1 (uninitialised).
        // This test runs outside a GL context so init() has never been called in this JVM.
        // Reset fields to -1 to guarantee a clean starting state.
        CgBindingPoints.OBJECT_DATA_SSBO = -1;
        CgBindingPoints.OBJECT_DATA_TBO  = -1;
        CgBindingPoints.FRAME_DATA_UBO   = -1;

        assertFalse("isInitialized() should return false before init()", CgBindingPoints.isInitialized());
    }
}
