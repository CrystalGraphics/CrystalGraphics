package io.github.somehussar.crystalgraphics.api;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

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
 * @see Backend
 */
public final class CgCapabilities {

    /**
     * Enumerates the available framebuffer object backends in order of
     * preference.
     *
     * <p>The preferred backend is selected by
     * {@link CgCapabilities#preferredFboBackend()} based on the detected
     * hardware capabilities.</p>
     */
    public enum Backend {

        /**
         * Core OpenGL 3.0 framebuffer support.
         * This is the preferred backend, offering full feature coverage
         * including separate draw/read targets and MRT.
         */
        CORE_GL30,

        /**
         * ARB framebuffer object extension ({@code GL_ARB_framebuffer_object}).
         * Semantically identical to Core GL30 but routed through the ARB
         * extension entry point.  Supports separate draw/read targets and MRT.
         */
        ARB_FBO,

        /**
         * EXT framebuffer object extension ({@code GL_EXT_framebuffer_object}).
         * Legacy path with {@code *EXT}-suffixed methods.  Does not support
         * separate draw/read targets and may lack MRT support.
         */
        EXT_FBO,

        /**
         * No framebuffer support detected.  FBO creation will fail.
         */
        NONE
    }

    /** Whether Core OpenGL 3.0 framebuffer support is available. */
    private final boolean coreFbo;

    /** Whether the {@code GL_ARB_framebuffer_object} extension is available. */
    private final boolean arbFbo;

    /** Whether the {@code GL_EXT_framebuffer_object} extension is available. */
    private final boolean extFbo;

    /** Whether Core OpenGL 2.0 shader support ({@code glUseProgram} etc.) is available. */
    private final boolean coreShaders;

    /** Whether the {@code GL_ARB_shader_objects} extension is available. */
    private final boolean arbShaders;

    /**
     * Maximum number of simultaneous draw buffer outputs (MRT).
     * A value of 1 means MRT is not supported or not available.
     */
    private final int maxDrawBuffers;

    /**
     * Maximum number of texture image units available for fragment shaders,
     * or the number of fixed-function texture units if shaders are unavailable.
     */
    private final int maxTextureUnits;

    /** Whether stencil buffer attachments are supported. */
    private final boolean stencil;

    /** Whether depth buffer attachments are supported. */
    private final boolean depth;

    /** Whether packed depth-stencil formats are supported. */
    private final boolean packedDepthStencil;

    /** Whether depth textures are supported via {@code GL_ARB_depth_texture}. */
    private final boolean depthTexture;

    /** Maximum texture dimension (width/height) for 2D textures. */
    private final int maxTextureSize;

    /** Maximum renderbuffer dimension (width/height). */
    private final int maxRenderbufferSize;

    /** Maximum number of color attachments available on FBOs. */
    private final int maxColorAttachments;

    /**
     * Private constructor; use {@link #detect()} to create instances.
     *
     * @param coreFbo               Core GL30 FBO support
     * @param arbFbo                ARB FBO extension support
     * @param extFbo                EXT FBO extension support
     * @param coreShaders           Core GL20 shader support
     * @param arbShaders            ARB shader objects support
     * @param maxDrawBuffers        maximum MRT outputs (1 if unsupported)
     * @param maxTextureUnits       maximum texture units
     * @param stencil               stencil attachment support
     * @param depth                 depth attachment support
     * @param packedDepthStencil    packed depth-stencil format support
     * @param depthTexture          depth texture support
     * @param maxTextureSize        maximum texture dimension
     * @param maxRenderbufferSize   maximum renderbuffer dimension
     * @param maxColorAttachments   maximum color attachments
     */
    private CgCapabilities(boolean coreFbo, boolean arbFbo, boolean extFbo,
                           boolean coreShaders, boolean arbShaders,
                           int maxDrawBuffers, int maxTextureUnits,
                           boolean stencil, boolean depth,
                           boolean packedDepthStencil, boolean depthTexture,
                           int maxTextureSize, int maxRenderbufferSize,
                           int maxColorAttachments) {
        this.coreFbo = coreFbo;
        this.arbFbo = arbFbo;
        this.extFbo = extFbo;
        this.coreShaders = coreShaders;
        this.arbShaders = arbShaders;
        this.maxDrawBuffers = maxDrawBuffers;
        this.maxTextureUnits = maxTextureUnits;
        this.stencil = stencil;
        this.depth = depth;
        this.packedDepthStencil = packedDepthStencil;
        this.depthTexture = depthTexture;
        this.maxTextureSize = maxTextureSize;
        this.maxRenderbufferSize = maxRenderbufferSize;
        this.maxColorAttachments = maxColorAttachments;
    }

