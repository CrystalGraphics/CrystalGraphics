package io.github.somehussar.crystalgraphics.api;

import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

/**
 * Engine-reserved UBO/SSBO/TBO binding slot constants.
 *
 * <p>Slots 0–4 are owned by CrystalGraphics. User code must use slots
 * {@value #USER_START} and above via {@link CgUniformBuffer#create}
 * to avoid silent stomping of engine data.</p>
 *
 * <pre>
 * Slot  Owner                    Constant
 * ----  --------                 --------
 *  0    per-object SSBO/TBO      OBJECT_DATA  (CgShaderBuffer / CgObjectDataBuffer)
 *  1    per-frame UBO            FRAME_DATA   (CgUniformBuffer / CgFrameBlock)
 *  2-4  reserved for engine use  (lighting, shadow, etc.)
 *  5+   user code                USER_START and above
 * </pre>
 *
 * <h3>SSBO and TBO namespace</h3>
 * <p>SSBO binding points and TBO texture units share the same logical index space.
 * {@link #USER_START} applies to both: user SSBO binding point N and user TBO texture
 * unit N refer to the same logical slot. {@link #toTboUnit(int)} converts a raw binding
 * point to its corresponding TBO texture unit (currently an identity mapping).</p>
 */
public final class CgBindingPoints {

    /** Per-object SSBO/TBO binding point (CgShaderBuffer / CgObjectDataBuffer). */
    public static final int OBJECT_DATA = 0;

    /** Per-frame UBO binding point (CgUniformBuffer / CgFrameBlock). */
    public static final int FRAME_DATA  = 1;

    /**
     * First binding/texture-unit slot available to user-defined buffers.
     * Slots 0–4 are engine-reserved.
     *
     * <p>User-facing factory methods ({@link io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer#create}
     * etc.) accept a 0-based {@code userIndex} and add {@code USER_START} internally, so user
     * code never needs to reference this constant directly.</p>
     */
    public static final int USER_START  = 5;

    /**
     * GL texture unit used by the engine's object buffer TBO ({@code CgObjectDataBuffer}).
     * Corresponds to engine binding point {@link #OBJECT_DATA} = 0.
     */
    public static final int TBO_ENGINE_UNIT = 0;

    private CgBindingPoints() {}

    /**
     * Translates a raw binding point to the corresponding TBO texture unit.
     *
     * <p>With {@code USER_START = TBO_USER_START = 5} the mapping is a direct identity:
     * binding N → texture unit N. SSBO and TBO share the same logical index space.</p>
     *
     * @param binding the raw binding point (e.g. {@link #OBJECT_DATA}, {@link #USER_START}, …)
     * @return the GL texture unit that corresponds to {@code binding}
     */
    public static int toTboUnit(int binding) {
        return binding; // identity: SSBO and TBO namespace share the same index space
    }
}
