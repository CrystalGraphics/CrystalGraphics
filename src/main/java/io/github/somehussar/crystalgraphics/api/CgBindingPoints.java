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
}