    /**
     * Detects capabilities from the current OpenGL context.
     *
     * <p>Must be called on the render thread with an active GL context.
     * This method queries LWJGL's {@link GLContext#getCapabilities()} and
     * relevant {@code glGetInteger} values to populate all capability
     * fields.</p>
     *
     * <p>Depth and stencil support are assumed to be universally available
     * on the target hardware range (OpenGL 2.0+ / Intel HD 3000 and above).</p>
     *
     * @return a new {@code CgCapabilities} reflecting the current context
     */
    public static CgCapabilities detect() {
        ContextCapabilities caps = GLContext.getCapabilities();

        boolean coreFbo = caps.OpenGL30;
        boolean arbFbo = caps.GL_ARB_framebuffer_object;
        boolean extFbo = caps.GL_EXT_framebuffer_object;
        boolean coreShaders = caps.OpenGL20;
        boolean arbShaders = caps.GL_ARB_shader_objects;

        int maxDrawBuffers;
        if (coreShaders) {
            maxDrawBuffers = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        } else {
            maxDrawBuffers = 1;
        }

        int maxTextureUnits;
        if (coreShaders) {
            maxTextureUnits = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
        } else {
            maxTextureUnits = GL11.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        }

        // Depth and stencil are universally supported on the target hardware
        // range (Intel HD 3000 / OpenGL 2.0+ and above).
        boolean depth = true;
        boolean stencil = true;

        // Packed depth-stencil support: EXT or NV variant
        boolean packedDepthStencil = caps.GL_EXT_packed_depth_stencil || caps.GL_NV_packed_depth_stencil;

        // Depth texture support via ARB extension
        boolean depthTexture = caps.GL_ARB_depth_texture;

        // Maximum texture size (universal, always available)
        int maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);

        // Maximum renderbuffer size; use maxTextureSize as fallback if unavailable
        int maxRenderbufferSize;
        if (coreFbo || arbFbo) {
            maxRenderbufferSize = GL11.glGetInteger(0x84E8); // GL_MAX_RENDERBUFFER_SIZE
        } else {
            maxRenderbufferSize = maxTextureSize;
        }

        // Maximum color attachments; varies by backend
        int maxColorAttachments;
        if (coreFbo || arbFbo) {
            maxColorAttachments = GL11.glGetInteger(0x8CDF); // GL_MAX_COLOR_ATTACHMENTS
        } else if (extFbo) {
            // EXT framebuffer object typically limited to 1 color attachment
            maxColorAttachments = 1;
        } else {
            maxColorAttachments = 1;
        }

