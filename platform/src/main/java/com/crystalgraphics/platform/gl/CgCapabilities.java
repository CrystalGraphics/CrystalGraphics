package com.crystalgraphics.platform.gl;

import lombok.AccessLevel;
import lombok.Getter;
import com.crystalgraphics.platform.CgPlatform;

import java.util.Locale;

/**
 * Immutable snapshot of OpenGL capabilities relevant to CrystalGraphics,
 * detected once per GL context lifecycle.
 *
 * <p>This class is populated at construction time (via the static {@link #detect()} factory
 * method) by reading flags from the platform's {@link CgGLContext} implementation,
 * and exposes boolean flags and integer limits used by the framebuffer
 * and shader abstraction layers to select the appropriate backend.</p>
 *
 * <h3>The GL 3.3 floor</h3>
 * <p>Detection throws below OpenGL 3.3, so everything core in 3.3 — framebuffers, VAOs, instancing,
 * samplers, timer queries — is simply there and has no flag. A 3.2 context with the four extensions 3.3
 * absorbed from them passes too: vanilla Minecraft 1.17 to 1.21.4 asks for 3.2 core, and NVIDIA hands
 * back exactly 3.2 on hardware that runs 4.6, which on Fabric is the context we get. What is flagged is above the floor —
 * SSBOs (4.3 or ARB), {@code glCopyImageSubData} (4.3), persistent mapping (4.4 or ARB), 64-bit shader
 * integers (4.0) — and what {@code CgStreamBuffer}'s waterfall chooses its tier from, fence sync and
 * {@code glMapBufferRange} included.</p>
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
 */
@Getter
public final class CgCapabilities {
    
    @Getter private static CgGLContext context;
    public static void init(CgGLContext ctx) {  context = ctx; }
    
    private static String cachedParsedVersionKey   = null;
    private static int[]  cachedParsedVersionValue = null;
    private static volatile CgCapabilities cachedCaps = null;

    // ─────────────────────────────────────────────────────────────────────────
    //  Enums
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enumerates the available GPU-resident shader buffer paths in preference order.
     *
     * <p>SSBO is preferred (GL 4.3 core or ARB). TBO is the fallback for GL 3.3+ contexts
     * that lack SSBO. NONE means the material pipeline cannot be used on this context.</p>
     */
    public enum ShaderBufferPath {
        /** Core OpenGL 4.3 SSBO. */
        SSBO_GL43,
        /** {@code GL_ARB_shader_storage_buffer_object} SSBO when core 4.3 is absent. */
        SSBO_ARB,
        /** Texture buffer object fallback (GL 3.1+, sampler-based). */
        TBO,
        /** No usable shader buffer path. Material pipeline creation will throw. */
        NONE
    }

    /**
     * How a {@code CgStreamBuffer} uploads, best first.
     *
     * <p>{@code -Dcrystalgraphics.stream.tier=persistent|ring|orphan|subdata} forces one — for a driver that
     * misbehaves on the tier chosen, and for comparing them. Forcing a tier this context cannot do throws
     * from {@link #detect()}.</p>
     */
    public enum StreamBufferTier {
        /** A frame ring in immutable storage mapped once for its life: no map call per upload. GL 4.4 / {@code ARB_buffer_storage}. */
        PERSISTENT,
        /** A frame ring mapped per upload, unsynchronised; one fence per frame. GL 3.2 sync and {@code glMapBufferRange}. */
        RING,
        /** Every upload orphans the whole buffer and writes at offset 0; the driver does the renaming. */
        ORPHAN,
        /** A CPU staging copy and {@code glBufferSubData} at offset 0 -- the one every driver gets right. */
        SUBDATA
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Capability fields  (package-private — accessible to same-package tests)
    // ─────────────────────────────────────────────────────────────────────────

    // ── Render limits ─────────────────────────────────────────────────────────
    /** Maximum number of simultaneous draw buffer outputs (MRT); at least 1. */
    int maxDrawBuffers;
    /** Maximum texture image units. */
    @Getter(AccessLevel.NONE) int maxTextureUnits;

    /**
     * A ceiling the host imposes on usable texture units, independent of what GL reports.
     *
     * <p>A host with its own GL state tracker models a fixed number of units; binding above that
     * corrupts sampling for whoever draws next. Declared by the loader — unset, nothing is clamped.</p>
     */
    @Getter(AccessLevel.NONE) private static volatile int hostTextureUnitCeiling = Integer.MAX_VALUE;

    /** @see #hostTextureUnitCeiling */
    public static void setHostTextureUnitCeiling(int units) {
        hostTextureUnitCeiling = Math.max(1, units);
    }

