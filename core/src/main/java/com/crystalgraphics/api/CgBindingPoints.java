package com.crystalgraphics.api;

import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.platform.gl.CgCapabilities;

/**
 * Engine-reserved binding slot constants for SSBO, TBO, and UBO resources.
 *
 * <h3>Two separate namespaces</h3>
 * <p>SSBO binding points and TBO texture units are <em>distinct</em> namespaces in OpenGL.
 * An SSBO at binding slot N and a TBO at texture unit N are completely independent resources.
 * Reserved SSBO/TBO pairs (see {@link Binding}) make the distinction explicit: {@code ssbo} is
 * an SSBO binding slot, {@code tbo} is a GL texture unit. UBO-only reservations (no TBO fallback
 * concept applies to UBOs) stay plain {@code int} fields.</p>
 *
 * <h3>Top-vs-bottom allocation strategy</h3>
 * <p>Engine-reserved slots are allocated from the <em>top</em> of the available range
 * ({@code maxXxxBindings - 1}). This minimises collision with Minecraft and other mods,
 * which start from slot 0. User-defined buffers are allocated from the <em>bottom</em>
 * ({@code USER_START_*}) and grow upward.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>{@link #init(CgCapabilities)} must be called (by {@code CgRenderPipeline.init()})
 * before any engine buffer is constructed. The runtime {@link Binding} fields are {@code null}
 * and the runtime {@code int} fields are {@code -1} until then; {@link #isInitialized()} returns
 * {@code false} in that state.</p>
 */
public final class CgBindingPoints {
    public static CgCapabilities.ShaderBufferPath PATH;

    /**
     * A reserved engine binding <em>pair</em> — one slot for the SSBO path, one for the TBO
     * fallback path (distinct GL namespaces, see class javadoc) — that knows how to resolve
     * itself against the currently-detected {@link CgCapabilities.ShaderBufferPath}.
     *
     * <p>Exists so a consumer needing one reserved slot (e.g.
     * {@code CgShaderBufferRegistry.getOrCreateInternal}) takes a single object instead of two
     * loose ints plus its own copy of the SSBO-vs-TBO branch — the branch lives here, once.</p>
     *
     * @param ssbo SSBO binding point to use when the SSBO path is active
     * @param tbo  GL texture unit to use when the TBO fallback path is active
     */
    public record Binding(int ssbo, int tbo) {
        /** Returns {@link #ssbo} or {@link #tbo}, whichever matches the currently-active path. */
        public int resolve() {
            return PATH == CgCapabilities.ShaderBufferPath.TBO ? tbo : ssbo;
        }
    }

    // ── Engine buffers — resolved to top of available range at init() ─────────

    /**
     * Reserved binding pair for the engine's per-object data buffer ({@code CgObjectDataBuffer}).
     * {@code ssbo} = {@code maxSsboBindings - 1}, {@code tbo} = {@code maxTextureImageUnits - 1}.
     * {@code null} until {@link #init(CgCapabilities)} has been called.
     */
    public static Binding OBJECT_DATA;

    /**
     * Reserved binding pair for {@code CgQuadRenderer}'s shared {@code "CgQuadRendererInstances"}
     * buffer — {@code ssbo} one below {@link #OBJECT_DATA}'s, {@code tbo} one below
     * {@link #OBJECT_DATA}'s, continuing the same top-of-range countdown. Engine-reserved,
     * must not be used by user-attached SSBOs/TBOs — see
     * {@code CgShaderBufferRegistry.getOrCreateInternal}. {@code null} until
     * {@link #init(CgCapabilities)} has been called.
     */
    public static Binding QUAD_RENDERER;

    /**
     * Reserved binding pair for {@code CgVectorRenderer}'s shared {@code "CgVectorRendererInstances"}
     * buffer — one below {@link #QUAD_RENDERER}'s on both paths, continuing the same top-of-range
     * countdown. Engine-reserved, must not be used by user-attached SSBOs/TBOs — see
     * {@code CgShaderBufferRegistry.getOrCreateInternal}. {@code null} until
     * {@link #init(CgCapabilities)} has been called.
     *
     * <p>Unlike {@link #QUAD_RENDERER}, this buffer is read from the <em>fragment</em> stage as well
     * as the vertex stage: a curve's stroke is an analytic SDF evaluated per pixel, so the fragment
     * needs the control points themselves. That works on both paths without any compiler change —
     * {@code CgMaterialShaderCompiler.appendAttachedBuffers} runs for the fragment source too — but
     * it does mean the TBO fallback consumes this texture unit in both stages rather than one.</p>
     */
    public static Binding CURVE_RENDERER;

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
     * UBO binding slot for {@code CgTextRenderer}'s shared {@code "TextData"} block
     * ({@code u_projection} — renderer-local, deliberately separate from the engine's shared
     * per-frame {@code cg_ProjMatrix}). Set to {@code maxUniformBufferBindings - 3} by
     * {@link #init(CgCapabilities)}, following the same top-of-range pattern as
     * {@link #FRAME_DATA_UBO}/{@link #MATERIAL_PROPERTIES_UBO}. This slot is engine-reserved and
     * must not be used by user-attached UBOs — see {@link CgShaderBufferRegistry}.
     * Valid only after {@link #init(CgCapabilities)} has been called.
     */
    public static int TEXT_DATA_UBO = -1;

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
     * The top three slots ({@code maxUniformBufferBindings - 1} through {@code - 3}) are
     * engine-reserved for {@link #FRAME_DATA_UBO}, {@link #MATERIAL_PROPERTIES_UBO}, and
     * {@link #TEXT_DATA_UBO}.
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
        int maxSsboBindings = caps.getMaxSsboBindings();
        int maxUboBindings  = caps.getMaxUniformBufferBindings();

        // ── SSBO/TBO Path bindings ───────────────────────────────────────────────────────────────
        OBJECT_DATA = new Binding(--maxSsboBindings, --maxTextureUnits);
        QUAD_RENDERER = new Binding(--maxSsboBindings, --maxTextureUnits);
        // Note: this consumes one more texture unit from the same countdown DEPTH_TEXTURE_UNIT
        // draws from below, so adding it shifts DEPTH_TEXTURE_UNIT down by one. That is fine —
        // the slot is resolved dynamically and never hardcoded — but it is a deliberate change,
        // not an accident: anything that caches the depth unit across an init() would be wrong.
        CURVE_RENDERER = new Binding(--maxSsboBindings, --maxTextureUnits);

        // ── UBO bindings ───────────────────────────────────────────────────────────────
        FRAME_DATA_UBO          = --maxUboBindings;
        MATERIAL_PROPERTIES_UBO = --maxUboBindings;
        TEXT_DATA_UBO           = --maxUboBindings;

        // ── Texture bindings ───────────────────────────────────────────────────────────────
        DEPTH_TEXTURE_UNIT = --maxTextureUnits;
    }

    /**
     * Returns {@code true} after {@link #init(CgCapabilities)} has been called.
     */
    public static boolean isInitialized() {
        return OBJECT_DATA != null;
    }
}
