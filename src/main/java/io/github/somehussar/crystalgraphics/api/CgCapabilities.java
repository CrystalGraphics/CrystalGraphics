package io.github.somehussar.crystalgraphics.api;

import lombok.AccessLevel;
import lombok.Getter;
import org.lwjgl.opengl.*;

/**
 * Immutable snapshot of OpenGL capabilities relevant to CrystalGraphics,
 * detected once per GL context lifecycle.
 *
 * <p>This class queries the LWJGL 2.9 {@link ContextCapabilities} at
 * construction time (via the static {@link #detect()} factory method)
 * and exposes boolean flags and integer limits used by the framebuffer
 * and shader abstraction layers to select the appropriate backend.</p>
 *
 * <h3>Detection Order</h3>
 * <p>CrystalGraphics uses a <em>waterfall</em> preference for FBO backends:
 * Core GL30 &gt; ARB &gt; EXT.  Use {@link #preferredFboBackend()} to
 * determine the best available backend for the current context.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Instances are immutable and may be freely shared.  However, they
 * capture the capabilities of the OpenGL context that was current at the
 * time of {@link #detect()}.  If the context is destroyed and recreated,
 * a new {@code CgCapabilities} must be detected.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable and therefore thread-safe.  The
 * {@link #detect()} factory method must be called on the render thread
 * with a current OpenGL context.</p>
 *
 * @see FramebufferPath
 */
@Getter
public final class CgCapabilities {

    private static String cachedParsedVersionKey   = null;
    private static int[]  cachedParsedVersionValue = null;
    private static volatile CgCapabilities cachedCaps = null;