        return new CgCapabilities(
            coreFbo, arbFbo, extFbo,
            coreShaders, arbShaders,
            maxDrawBuffers, maxTextureUnits,
            stencil, depth,
            packedDepthStencil, depthTexture,
            maxTextureSize, maxRenderbufferSize,
            maxColorAttachments
        );
    }

    /**
     * Returns the preferred framebuffer object backend based on detected
     * capabilities, using the waterfall order: Core GL30 &gt; ARB &gt; EXT.
     *
     * @return the best available {@link Backend}, or {@link Backend#NONE}
     *         if no FBO support was detected
     */
    public Backend preferredFboBackend() {
        if (coreFbo) {
            return Backend.CORE_GL30;
        }
        if (arbFbo) {
            return Backend.ARB_FBO;
        }
        if (extFbo) {
            return Backend.EXT_FBO;
        }
        return Backend.NONE;
    }

    /**
     * Returns whether Core OpenGL 3.0 framebuffer support is available.
     *
     * @return {@code true} if {@code GL30.glBindFramebuffer} and related
     *         methods are available
     */
    public boolean isCoreFbo() {
        return coreFbo;
    }

    /**
     * Returns whether the {@code GL_ARB_framebuffer_object} extension is
     * available.
     *
     * @return {@code true} if {@code ARBFramebufferObject.glBindFramebuffer}
     *         and related methods are available
     */
    public boolean isArbFbo() {
        return arbFbo;
    }

    /**
     * Returns whether the {@code GL_EXT_framebuffer_object} extension is
     * available.
     *
     * @return {@code true} if {@code EXTFramebufferObject.glBindFramebufferEXT}
     *         and related methods are available
     */
    public boolean isExtFbo() {
        return extFbo;
    }

    /**
     * Returns whether Core OpenGL 2.0 shader support is available.
     *
     * @return {@code true} if {@code GL20.glUseProgram} and related
     *         methods are available
     */
    public boolean isCoreShaders() {
        return coreShaders;
    }

    /**
     * Returns whether the {@code GL_ARB_shader_objects} extension is available.
     *
     * @return {@code true} if {@code ARBShaderObjects.glUseProgramObjectARB}
     *         and related methods are available
     */
    public boolean isArbShaders() {
        return arbShaders;
    }

    /**
     * Returns the maximum number of simultaneous draw buffer outputs (MRT).
     *
     * <p>A value of 1 means only a single color attachment can be drawn to
     * at a time (no MRT support).  Values greater than 1 indicate the
     * number of {@code GL_COLOR_ATTACHMENTi} targets that can be active
     * simultaneously via {@code glDrawBuffers}.</p>
     *
     * @return the maximum number of draw buffers (at least 1)
     */
    public int getMaxDrawBuffers() {
        return maxDrawBuffers;
    }

    /**
     * Returns the maximum number of texture units available.
     *
     * <p>When shaders are supported, this returns the number of texture
     * image units available for fragment shader samplers
     * ({@code GL_MAX_TEXTURE_IMAGE_UNITS}).  Otherwise, it returns the
     * number of fixed-function texture units ({@code GL_MAX_TEXTURE_UNITS}).</p>
     *
     * @return the maximum number of texture units (at least 1)
     */
    public int getMaxTextureUnits() {
        return maxTextureUnits;
    }

    /**
     * Returns whether stencil buffer attachments are supported.
     *
     * @return {@code true} if stencil attachments can be created on FBOs
     */
    public boolean hasStencil() {
        return stencil;
    }

    /**
     * Returns whether depth buffer attachments are supported.
     *
     * @return {@code true} if depth attachments can be created on FBOs
     */
    public boolean hasDepth() {
        return depth;
    }

    /**
     * Returns whether packed depth-stencil formats are supported.
     *
     * <p>Packed depth-stencil allows a single renderbuffer or texture to
     * store both depth and stencil data, reducing memory usage and improving
     * performance.  This is indicated by either {@code GL_EXT_packed_depth_stencil}
     * or {@code GL_NV_packed_depth_stencil} extension support.</p>
     *
     * @return {@code true} if packed depth-stencil formats are available
     */
    public boolean hasPackedDepthStencil() {
        return packedDepthStencil;
    }

    /**
     * Returns whether depth textures are supported via the
     * {@code GL_ARB_depth_texture} extension.
     *
     * <p>Depth textures allow the depth buffer to be sampled as a texture,
     * enabling shadow mapping and other advanced rendering techniques.</p>
     *
     * @return {@code true} if depth textures can be created and sampled
     */
    public boolean hasDepthTexture() {
        return depthTexture;
    }

    /**
     * Returns the maximum texture dimension (width/height) for 2D textures.
     *
     * <p>This is the maximum size of a single 2D texture in either dimension.
     * Typical values are 2048, 4096, 8192, or 16384 depending on GPU
     * generation and driver.</p>
     *
     * @return the maximum texture size in pixels (at least 64)
     */
    public int getMaxTextureSize() {
        return maxTextureSize;
    }

    /**
     * Returns the maximum renderbuffer dimension (width/height).
     *
     * <p>This is the maximum size of a renderbuffer attachment on an FBO.
     * On hardware supporting {@code GL_ARB_framebuffer_object} or Core GL3.0+,
     * this typically matches {@code GL_MAX_TEXTURE_SIZE}.  On older hardware
     * with only {@code GL_EXT_framebuffer_object}, it may be smaller or
     * equal to the maximum texture size.</p>
     *
     * @return the maximum renderbuffer size in pixels
     */
    public int getMaxRenderbufferSize() {
        return maxRenderbufferSize;
    }

    /**
     * Returns the maximum number of color attachments available on FBOs.
     *
     * <p>This determines how many simultaneous color render targets (MRT)
     * can be bound to an FBO via {@code glDrawBuffers}.  Typical values:
     * <ul>
     *   <li>1 - Only single-target rendering (EXT framebuffer)</li>
     *   <li>8 - Modern Core/ARB framebuffer (typical default)</li>
     *   <li>16 - High-end GPUs</li>
     * </ul></p>
     *
     * @return the maximum number of color attachments (at least 1)
     */
    public int getMaxColorAttachments() {
        return maxColorAttachments;
    }
}