    /**
     * What GL reports, clamped by whatever the host declared via
     * {@link #setHostTextureUnitCeiling(int)}. Clamped on read, so registration order does not matter.
     */
    public int getMaxTextureUnits() {
        return Math.min(maxTextureUnits, hostTextureUnitCeiling);
    }
    /** Maximum 2D texture dimension (width/height). */
    int maxTextureSize;
    /** Maximum renderbuffer dimension. */
    int maxRenderbufferSize;
    /** Maximum color attachments on FBOs; 8 or more at the floor. */
    int maxColorAttachments;

    // ── Depth / Stencil ───────────────────────────────────────────────────────
    /** Stencil buffer support (assumed universally available on target hardware). */
    @Getter(AccessLevel.NONE) boolean stencil;
    /** Depth buffer support (assumed universally available on target hardware). */
    @Getter(AccessLevel.NONE) boolean depth;

    // ── Texture copy ──────────────────────────────────────────────────────────
    /**
     * Whether {@code glCopyImageSubData} is available (Core GL43). Enables direct GPU-to-GPU
     * texel copies with no CPU round trip — see {@code CgTextureCopy}.
     *
     * <p>Gated on core 4.3 only, deliberately not also probing {@code GL_ARB_copy_image}:
     * {@code CgTextureCopy} already falls back to a framebuffer blit that works on the GL 3.0
     * baseline, so the ARB path would only cover the narrow band of drivers that expose the
     * extension without 4.3 — not worth an extra probe on every {@code CgGLContext}
     * implementation.</p>
     */
    @Getter(AccessLevel.NONE) boolean copyImageSubData;

    // ── Streaming — what CgStreamBuffer's waterfall picks a tier from ─────────
    /** Whether fence sync is available (Core GL32 or {@code GL_ARB_sync}). */
    boolean arbSync;
    /** Whether {@code glMapBufferRange} is available (Core GL30 or {@code GL_ARB_map_buffer_range}). */
    @Getter(AccessLevel.NONE) boolean hasMapBufferRange;
    /** Whether immutable, persistently mappable storage is available (Core GL44 or {@code GL_ARB_buffer_storage}). */
    @Getter(AccessLevel.NONE) boolean bufferStorage;
    /** Chosen from the three flags above and the override. @see StreamBufferTier */
    @Getter(AccessLevel.NONE)
    StreamBufferTier vertexStreamTier, shaderStreamTier;

    // ── Instancing ────────────────────────────────────────────────────────────
    /** Maximum vertex attribute slots. A mat4 consumes 4 slots. */
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
    /** Max UBO binding points (min 36 per GL3.1 spec). */
    int maxUniformBufferBindings;
    /** Whether {@code GL_ARB_gpu_shader_int64} (OpenGL 4.0+) is supported. */
    boolean gpuShaderInt64;

    /** Max MSAA samples available for driver*/
    @Getter int maxSamples;
    /** Whether the current context is a core profile. Fixed-function state
     *  such as {@code GL_ALPHA_TEST} is unavailable in core profile contexts. */
    boolean coreProfile;

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
            if (context == null) context = CgPlatform.capabilities();
            local = detectUncached();
            cachedCaps = local;
            // Published so the fixed-function guards in CgGL cost a field load. @see CgGL#CORE
            CgGL.CORE = local.coreProfile;
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
        CgGLContext gl = context;
        if (gl == null) throw new IllegalStateException("CgGLContext not initialised — call CgCapabilities.init() before detect()");
        boolean threeThreeByExtension = gl.OpenGL32() && gl.GL_ARB_instanced_arrays() && gl.GL_ARB_sampler_objects()
                && gl.GL_ARB_explicit_attrib_location() && gl.GL_ARB_timer_query();
        if (!gl.OpenGL33() && !threeThreeByExtension) {
            throw new IllegalStateException("CrystalGraphics needs OpenGL 3.3, or 3.2 with the extensions 3.3 "
                    + "absorbed, and this context has neither");
        }
        CgCapabilities caps = new CgCapabilities();

        // ── Render limits ─────────────────────────────────────────────────────
        caps.maxDrawBuffers      = CgGL.glGetInteger(CgGL.GL_MAX_DRAW_BUFFERS);
        caps.maxTextureUnits     = CgGL.glGetInteger(CgGL.GL_MAX_TEXTURE_IMAGE_UNITS);
        caps.maxTextureSize      = CgGL.glGetInteger(CgGL.GL_MAX_TEXTURE_SIZE);
        caps.maxRenderbufferSize = CgGL.glGetInteger(0x84E8 /* GL_MAX_RENDERBUFFER_SIZE */);
        caps.maxColorAttachments = CgGL.glGetInteger(0x8CDF /* GL_MAX_COLOR_ATTACHMENTS */);
        caps.maxVertexAttribs    = CgGL.glGetInteger(CgGL.GL_MAX_VERTEX_ATTRIBS);

        // ── Depth / Stencil ───────────────────────────────────────────────────
        caps.depth   = true;
        caps.stencil = true;

        // ── Texture copy ──────────────────────────────────────────────────────
        caps.copyImageSubData = gl.OpenGL43();

