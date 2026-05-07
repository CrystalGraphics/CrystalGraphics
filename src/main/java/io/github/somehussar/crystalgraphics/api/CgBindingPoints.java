package io.github.somehussar.crystalgraphics.api;

import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

/**
 * Engine-reserved UBO/SSBO binding slot constants.
 *
 * <p>Slots 0–9 are owned by CrystalGraphics. User code must use slots
 * {@value #USER_START} and above via {@link CgUniformBuffer#create}
 * to avoid silent stomping of engine data.</p>
 *
 * <pre>
 * Slot  Owner                    Constant
 * ----  --------                 --------
 *  0    per-object SSBO/TBO      OBJECT_DATA  (CgShaderBuffer)
 *  1    per-frame UBO            FRAME_DATA   (CgUniformBuffer / CgFrameBlock)
 *  2-9  reserved for engine use  (lighting, shadow, etc.)
 * 10+   user code                USER_START and above
 * </pre>
 */
public final class CgBindingPoints {

    /** Per-object SSBO/TBO binding point (CgShaderBuffer). */
    public static final int OBJECT_DATA = 0;

    /** Per-frame UBO binding point (CgUniformBuffer / CgFrameBlock). */
    public static final int FRAME_DATA  = 1;

    /** First binding slot available to user-defined UBOs. Slots 0–9 are engine-reserved. */
    public static final int USER_START  = 10;

    private CgBindingPoints() {}
    
    /**
     * Validates that a binding point is in the user-accessible range.
     * Exposed as package-accessible static for unit testing of the guard in isolation.
     *
     * @param bindingPoint the point to validate
     * @throws IllegalArgumentException if {@code bindingPoint < CgBindingPoints.USER_START}
     */
    public static void validateBindingPoint(int bindingPoint) {
        if (bindingPoint < CgBindingPoints.USER_START) {
            throw new IllegalArgumentException(
                "Binding slot " + bindingPoint + " is reserved for the engine (0\u2013"
                + (CgBindingPoints.USER_START - 1) + "). "
                + "Use CgBindingPoints.USER_START (" + CgBindingPoints.USER_START + "+) for custom UBOs. "
                + "Conflicts here produce silent rendering corruption with no GL error.");
        }
    }
}
