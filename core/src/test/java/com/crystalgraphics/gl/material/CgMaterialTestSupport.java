package com.crystalgraphics.gl.material;

/** Test hooks other packages' tests need: the shader registry is a singleton a sibling test may have deleted. */
public final class CgMaterialTestSupport {

    private CgMaterialTestSupport() {}

    public static void resetShaderRegistry() {
        CgMaterialShaderRegistry.resetForTest();
    }
}
