package io.github.somehussar.crystalgraphics.api;

import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;

/**
 * Engine-reserved binding slot constants for SSBO, TBO, and UBO resources.
 *
 * <h3>Two separate namespaces</h3>
 * <p>SSBO binding points and TBO texture units are <em>distinct</em> namespaces in OpenGL.
 * An SSBO at binding slot N and a TBO at texture unit N are completely independent resources.
 * Constants in this class make the distinction explicit:
 * {@code *_SSBO} fields are SSBO binding slots; {@code *_TBO} fields are GL texture units.</p>
 *
 * <h3>Top-vs-bottom allocation strategy</h3>
 * <p>Engine-reserved slots are allocated from the <em>top</em> of the available range
 * ({@code maxXxxBindings - 1}). This minimises collision with Minecraft and other mods,
 * which start from slot 0. User-defined buffers are allocated from the <em>bottom</em>
 * ({@code USER_START_*}) and grow upward.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>{@link #init(CgCapabilities)} must be called (by {@code CgMaterialPipeline.init()})
 * before any engine buffer is constructed. The three runtime fields are {@code -1}
 * until then; {@link #isInitialized()} returns {@code false} in that state.</p>
 */
public final class CgBindingPoints {
    public static CgCapabilities.ShaderBufferPath PATH;
    // ── Engine buffers — resolved to top of available range at init() ─────────

    /**
     * SSBO binding slot for the engine's per-object data buffer ({@code CgObjectDataBuffer}).
     * Set to {@code maxSsboBindings - 1} by {@link #init(CgCapabilities)}.
     * Valid only after {@link #init(CgCapabilities)} has been called.
     */
    public static int OBJECT_DATA_SSBO = -1;

    /**
     * GL texture unit for the engine's per-object data buffer ({@code CgObjectDataBuffer}) TBO.
     * Set to {@code maxTextureImageUnits - 1} by {@link #init(CgCapabilities)}.
     * Valid only after {@link #init(CgCapabilities)} has been called.
     */
    public static int OBJECT_DATA_TBO = -1;

    /**
     * UBO binding slot for the engine's per-frame uniform block ({@code CgFrameBlock}).
     * Set to {@code maxUniformBufferBindings - 1} by {@link #init(CgCapabilities)}.
     * Valid only after {@link #init(CgCapabilities)} has been called.
     */
    public static int FRAME_DATA_UBO = -1;

    // ── User buffers — allocated from bottom of available range ──────────────

    /**
     * First SSBO binding slot available to user-defined buffers.
     * User SSBOs start at slot 0 and grow upward.
     */
    public static final int USER_START_SSBO = 0;

    /**
     * First GL texture unit available to user-defined TBO buffers.
     * Starts at unit 5 to stay above the texture units Minecraft typically occupies (0–4).
     */
    public static final int USER_START_TBO = 5;

    /**
     * First UBO binding slot available to user-defined buffers.
     * User UBOs start at slot 0 and grow upward.
     */
    public static final int USER_START_UBO = 0;

    private CgBindingPoints() {}

    /**
     * Resolves the three runtime engine-buffer slots from the detected hardware limits.
     * Must be called once — by {@code CgMaterialPipeline.init()} — before any engine
     * buffer is constructed.
     *
     * @param caps detected capabilities; must not be null
     */
    public static void init(CgCapabilities caps) {
        PATH = caps.shaderBufferPath();
        OBJECT_DATA_SSBO = caps.getMaxSsboBindings() - 1;
        OBJECT_DATA_TBO = caps.getMaxTextureUnits() - 1;
        FRAME_DATA_UBO = caps.getMaxUniformBufferBindings() - 1;
    }

    /**
     * Returns {@code true} after {@link #init(CgCapabilities)} has been called.
     */
    public static boolean isInitialized() {
        return OBJECT_DATA_SSBO >= 0;
    }

    /**Returns OBJECT_DATA binding point for the current capability path.*/
    public static int objectData() {
        return PATH == CgCapabilities.ShaderBufferPath.TBO ? CgBindingPoints.OBJECT_DATA_TBO : CgBindingPoints.OBJECT_DATA_SSBO;
    }
}
