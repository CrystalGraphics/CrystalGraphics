package com.crystalgraphics.platform.gl;

import lombok.AccessLevel;
import lombok.Getter;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.variable.CgVar;

import java.util.Arrays;
import java.util.Locale;

/**
 * What the installed device can do, as the engine's tiers ask it: limits and flags read from its {@link CgRHI.Globals},
 * once per device.
 *
 * <pre>{@code
 * CgCapabilities caps = CgCapabilities.detect();   // render thread, a device installed
 * if (caps.bc7()) ...
 * }</pre>
 *
 * <ul>
 *   <li>A device below the engine's floor never installs, so what the floor guarantees has no flag.</li>
 *   <li>A new device (a resize never makes one; a host's restart does) needs {@link #clearCache()}:
 *       {@code CgPlatform.register} clears it.</li>
 *   <li>Instances are immutable and may be shared across threads; {@link #detect()} runs on the render thread.</li>
 * </ul>
 */
@Getter
public final class CgCapabilities {

    public static final CgVar.Text COMPUTE_TIER = CgVar.text("crystalgraphics.compute.tier", "", CgVar.DEV,
            "V or G43: kernels run as that tier would, where the context has what it needs, refused naming what is"
                    + " missing where not; G40 and G33 run none. Empty for the best. Taken at each context's capability probe.");
    public static final CgVar.Text SHADER_BUFFER_TIER = CgVar.text("crystalgraphics.shaderBuffer.tier", "", CgVar.DEV,
            "SSBO_GL43 or SSBO_ARB: engine buffers read that way; TBO is detected but unhooked (docs/TBO_PATH.md). Empty"
                    + " for the best. Taken at each context's capability probe.");
    public static final CgVar.Text STREAM_TIER = CgVar.text("crystalgraphics.stream.tier", "", CgVar.DEV,
            "persistent or ring: how the frame ring uploads, refused where the context lacks it;"
                    + " empty for the best. Taken at each context's capability probe.");
    public static final CgVar.Bool BC7 = CgVar.bool("crystalgraphics.texture.bc7", true, CgVar.DEV,
            "Cooked BC7 textures uploaded as their blocks where the context has BPTC; false decodes them to RGBA8 as they"
                    + " load. Taken at each context's capability probe.");
    public static final CgVar.Bool DSA = CgVar.bool("crystalgraphics.gl.dsa", true, CgVar.DEV,
            "Blits between framebuffers by name where the context has direct state access, binding neither; false binds them.");
    public static final CgVar.Bool MULTI_DRAW =CgVar.bool("crystalgraphics.mesh.multiDraw", true, CgVar.DEV,
            "Runs of draws sharing pipeline, bindings and a slab joined into one multi-draw; false draws a call a draw, for"
                    + " a picture that must not change. Taken at each context's capability probe.");
    
    
    private static String cachedParsedVersionKey   = null;
    private static int[]  cachedParsedVersionValue = null;
    private static volatile CgCapabilities cachedCaps = null;

    // ─────────────────────────────────────────────────────────────────────────
    //  Enums
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Enumerates the available GPU-resident shader buffer paths in preference order.
     *
     * <p>SSBO, GL 4.3 core or ARB. {@code TBO} is never chosen: the buffer-texture path is unhooked, its detect
     * ({@code textureBufferMaterialPath}) kept, and {@code docs/TBO_PATH.md} says how to reconnect it. NONE means the
     * material pipeline cannot be used on this context.</p>
     */
    public enum ShaderBufferPath {
        /** Core OpenGL 4.3 SSBO. */
        SSBO_GL43,
        /** {@code GL_ARB_shader_storage_buffer_object} SSBO when core 4.3 is absent. */
        SSBO_ARB,
        /** Texture buffer objects (GL 3.1+, sampler-based): unhooked, never chosen ({@code docs/TBO_PATH.md}). */
        TBO,
        /** No usable shader buffer path. Material pipeline creation will throw. */
        NONE
    }

    /**
     * How a {@code CgStreamBuffer} uploads, best first.
     *
     * <p>{@code -Dcrystalgraphics.stream.tier=persistent|ring} forces one — for a driver that
     * misbehaves on the tier chosen, and for comparing them. Forcing a tier this context cannot do throws
     * from {@link #detect()}.</p>
     */
    /**
     * Where kernels run, best first, and every tier is forceable for a driver that misbehaves:
     * {@code -Dcrystalgraphics.compute.tier=g43}. Kernels run on {@code V} and {@code G43} alone; {@code G40} and
     * {@code G33} are still detected but nothing runs there (the lowered form was removed: {@code docs/LOWERED_COMPUTE.md}).
     */
    public enum ComputeTier {
        /** A device: compute passes on the device's compute queue. */
        V,
        /** GL compute shaders and storage images, with indirect counts and subgroups where the context lists them. */
        G43,
        /** No compute, transform feedback 2 (GL 4.0): where the lowered form ran. A kernel is refused here. */
        G40,
        /** GL 3.3: where the lowered form ran, reading counts back. A kernel is refused here. */
        G33;