        // ── Streaming ─────────────────────────────────────────────────────────
        caps.arbSync           = gl.OpenGL32() || gl.GL_ARB_sync();
        caps.hasMapBufferRange = gl.OpenGL30() || gl.GL_ARB_map_buffer_range();
        caps.bufferStorage     = gl.OpenGL44() || gl.GL_ARB_buffer_storage();
        caps.vertexStreamTier  = vertexStreamTier(caps);
        caps.shaderStreamTier  = caps.vertexStreamTier == StreamBufferTier.SUBDATA ? StreamBufferTier.SUBDATA : StreamBufferTier.ORPHAN;

        // ── Shader buffers (GL43 SSBO > ARB SSBO > TBO > NONE) ───────────────
        caps.shaderStorageBufferCore   = gl.OpenGL43();
        caps.shaderStorageBufferArb    = !caps.shaderStorageBufferCore && gl.GL_ARB_shader_storage_buffer_object();
        caps.textureBufferMaterialPath = !caps.shaderStorageBufferCore && !caps.shaderStorageBufferArb && gl.OpenGL33();
        caps.maxSsboBindings           = (caps.shaderStorageBufferCore || caps.shaderStorageBufferArb) ? CgGL.glGetInteger(CgGL.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
        caps.maxUniformBufferBindings  = CgGL.glGetInteger(CgGL.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        caps.gpuShaderInt64            = gl.OpenGL40();
        caps.maxSamples                = CgGL.glGetInteger(CgGL.GL_MAX_SAMPLES);

        // GL_CONTEXT_PROFILE_MASK (0x9126); bit 0x1 = GL_CONTEXT_CORE_PROFILE_BIT.
        caps.coreProfile = (CgGL.glGetInteger(0x9126) & 0x1) != 0;

        if      (caps.shaderStorageBufferCore)   caps.shaderBufferPath = ShaderBufferPath.SSBO_GL43;
        else if (caps.shaderStorageBufferArb)    caps.shaderBufferPath = ShaderBufferPath.SSBO_ARB;
        else if (caps.textureBufferMaterialPath) caps.shaderBufferPath = ShaderBufferPath.TBO;
        else                                     caps.shaderBufferPath = ShaderBufferPath.NONE;

        return caps;
    }

    private static StreamBufferTier vertexStreamTier(CgCapabilities caps) {
        StreamBufferTier best = caps.bufferStorage ? StreamBufferTier.PERSISTENT
                : caps.arbSync && caps.hasMapBufferRange ? StreamBufferTier.RING
                : caps.hasMapBufferRange ? StreamBufferTier.ORPHAN
                : StreamBufferTier.SUBDATA;
        String property = System.getProperty("crystalgraphics.stream.tier");
        if (property == null) return best;
        StreamBufferTier forced = StreamBufferTier.valueOf(property.trim().toUpperCase(Locale.ROOT));
        if (forced.ordinal() < best.ordinal()) {
            throw new IllegalStateException("-Dcrystalgraphics.stream.tier=" + forced.name().toLowerCase(Locale.ROOT)
                    + " needs what this context lacks; the best it supports is " + best.name().toLowerCase(Locale.ROOT));
        }
        return forced;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Public API — custom-named getters (Lombok suppressed on matching fields)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns whether stencil buffer attachments are supported. */
    public boolean hasStencil() { return stencil; }

    /** Returns whether depth buffer attachments are supported. */
    public boolean hasDepth() { return depth; }

    /** @see #copyImageSubData */
    public boolean isCopyImageSubDataSupported() { return copyImageSubData; }

    /** Whether {@code glMapBufferRange} is supported (Core GL30 or {@code GL_ARB_map_buffer_range}). */
    public boolean isMapBufferRangeSupported() { return hasMapBufferRange; }

    /** Whether {@code glBufferStorage} and persistent mapping are supported (Core GL44 or {@code GL_ARB_buffer_storage}). */
    public boolean isBufferStorageSupported() { return bufferStorage; }

    /** Returns the preferred shader buffer path for the current GL context. */
    public ShaderBufferPath shaderBufferPath() { return shaderBufferPath; }

    /** The tier a vertex stream takes: forced, or the best this context supports. */
    public StreamBufferTier vertexStreamTier() { return vertexStreamTier; }

    /** The tier shader-buffer storage takes: {@code ORPHAN}, or {@code SUBDATA} where vertex streams take it. */
    public StreamBufferTier shaderStreamTier() { return shaderStreamTier; }


    // ─────────────────────────────────────────────────────────────────────────
    //  GL version string parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a raw GL version string (e.g. {@code "4.6.0 NVIDIA 537.58"}) into a
     * {@code {major, minor}} array.
     *
     * <p>Accepts the raw string returned by {@code CgGL.glGetString(CgGL.GL_VERSION)}
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
