package com.crystalgraphics.api;

import com.crystalgraphics.platform.gl.CgCapabilities;

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
 * <p>{@link #init(CgCapabilities)} must be called (by {@code CgRenderPipeline.init()})
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

    /**
     * UBO binding slot for the engine's per-material properties block ({@code CgMaterialBlock}).
     * Set to {@code maxUniformBufferBindings - 2} by {@link #init(CgCapabilities)}.
     * This slot is engine-reserved and must not be used by user-attached UBOs.
     * Valid only after {@link #init(CgCapabilities)} has been called.
     */
    public static int MATERIAL_PROPERTIES_UBO = -1;

     /**
      * GL texture unit reserved for {@code cg_DepthBuffer} — the per-frame scene depth snapshot
      * automatically bound by the engine before each material draw.
      *
      * <p>Resolved dynamically from the tail of the available texture unit range at
      * {@link #init(CgCapabilities)} time. Away from the user sampler range (0–7) and from
      * {@link #OBJECT_DATA_TBO} which is allocated at the top of the same range.</p>
      *
      * <p><strong>Shader authors must NOT use this unit in material Properties.</strong>
      * The {@code cg_DepthBuffer} uniform declared in {@code cg_env.glsl} is bound here
      * automatically by the engine on every material draw.</p>
      */
    public static int DEPTH_TEXTURE_UNIT = -1;

    /** Name of depth texture sampler2D uniform*/
    public static final String DEPTH_TEXTURE_UNIFORM = "cg_DepthBuffer";

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
     * The top two slots ({@code maxUniformBufferBindings - 1} and {@code - 2}) are
     * engine-reserved for {@link #FRAME_DATA_UBO} and {@link #MATERIAL_PROPERTIES_UBO}.
     */
    public static final int USER_START_UBO = 0;

    private CgBindingPoints() {}

    /**
     * Resolves the three runtime engine-buffer slots from the detected hardware limits.
     * Must be called once — by {@code CgRenderPipeline.init()} — before any engine
     * buffer is constructed.
     *
     * @param caps detected capabilities; must not be null
     */
    public static void init(CgCapabilities caps) {
        PATH = caps.shaderBufferPath();
        
        int maxTextureUnits = caps.getMaxTextureUnits();
        int maxSsboBindings = caps.getMaxTextureUnits();
        int maxUboBindings = caps.getMaxUniformBufferBindings();
        
        // ── SSBO/TBO Path bindings ───────────────────────────────────────────────────────────────
        OBJECT_DATA_SSBO = maxSsboBindings--;
        OBJECT_DATA_TBO = maxTextureUnits--;
        
        // ── UBO bindings ───────────────────────────────────────────────────────────────
        FRAME_DATA_UBO = maxUboBindings--;
        MATERIAL_PROPERTIES_UBO = maxUboBindings--;

        // ── Texture bindings ───────────────────────────────────────────────────────────────
        DEPTH_TEXTURE_UNIT  = maxTextureUnits--;
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