        /** What this tier needs that the context lacks, or null. */
        String missing(CgCapabilities caps, boolean device) {
            switch (this) {
                case V:   return device ? null : "a device";
                case G43: return caps.compute && caps.storageImages ? null : "compute shaders and storage images (GL 4.3)";
                case G40: return device ? "transform feedback, which a device does not carry"
                        : caps.feedbackCount ? null : "transform feedback 2 (GL 4.0)";
                default:  return device ? "transform feedback, which a device does not carry" : null;
            }
        }
    }

    public enum StreamBufferTier {
        /** A frame ring in immutable storage mapped once for its life: no map call per upload. GL 4.4 / {@code ARB_buffer_storage}. */
        PERSISTENT,
        /** A frame ring mapped per upload, unsynchronised; one fence per frame. GL 3.2 sync and {@code glMapBufferRange}. */
        RING
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
     * The fragment stage's texture units, at most {@value #MAX_UNITS}: the most a binding table names.
     */
    public int getMaxTextureUnits() {
        return Math.min(maxTextureUnits, MAX_UNITS);
    }

    private static final int MAX_UNITS = 32;
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
     * extension without 4.3 — not worth an extra probe.</p>
     */
    @Getter(AccessLevel.NONE) boolean copyImageSubData;
    /** GL 4.5 or ARB_direct_state_access, on a GL driver: framebuffers blitted by name. */
    @Getter(AccessLevel.NONE) boolean directStateAccess;

    // ── Streaming — what CgStreamBuffer's waterfall picks a tier from ─────────
    /** Whether fence sync is available (Core GL32 or {@code GL_ARB_sync}). */
    boolean arbSync;
    /** Whether {@code glMapBufferRange} is available (Core GL30 or {@code GL_ARB_map_buffer_range}). */
    @Getter(AccessLevel.NONE) boolean hasMapBufferRange;
    /** Whether immutable, persistently mappable storage is available (Core GL44 or {@code GL_ARB_buffer_storage}). */
    @Getter(AccessLevel.NONE) boolean bufferStorage;
    /** Chosen from the three flags above and the override. @see StreamBufferTier */
    @Getter(AccessLevel.NONE)
    StreamBufferTier vertexStreamTier;

    // ── Instancing ────────────────────────────────────────────────────────────
    /** Maximum vertex attribute slots. A mat4 consumes 4 slots. */
    int maxVertexAttribs;

    // ── Shader buffers ────────────────────────────────────────────────────────
    /** Whether Core OpenGL 4.3 SSBO is available. */
    boolean shaderStorageBufferCore;
    /** Whether {@code GL_ARB_shader_storage_buffer_object} is available (and core 4.3 is absent). */
    boolean shaderStorageBufferArb;
    /** Whether the TBO path could run here (GL33+, no SSBO): detected, unhooked ({@code docs/TBO_PATH.md}). */
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
     * How many program binary formats the driver gives (GL 4.1 or {@code ARB_get_program_binary}): above 0, a linked
     * program's binary may be kept and loaded in a later launch. 0 on macOS, whose GL
     * lists none.
     */
    @Getter int programBinaryFormats;
    int glslVersion;

