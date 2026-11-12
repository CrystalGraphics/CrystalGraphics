package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.CgBindingPoints;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgShaderBufferRegistryTest {

    @Test
    public void isInitializedReturnsFalseBeforeInit() {
        // CgBindingPoints runtime fields start at -1 (uninitialised).
        // This test runs outside a GL context so init() has never been called in this JVM.
        // Reset fields to -1 to guarantee a clean starting state.
        CgBindingPoints.OBJECT_DATA    = null;
        CgBindingPoints.VIEW_UBO = -1;
        CgBindingPoints.ENVIRONMENT_UBO = -1;

        assertFalse("isInitialized() should return false before init()", CgBindingPoints.isInitialized());
    }
}