    // ─────────────────────────────────────────────────────────────────────────
    //  Enums
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enumerates the available framebuffer object backends in order of preference.
     *
     * <p>The preferred backend is selected by {@link CgCapabilities#preferredFboBackend()}
     * based on the detected hardware capabilities.</p>
     */
    public enum FramebufferPath {
        /** Core OpenGL 3.0 framebuffer support — preferred path, full MRT. */
        CORE_GL30,
        /** {@code GL_ARB_framebuffer_object} — semantically identical to Core GL30, full MRT. */
        ARB_FBO,
        /** {@code GL_EXT_framebuffer_object} — legacy, no separate draw/read, limited MRT. */
        EXT_FBO,
        /** No framebuffer support detected. FBO creation will fail. */
        NONE
    }

    /**
     * Enumerates the available GPU-resident shader buffer paths in preference order.
     *
     * <p>SSBO is preferred (GL 4.3 core or ARB). TBO is the fallback for GL 3.3+ contexts
     * that lack SSBO. NONE means the material pipeline cannot be used on this context.</p>
     */
    public enum ShaderBufferPath {
        /** Core OpenGL 4.3 SSBO via {@code org.lwjgl.opengl.GL43}. */
        SSBO_GL43,
        /** {@code GL_ARB_shader_storage_buffer_object} SSBO when core 4.3 is absent. */
        SSBO_ARB,
        /** Texture buffer object fallback (GL 3.1+, sampler-based). */
        TBO,
        /** No usable shader buffer path. Material pipeline creation will throw. */
        NONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Capability fields  (package-private — accessible to same-package tests)
    // ─────────────────────────────────────────────────────────────────────────

    // ── Framebuffer backends ──────────────────────────────────────────────────
    /** Whether Core OpenGL 3.0 framebuffer support is available. */
    boolean coreFbo;
    /** Whether the {@code GL_ARB_framebuffer_object} extension is available. */
    boolean arbFbo;
    /** Whether the {@code GL_EXT_framebuffer_object} extension is available. */
    boolean extFbo;

    // ── Shader backends ───────────────────────────────────────────────────────
    /** Whether Core OpenGL 2.0 shader support ({@code glUseProgram} etc.) is available. */
    boolean coreShaders;
    /** Whether the {@code GL_ARB_shader_objects} extension is available. */
    boolean arbShaders;

    // ── Render limits ─────────────────────────────────────────────────────────
    /** Maximum number of simultaneous draw buffer outputs (MRT); at least 1. */
    int maxDrawBuffers;
    /** Maximum texture image units (shader) or fixed-function texture units. */
    int maxTextureUnits;
    /** Maximum 2D texture dimension (width/height). */
    int maxTextureSize;
    /** Maximum renderbuffer dimension; falls back to {@link #maxTextureSize} on EXT-only. */
    int maxRenderbufferSize;
    /** Maximum color attachments on FBOs; typically 1 (EXT) or 8+ (Core/ARB). */
    int maxColorAttachments;

    // ── Depth / Stencil ───────────────────────────────────────────────────────
    /** Stencil buffer support (assumed universally available on target hardware). */
    @Getter(AccessLevel.NONE) boolean stencil;
    /** Depth buffer support (assumed universally available on target hardware). */
    @Getter(AccessLevel.NONE) boolean depth;
    /** Packed depth-stencil via {@code GL_EXT_packed_depth_stencil} or {@code GL_NV_packed_depth_stencil}. */
    @Getter(AccessLevel.NONE) boolean packedDepthStencil;
    /** Depth texture support via {@code GL_ARB_depth_texture}. */
    @Getter(AccessLevel.NONE) boolean depthTexture;

    // ── Buffer / VAO ─────────────────────────────────────────────────────────
    /** Whether VAOs are supported (Core GL30 or {@code GL_ARB_vertex_array_object}). */
    @Getter(AccessLevel.NONE) boolean hasVao;
    /** Whether {@code glMapBufferRange} is supported (Core GL30 or {@code GL_ARB_map_buffer_range}). */
    @Getter(AccessLevel.NONE) boolean hasMapBufferRange;
    /** Whether fence sync is available (Core GL32 or {@code GL_ARB_sync}). */
    boolean arbSync;

    // ── Instancing ────────────────────────────────────────────────────────────
    /** Whether instanced draw calls are available (Core GL31 or {@code GL_ARB_draw_instanced}). */
    @Getter(AccessLevel.NONE) boolean drawInstanced;
    /** Whether per-attribute divisors are available (Core GL33 or {@code GL_ARB_instanced_arrays}). */
    @Getter(AccessLevel.NONE) boolean vertexAttribDivisor;
    /** Maximum vertex attribute slots; 0 if shader support is absent. A mat4 consumes 4 slots. */
    int maxVertexAttribs;

    // ── Shader buffers ────────────────────────────────────────────────────────
    /** Whether Core OpenGL 4.3 SSBO is available. */
    boolean shaderStorageBufferCore;
    /** Whether {@code GL_ARB_shader_storage_buffer_object} is available (and core 4.3 is absent). */
    boolean shaderStorageBufferArb;
    /** Whether the TBO material fallback path is available (GL33+, no SSBO). */
    boolean textureBufferMaterialPath;
    /** Preferred shader buffer path, derived from the three flags above. */
    @Getter(AccessLevel.NONE) ShaderBufferPath shaderBufferPath;
    /** Max SSBO binding points (min 8 per GL4.3 spec); 0 when SSBO is unsupported. */
    int maxSsboBindings;
    /** Max UBO binding points (min 36 per GL3.1 spec); 0 when shader support is absent. */
    int maxUniformBufferBindings;
    /** Whether {@code GL_ARB_gpu_shader_int64} (OpenGL 4.0+) is supported. */
    boolean gpuShaderInt64;

    // ─────────────────────────────────────────────────────────────────────────
    //  Constructor
    // ─────────────────────────────────────────────────────────────────────────

    /** Package-private: allows {@link #detectUncached()} and same-package tests to construct. */
    CgCapabilities() {}

    // ─────────────────────────────────────────────────────────────────────────
    //  Cache management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a lazily-cached capabilities snapshot.
     *
     * <p>The first call probes the current OpenGL context and caches the result;
     * subsequent calls return the cached instance.  Must be called on the render thread
     * with an active GL context (at least on the first invocation).</p>
     *
     * <p>If the GL context is destroyed and recreated, call {@link #clearCache()} to
     * force re-detection on the next call.</p>
     *
     * @return the cached {@code CgCapabilities} for the current context
     * @see #detectUncached()
     * @see #clearCache()
     */
    public static CgCapabilities detect() {
        CgCapabilities local = cachedCaps;
        if (local == null) {
            local = detectUncached();
            cachedCaps = local;
        }
        return local;
    }

    /**
     * Clears the cached capabilities singleton.
     *
     * <p>After this call, the next invocation of {@link #detect()} will re-probe
     * the OpenGL context.  Use this when the GL context is destroyed and recreated.</p>
     */
    public static void clearCache() { cachedCaps = null; }

    // ─────────────────────────────────────────────────────────────────────────
    //  Detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detects capabilities from the current OpenGL context (uncached).
     *
     * <p>Must be called on the render thread with an active GL context.
     * Prefer {@link #detect()} for most use cases.</p>
     *
     * <p>Depth and stencil support are assumed to be universally available
     * on the target hardware range (OpenGL 2.0+ / Intel HD 3000 and above).</p>
     *
     * @return a new {@code CgCapabilities} reflecting the current context
     * @see #detect()
     */
    public static CgCapabilities detectUncached() {
        ContextCapabilities gl = GLContext.getCapabilities();
        CgCapabilities caps = new CgCapabilities();

        // ── Framebuffer backends ──────────────────────────────────────────────
        caps.coreFbo = gl.OpenGL30;
        caps.arbFbo  = gl.GL_ARB_framebuffer_object;
        caps.extFbo  = gl.GL_EXT_framebuffer_object;

        // ── Shader backends ───────────────────────────────────────────────────
        caps.coreShaders = gl.OpenGL20;
        caps.arbShaders  = gl.GL_ARB_shader_objects;

        // ── Render limits ─────────────────────────────────────────────────────
        caps.maxDrawBuffers      = caps.coreShaders ? GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) : 1;
        caps.maxTextureUnits     = caps.coreShaders ? GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS) : GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        caps.maxTextureSize      = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        caps.maxRenderbufferSize = (caps.coreFbo || caps.arbFbo) ? GL11.glGetInteger(0x84E8 /* GL_MAX_RENDERBUFFER_SIZE */) : caps.maxTextureSize;
        caps.maxColorAttachments = (caps.coreFbo || caps.arbFbo) ? GL11.glGetInteger(0x8CDF /* GL_MAX_COLOR_ATTACHMENTS */) : 1;

        // ── Depth / Stencil ───────────────────────────────────────────────────
        caps.depth              = true; // universally available on target hardware
        caps.stencil            = true;
        caps.packedDepthStencil = gl.GL_EXT_packed_depth_stencil || gl.GL_NV_packed_depth_stencil;
        caps.depthTexture       = gl.GL_ARB_depth_texture;

        // ── Buffer / VAO ──────────────────────────────────────────────────────
        caps.hasVao            = gl.OpenGL30 || gl.GL_ARB_vertex_array_object;
        caps.hasMapBufferRange = gl.OpenGL30 || gl.GL_ARB_map_buffer_range;
        caps.arbSync           = gl.OpenGL32 || gl.GL_ARB_sync;

        // ── Instancing ────────────────────────────────────────────────────────
        caps.drawInstanced       = gl.OpenGL31 || gl.GL_ARB_draw_instanced;
        caps.vertexAttribDivisor = gl.OpenGL33 || gl.GL_ARB_instanced_arrays;
        caps.maxVertexAttribs    = caps.coreShaders ? GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS) : 0;

        // ── Shader buffers (waterfall: GL43 SSBO > ARB SSBO > TBO > NONE) ────
        caps.shaderStorageBufferCore   = gl.OpenGL43;
        caps.shaderStorageBufferArb    = !caps.shaderStorageBufferCore && gl.GL_ARB_shader_storage_buffer_object;
        caps.textureBufferMaterialPath = !caps.shaderStorageBufferCore && !caps.shaderStorageBufferArb && gl.OpenGL33;
        caps.maxSsboBindings           = (caps.shaderStorageBufferCore || caps.shaderStorageBufferArb) ? GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
        caps.maxUniformBufferBindings  = caps.coreShaders ? GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS) : 0;
        caps.gpuShaderInt64            = gl.OpenGL40;

        if      (caps.shaderStorageBufferCore)   caps.shaderBufferPath = ShaderBufferPath.SSBO_GL43;
        else if (caps.shaderStorageBufferArb)    caps.shaderBufferPath = ShaderBufferPath.SSBO_ARB;
        else if (caps.textureBufferMaterialPath) caps.shaderBufferPath = ShaderBufferPath.TBO;
        else                                     caps.shaderBufferPath = ShaderBufferPath.NONE;

        return caps;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API — custom-named getters (Lombok suppressed on matching fields)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the preferred framebuffer object backend based on detected capabilities,
     * using the waterfall order: Core GL30 &gt; ARB &gt; EXT.
     *
     * @return the best available {@link FramebufferPath}, or {@link FramebufferPath#NONE}
     *         if no FBO support was detected
     */
    public FramebufferPath preferredFboBackend() {
        if (coreFbo) return FramebufferPath.CORE_GL30;
        if (arbFbo)  return FramebufferPath.ARB_FBO;
        if (extFbo)  return FramebufferPath.EXT_FBO;
        return FramebufferPath.NONE;
    }

    /** Returns whether stencil buffer attachments are supported. */
    public boolean hasStencil() { return stencil; }

    /** Returns whether depth buffer attachments are supported. */
    public boolean hasDepth() { return depth; }

    /**
     * Returns whether packed depth-stencil formats are supported
     * ({@code GL_EXT_packed_depth_stencil} or {@code GL_NV_packed_depth_stencil}).
     */
    public boolean hasPackedDepthStencil() { return packedDepthStencil; }

    /** Returns whether depth textures are supported via {@code GL_ARB_depth_texture}. */
    public boolean hasDepthTexture() { return depthTexture; }

    /**
     * Returns whether vertex array objects (VAOs) are supported
     * (Core GL30 or {@code GL_ARB_vertex_array_object}).
     */
    public boolean isVaoSupported() { return hasVao; }

    /**
     * Returns whether {@code glMapBufferRange} is supported
     * (Core GL30 or {@code GL_ARB_map_buffer_range}).
     */
    public boolean isMapBufferRangeSupported() { return hasMapBufferRange; }

    /**
     * Returns whether instanced draw calls ({@code glDrawArraysInstanced} /
     * {@code glDrawElementsInstanced}) are available
     * (Core GL31 or {@code GL_ARB_draw_instanced}).
     */
    public boolean isDrawInstancedSupported() { return drawInstanced; }

    /**
     * Returns whether per-attribute vertex divisors ({@code glVertexAttribDivisor})
     * are available (Core GL33 or {@code GL_ARB_instanced_arrays}).
     */
    public boolean isVertexAttribDivisorSupported() { return vertexAttribDivisor; }

    /** Returns the preferred shader buffer path for the current GL context. */
    public ShaderBufferPath shaderBufferPath() { return shaderBufferPath; }

    // ─────────────────────────────────────────────────────────────────────────
    //  GL version string parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a raw GL version string (e.g. {@code "4.6.0 NVIDIA 537.58"}) into a
     * {@code {major, minor}} array.
     *
     * <p>Accepts the raw string returned by {@code GL11.glGetString(GL11.GL_VERSION)}
     * as well as simple {@code "major.minor"} expressions.  The parser locates the first
     * occurrence of a {@code digit(s).digit(s)} pattern in the input, ignoring any prefix
     * text (e.g. {@code "OpenGL ES"}) and any trailing driver/vendor information.</p>
     *
     * <p>If parsing fails (null, empty, garbage), returns {@code {0, 0}}.</p>
     *
     * @param glVersionString the raw GL version string, or a simple {@code "major.minor"} expression
     * @return a two-element array {@code {major, minor}}, or {@code {0, 0}} if unparseable
     */
    public static int[] parseGLVersion(String glVersionString) {
        if (glVersionString == null || glVersionString.isEmpty()) return new int[]{0, 0};

        int[] cached = cachedParsedVersionValue;
        if (cached != null && glVersionString.equals(cachedParsedVersionKey))
            return new int[]{cached[0], cached[1]};

        int len = glVersionString.length();
        int i = 0;

        while (i < len && !isAsciiDigit(glVersionString.charAt(i))) i++;
        if (i >= len) return new int[]{0, 0};

        int majorStart = i;
        while (i < len && isAsciiDigit(glVersionString.charAt(i))) i++;
        if (i >= len || glVersionString.charAt(i) != '.') return new int[]{0, 0};
        int major = parseIntSubstring(glVersionString, majorStart, i);

        i++; // skip '.'

        int minorStart = i;
        while (i < len && isAsciiDigit(glVersionString.charAt(i))) i++;
        if (minorStart == i) return new int[]{0, 0};
        int minor = parseIntSubstring(glVersionString, minorStart, i);

        cachedParsedVersionKey   = glVersionString;
        cachedParsedVersionValue = new int[]{major, minor};
        return new int[]{major, minor};
    }

    private static boolean isAsciiDigit(char c) { return c >= '0' && c <= '9'; }

    private static int parseIntSubstring(String s, int from, int to) {
        int result = 0;
        for (int i = from; i < to; i++) result = result * 10 + (s.charAt(i) - '0');
        return result;
    }
}