    // ── Compute and GPU-driven draws ──────────────────────────────────────────
    // Each answers what a consumer needs, joined from a core version, its ARB extension and, on the tracked
    // backend, the device. @see #computeTier
    @Getter(AccessLevel.NONE)
    boolean compute, storageImages, subgroups, floatAtomics, drawIndirect, multiDrawIndirect, indirectCount,
            multiDraw, feedbackCount, asyncCompute, bindless, independentBlend, bc7;
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
     * The installed device's capabilities, read at the first call and kept until {@link #clearCache()}. Render thread.
     *
     * @return the cached {@code CgCapabilities} for the installed device
     * @see #detectUncached()
     * @see #clearCache()
     */
    public static CgCapabilities detect() {
        CgCapabilities local = cachedCaps;
        if (local == null) {
            local = detectUncached();
            cachedCaps = local;
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
     * Detects capabilities from the installed device (uncached): its globals and limits, what a GL context lists no
     * longer asked. Render thread, with a device installed.
     *
     * @return a new {@code CgCapabilities} reflecting the installed device
     * @see #detect()
     */
    public static CgCapabilities detectUncached() {
        if (!CgRHI.installed()) throw new IllegalStateException("no device installed: capabilities are the device's");
        CgRHI rhi = CgRHI.get();
        CgRHI.Globals device = rhi.globals();
        CgRHI.Globals.Limits limits = device.limits();
        CgRHI.Globals.Compute compute = limits.compute();
        CgCapabilities caps = new CgCapabilities();

        caps.maxDrawBuffers      = limits.maxColorAttachments();
        caps.maxTextureUnits     = limits.maxTextureUnits();
        caps.maxTextureSize      = limits.maxTextureSize();
        caps.maxRenderbufferSize = limits.maxTextureSize();
        caps.maxColorAttachments = limits.maxColorAttachments();
        caps.maxVertexAttribs    = limits.maxVertexAttributes();
        caps.depth   = true;
        caps.stencil = true;
        caps.copyImageSubData = true;

        caps.arbSync           = true;
        caps.hasMapBufferRange = true;
        caps.bufferStorage     = true;
        caps.vertexStreamTier  = vertexStreamTier(caps);

        caps.shaderStorageBufferCore   = true;
        caps.maxSsboBindings           = limits.maxStorageBufferBindings();
        caps.maxUniformBufferBindings  = limits.maxUniformBufferBindings();
        caps.gpuShaderInt64            = true;
        caps.maxSamples                = limits.maxSamples();
        caps.maxTextureBufferSize      = limits.maxTexelBufferElements();

        caps.glslVersion = 450;
        caps.compute           = true;
        caps.storageImages     = true;
        caps.drawIndirect      = true;
        caps.multiDrawIndirect = device.multiDrawIndirect();
        caps.indirectCount     = device.indirectCount();
        if (!device.drawParameters() || !device.indirectFirstInstance()) {
            // Every draw carries its bases, read by cg_env.glsl (rhi D8).
            throw new IllegalStateException("CrystalGraphics needs a draw's base vertex and instance: GL 4.6, or "
                    + "ARB_shader_draw_parameters with ARB_base_instance; a Vulkan device's shaderDrawParameters and "
                    + "drawIndirectFirstInstance");
        }
        caps.multiDraw         = caps.multiDrawIndirect && MULTI_DRAW.get();
        caps.asyncCompute      = device.asyncCompute();
        caps.bindless          = device.bindless();
        caps.independentBlend  = device.independentBlend();
        caps.bc7               = rhi.supports(CgFormat.BC7_RGBA_UNORM_SRGB, CgGpuTexture.Usage.SAMPLED) && BC7.get();
        caps.computeTier       = computeTier(caps, true);
        caps.storageOffsetAlignment = limits.storageOffsetAlignment();
        caps.maxComputeSharedMemory = compute.sharedMemory();
        caps.maxComputeInvocations = compute.invocations();
        caps.maxComputeWorkGroupSize[0] = compute.sizeX();
        caps.maxComputeWorkGroupSize[1] = compute.sizeY();
        caps.maxComputeWorkGroupSize[2] = compute.sizeZ();
        caps.maxComputeWorkGroupCount[0] = compute.countX();
        caps.maxComputeWorkGroupCount[1] = compute.countY();
        caps.maxComputeWorkGroupCount[2] = compute.countZ();
        caps.subgroupSize = compute.subgroupSize();
        caps.subgroupOperations = compute.subgroupOperations();
        caps.subgroups = (caps.subgroupOperations & SUBGROUP_BASIC) != 0;

        caps.shaderBufferPath = shaderBufferPath(caps);
        return caps;
    }

    /** The best path, or {@code -Dcrystalgraphics.shaderBuffer.tier}'s where this context has it. */
    private static ShaderBufferPath shaderBufferPath(CgCapabilities caps) {
        // TBO would follow SSBO_ARB where caps.textureBufferMaterialPath; unhooked (docs/TBO_PATH.md)
        ShaderBufferPath best = caps.shaderStorageBufferCore ? ShaderBufferPath.SSBO_GL43
                : caps.shaderStorageBufferArb ? ShaderBufferPath.SSBO_ARB
                : ShaderBufferPath.NONE;
        String property = SHADER_BUFFER_TIER.get();
        if (property.isBlank()) return best;
        ShaderBufferPath forced = ShaderBufferPath.valueOf(property.trim().toUpperCase(Locale.ROOT));
        boolean has = switch (forced) {
            case SSBO_GL43 -> caps.shaderStorageBufferCore;
            case SSBO_ARB -> caps.shaderStorageBufferArb;
            case TBO, NONE -> false;   // TBO unhooked: docs/TBO_PATH.md
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
        String property = COMPUTE_TIER.get();
        if (property.isBlank()) return best;
        ComputeTier forced = ComputeTier.valueOf(property.trim().toUpperCase(Locale.ROOT));
        String missing = forced.missing(caps, device);
        if (missing != null) {
            throw new IllegalStateException("-Dcrystalgraphics.compute.tier=" + forced + " needs " + missing
                    + "; the best this context supports is " + best);
        }
        return forced;
    }

    private static StreamBufferTier vertexStreamTier(CgCapabilities caps) {
        StreamBufferTier best = caps.bufferStorage ? StreamBufferTier.PERSISTENT : StreamBufferTier.RING;
        String property = STREAM_TIER.get();
        if (property.isBlank()) return best;
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

    /** @see #directStateAccess */
    public boolean directStateAccess() { return directStateAccess; }

    /** Whether {@code glMapBufferRange} is supported (Core GL30 or {@code GL_ARB_map_buffer_range}). */
    public boolean isMapBufferRangeSupported() { return hasMapBufferRange; }

    /** Whether {@code glBufferStorage} and persistent mapping are supported (Core GL44 or {@code GL_ARB_buffer_storage}). */
    public boolean isBufferStorageSupported() { return bufferStorage; }

    /** Returns the preferred shader buffer path for the current GL context. */
    public ShaderBufferPath shaderBufferPath() { return shaderBufferPath; }

    /** The tier the frame ring takes: forced, or the best this context supports. */
    public StreamBufferTier vertexStreamTier() { return vertexStreamTier; }

    /** Kernels: GL 4.3 or {@code ARB_compute_shader}, with storage buffers; any device. */
    public boolean compute() { return compute; }

    /** {@code imageLoad} and {@code imageStore}: GL 4.2 or {@code ARB_shader_image_load_store}; any device. */
    public boolean storageImages() { return storageImages; }

    /** {@link #subgroupOperations()} bits, as {@code VkSubgroupFeatureFlags} and {@code KHR_shader_subgroup} name them. */
    public static final int SUBGROUP_BASIC = 0x1, SUBGROUP_VOTE = 0x2, SUBGROUP_ARITHMETIC = 0x4, SUBGROUP_BALLOT = 0x8,
            SUBGROUP_SHUFFLE = 0x10;

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

    /**
     * Meshes drawn as one multi-draw, each command carrying its bases: {@link #multiDrawIndirect()}.
     * {@code -Dcrystalgraphics.mesh.multiDraw=false} turns it off.
     */
    public boolean multiDraw() { return multiDraw; }

    /** A captured transform-feedback stream drawn by its own count: GL 4.0 or {@code ARB_transform_feedback2}. */
    public boolean feedbackCount() { return feedbackCount; }

    /** Kernels on a queue beside the frame's: a device with a compute queue of its own, the owned Vulkan device's. */
    public boolean asyncCompute() { return asyncCompute; }

    /** Textures by handle rather than by unit: {@code ARB_bindless_texture}. */
    public boolean bindless() { return bindless; }

    /**
     * A draw's colour attachments may differ in write mask ({@code glColorMaski}): every GL context, and a device
     * created with {@code independentBlend}: the owned device, and Minecraft 26.2's where the GPU has it.
     */
    public boolean independentBlend() { return independentBlend; }

    /**
     * BC7 textures uploaded as their blocks: GL 4.2 or {@code ARB_texture_compression_bptc}, or a device created with
     * {@code textureCompressionBC}. Where false a cooked texture is decoded to RGBA8 as it loads (macOS's 4.1);
     * {@code -Dcrystalgraphics.texture.bc7=false} forces that.
     */
    public boolean bc7() { return bc7; }

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

    /**
     * The highest tier this context runs kernels at, or {@code -Dcrystalgraphics.compute.tier}'s.
     */
    public ComputeTier computeTier() { return computeTier; }


    // ─────────────────────────────────────────────────────────────────────────
    //  GL version string parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a raw GL version string (e.g. {@code "4.6.0 NVIDIA 537.58"}) into a
     * {@code {major, minor}} array.
     *
     * <p>Accepts the raw string returned by {@code glGetString(GL_VERSION)}
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
