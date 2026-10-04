package com.crystalgraphics.platform.gl;

import lombok.AccessLevel;
import lombok.Getter;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;

import java.util.Arrays;
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
    /**
     * Where kernels run, best first. A kernel runs at the highest tier at or below {@link #computeTier()} it can,
     * and every tier is forceable for a driver that misbehaves: {@code -Dcrystalgraphics.compute.tier=g40}.
     */
    public enum ComputeTier {
        /** A device: compute passes on the tracked backend's device. */
        V,
        /** GL compute shaders and storage images, with indirect counts and subgroups where the context lists them. */
        G43,
        /** No compute: kernels lowered to draws (transform feedback, blended points, fragment passes); counts stay on the GPU. */
        G40,
        /** As {@code G40}, but a count a kernel wrote is read back before the draw that takes it: a stall. */
        G33,
        /** Kernels' Java bodies on worker threads. */
        CPU;

        /** What this tier needs that the context lacks, or null. */
        String missing(CgCapabilities caps, boolean device) {
            switch (this) {
                case V:   return device ? null : "a device (the tracked backend)";
                case G43: return caps.compute && caps.storageImages ? null : "compute shaders and storage images (GL 4.3)";
                case G40: return device ? "transform feedback, which a device does not carry"
                        : caps.feedbackCount ? null : "transform feedback 2 (GL 4.0)";
                case G33: return device ? "transform feedback, which a device does not carry" : null;
                default:  return null;
            }
        }
    }

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
    /** @see CgGLContext#mappingIsFree() */
    @Getter(AccessLevel.NONE) boolean mappingIsFree;
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
    /** Texels a buffer texture may read: 65536 at least. */
    @Getter int maxTextureBufferSize;
    /**
     * Whether a program's {@code GL_COMPLETION_STATUS_KHR} may be polled ({@code GL_KHR_parallel_shader_compile} or
     * the ARB twin): a compile can then be submitted and drawn from only once the driver has finished it.
     */
    @Getter boolean parallelShaderCompile;
    /** Whether the current context is a core profile. Fixed-function state
     *  such as {@code GL_ALPHA_TEST} is unavailable in core profile contexts. */
    boolean coreProfile;
    int glslVersion;

    // ── Compute and GPU-driven draws ──────────────────────────────────────────
    // Each answers what a consumer needs, joined from a core version, its ARB extension and, on the tracked
    // backend, the device. @see #computeTier
    @Getter(AccessLevel.NONE)
    boolean compute, storageImages, subgroups, floatAtomics, drawIndirect, multiDrawIndirect, indirectCount,
            drawParameters, multiDraw, feedbackCount, asyncCompute, bindless;
    @Getter(AccessLevel.NONE) ComputeTier computeTier;
    /** What a kernel may ask for; zeros without compute. @see #maxComputeWorkGroupSize */
    @Getter(AccessLevel.NONE) int maxComputeSharedMemory, maxComputeInvocations;
    /** What a storage buffer bound from an offset aligns the offset to; 0 without storage buffers. */
    @Getter(AccessLevel.NONE) int storageOffsetAlignment;
    @Getter(AccessLevel.NONE) final int[] maxComputeWorkGroupSize = new int[3], maxComputeWorkGroupCount = new int[3];
    @Getter(AccessLevel.NONE) int subgroupSize, subgroupOperations;

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
            // The probe reads limits through CgGL, and may be the first GL anything asks for.
            CgGL.installIfAbsent();
            local = detectUncached();
            cachedCaps = local;
            // Published so the fixed-function guards in CgGL cost a field load. @see CgGL#CORE
            CgGL.CORE = local.coreProfile;
            CgGpuReport.log();
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

    /** The current context's capabilities where something has detected them, else null. Any thread; probes nothing. */
    public static CgCapabilities detected() { return cachedCaps; }

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
        if (context == null) throw new IllegalStateException("CgGLContext not initialised — call CgCapabilities.init() before detect()");
        CgGLContext gl = CgDisabledExtensions.filter(context);
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
        caps.mappingIsFree     = gl.mappingIsFree();
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
        caps.maxTextureBufferSize      = CgGL.glGetInteger(CgGL.GL_MAX_TEXTURE_BUFFER_SIZE);
        caps.parallelShaderCompile     = gl.parallelShaderCompile();

        // GL_CONTEXT_PROFILE_MASK (0x9126); bit 0x1 = GL_CONTEXT_CORE_PROFILE_BIT.
        caps.coreProfile = (CgGL.glGetInteger(0x9126) & 0x1) != 0;

        // ── Compute and GPU-driven draws ──────────────────────────────────────
        CgDeviceInfo device = CgGL.backend() instanceof CgTrackedGLBackend tracked ? tracked.device().info() : null;
        caps.glslVersion = device != null ? 450 : gl.OpenGL46() ? 460 : gl.OpenGL44() ? 440 : gl.OpenGL43() ? 430
                : gl.OpenGL42() ? 420 : gl.OpenGL40() ? 400 : 330;
        boolean ssbo = caps.shaderStorageBufferCore || caps.shaderStorageBufferArb;
        caps.compute           = device != null || (gl.OpenGL43() || gl.GL_ARB_compute_shader()) && ssbo;
        caps.storageImages     = device != null || gl.OpenGL42() || gl.GL_ARB_shader_image_load_store();
        caps.floatAtomics      = device == null && gl.GL_NV_shader_atomic_float();
        caps.drawIndirect      = device != null || gl.OpenGL40() || gl.GL_ARB_draw_indirect();
        caps.multiDrawIndirect = device != null ? device.multiDrawIndirect() : gl.OpenGL43() || gl.GL_ARB_multi_draw_indirect();
        caps.indirectCount     = device != null ? device.indirectCount() : gl.OpenGL46() || gl.GL_ARB_indirect_parameters();
        caps.drawParameters    = device != null ? device.drawParameters() : gl.OpenGL46() || gl.GL_ARB_shader_draw_parameters();
        boolean firstInstance  = device != null ? device.indirectFirstInstance() : gl.OpenGL42() || gl.GL_ARB_base_instance();
        caps.multiDraw         = caps.multiDrawIndirect && caps.drawParameters && firstInstance
                && !"false".equalsIgnoreCase(System.getProperty("crystalgraphics.mesh.multiDraw"));
        caps.feedbackCount     = device == null && (gl.OpenGL40() || gl.GL_ARB_transform_feedback2());
        caps.asyncCompute      = device != null && device.asyncCompute();
        caps.bindless          = device == null && gl.GL_ARB_bindless_texture();
        caps.computeTier       = computeTier(caps, device != null);
        caps.storageOffsetAlignment = ssbo || device != null ? CgGL.glGetInteger(CgGL.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT) : 0;
        if (caps.compute) {
            caps.maxComputeSharedMemory = CgGL.glGetInteger(CgGL.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
            caps.maxComputeInvocations = CgGL.glGetInteger(CgGL.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
            for (int axis = 0; axis < 3; axis++) {
                caps.maxComputeWorkGroupSize[axis] = CgGL.glGetIntegeri(CgGL.GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis);
                caps.maxComputeWorkGroupCount[axis] = CgGL.glGetIntegeri(CgGL.GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis);
            }
            if ((device != null || gl.GL_KHR_shader_subgroup())
                    && (CgGL.glGetInteger(CgGL.GL_SUBGROUP_SUPPORTED_STAGES_KHR) & GL_COMPUTE_SHADER_BIT) != 0) {
                caps.subgroupSize = CgGL.glGetInteger(CgGL.GL_SUBGROUP_SIZE_KHR);
                caps.subgroupOperations = CgGL.glGetInteger(CgGL.GL_SUBGROUP_SUPPORTED_FEATURES_KHR);
            }
        }
        caps.subgroups = (caps.subgroupOperations & SUBGROUP_BASIC) != 0;

        caps.shaderBufferPath = shaderBufferPath(caps);
        return caps;
    }

    /** The best path, or {@code -Dcrystalgraphics.shaderBuffer.tier}'s where this context has it. */
    private static ShaderBufferPath shaderBufferPath(CgCapabilities caps) {
        ShaderBufferPath best = caps.shaderStorageBufferCore ? ShaderBufferPath.SSBO_GL43
                : caps.shaderStorageBufferArb ? ShaderBufferPath.SSBO_ARB
                : caps.textureBufferMaterialPath ? ShaderBufferPath.TBO
                : ShaderBufferPath.NONE;
        String property = System.getProperty("crystalgraphics.shaderBuffer.tier");
        if (property == null) return best;
        ShaderBufferPath forced = ShaderBufferPath.valueOf(property.trim().toUpperCase(Locale.ROOT));
        boolean has = switch (forced) {
            case SSBO_GL43 -> caps.shaderStorageBufferCore;
            case SSBO_ARB -> caps.shaderStorageBufferArb;
            case TBO -> true;                                  // core since 3.1, below the floor
            case NONE -> false;
        };
        if (!has) {
            throw new IllegalStateException("-Dcrystalgraphics.shaderBuffer.tier=" + forced
                    + " is not a path this context has; the best it supports is " + best);
        }
        return forced;
    }

    private static ComputeTier computeTier(CgCapabilities caps, boolean device) {
        ComputeTier best = Arrays.stream(ComputeTier.values()).filter(t -> t.missing(caps, device) == null)
                .findFirst().orElseThrow();
        String property = System.getProperty("crystalgraphics.compute.tier");
        if (property == null) return best;
        ComputeTier forced = ComputeTier.valueOf(property.trim().toUpperCase(Locale.ROOT));
        String missing = forced.missing(caps, device);
        if (missing != null) {
            throw new IllegalStateException("-Dcrystalgraphics.compute.tier=" + forced + " needs " + missing
                    + "; the best this context supports is " + best);
        }
        return forced;
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

    /** @see CgGLContext#mappingIsFree() */
    public boolean isMappingFree() { return mappingIsFree; }

    /** Whether {@code glBufferStorage} and persistent mapping are supported (Core GL44 or {@code GL_ARB_buffer_storage}). */
    public boolean isBufferStorageSupported() { return bufferStorage; }

    /** Returns the preferred shader buffer path for the current GL context. */
    public ShaderBufferPath shaderBufferPath() { return shaderBufferPath; }

    /** The tier a vertex stream takes: forced, or the best this context supports. */
    public StreamBufferTier vertexStreamTier() { return vertexStreamTier; }

    /** The tier shader-buffer storage takes: {@code ORPHAN}, or {@code SUBDATA} where vertex streams take it. */
    public StreamBufferTier shaderStreamTier() { return shaderStreamTier; }

    /** Kernels: GL 4.3 or {@code ARB_compute_shader}, with storage buffers; any device. */
    public boolean compute() { return compute; }

    /** {@code imageLoad} and {@code imageStore}: GL 4.2 or {@code ARB_shader_image_load_store}; any device. */
    public boolean storageImages() { return storageImages; }

    /** {@link #subgroupOperations()} bits, as {@code VkSubgroupFeatureFlags} and {@code KHR_shader_subgroup} name them. */
    public static final int SUBGROUP_BASIC = 0x1, SUBGROUP_VOTE = 0x2, SUBGROUP_ARITHMETIC = 0x4, SUBGROUP_BALLOT = 0x8,
            SUBGROUP_SHUFFLE = 0x10;
    private static final int GL_COMPUTE_SHADER_BIT = 0x20;

    /** Subgroup operations in kernels: {@code KHR_shader_subgroup} listing the compute stage; any device. */
    public boolean subgroups() { return subgroups; }

    /** The invocations in a subgroup, or 0 without {@link #subgroups()}. */
    public int subgroupSize() { return subgroupSize; }

    /** What kernels may do across a subgroup: {@link #SUBGROUP_BASIC} and the rest, or 0. */
    public int subgroupOperations() { return subgroupOperations; }

    /** Atomic adds on floats: {@code NV_shader_atomic_float}. No device enables its counterpart. */
    public boolean floatAtomics() { return floatAtomics; }

    /** A draw's arguments from a buffer: GL 4.0 or {@code ARB_draw_indirect}; any device. */
    public boolean drawIndirect() { return drawIndirect; }

    /** More than one indirect draw in a call: GL 4.3 or {@code ARB_multi_draw_indirect}, or a device that enabled it. */
    public boolean multiDrawIndirect() { return multiDrawIndirect; }

    /** The draw count from a buffer too: GL 4.6 or {@code ARB_indirect_parameters}, or a device that enabled it. */
    public boolean indirectCount() { return indirectCount; }

    /** {@code gl_DrawID} and a draw's bases in a shader: GL 4.6 or {@code ARB_shader_draw_parameters}, or a device. */
    public boolean drawParameters() { return drawParameters; }

    /**
     * Meshes drawn as one multi-draw, each command carrying its bases: {@link #multiDrawIndirect()},
     * {@link #drawParameters()} and a command's first instance (GL 4.2, {@code ARB_base_instance}, or a device's
     * {@code drawIndirectFirstInstance}). {@code -Dcrystalgraphics.mesh.multiDraw=false} turns it off.
     */
    public boolean multiDraw() { return multiDraw; }

    /** A captured transform-feedback stream drawn by its own count: GL 4.0 or {@code ARB_transform_feedback2}. */
    public boolean feedbackCount() { return feedbackCount; }

    /** Kernels on a queue beside the frame's: a device with a compute queue of its own, the owned Vulkan device's. */
    public boolean asyncCompute() { return asyncCompute; }

    /** Textures by handle rather than by unit: {@code ARB_bindless_texture}. */
    public boolean bindless() { return bindless; }

    /** The GLSL version the context compiles: 330 to 460, 450 on a device. */
    public int glslVersion() { return glslVersion; }

    /** Bytes of {@code shared} memory one work group may declare. */
    public int maxComputeSharedMemory() { return maxComputeSharedMemory; }

    /** The multiple a storage buffer's offset must be when part of it is bound: 0 without storage buffers. */
    public int storageOffsetAlignment() { return storageOffsetAlignment; }

    /** Invocations one work group may hold: the product of its size. */
    public int maxComputeInvocations() { return maxComputeInvocations; }

    /** A work group's largest size along {@code axis}, 0 to 2. */
    public int maxComputeWorkGroupSize(int axis) { return maxComputeWorkGroupSize[axis]; }

    /** The most work groups one dispatch may launch along {@code axis}, 0 to 2. */
    public int maxComputeWorkGroupCount(int axis) { return maxComputeWorkGroupCount[axis]; }

    /** The highest tier this context runs kernels at, or {@code -Dcrystalgraphics.compute.tier}'s. */
    public ComputeTier computeTier() { return computeTier; }


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
