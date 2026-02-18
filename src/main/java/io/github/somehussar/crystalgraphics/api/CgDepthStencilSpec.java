package io.github.somehussar.crystalgraphics.api;

/**
 * Immutable specification of depth and stencil attachment configuration.
 *
 * <p>This value object encapsulates depth and stencil buffer configuration
 * for framebuffer objects. CrystalGraphics supports three modes:</p>
 * <ul>
 *   <li><strong>None</strong>: No depth or stencil attachments</li>
 *   <li><strong>Packed</strong>: A single packed depth-stencil texture
 *       (e.g., {@code GL_DEPTH24_STENCIL8}), available on all supported
 *       hardware</li>
 *   <li><strong>Separate</strong>: Independent depth and stencil textures,
 *       requiring separate attachment to the FBO</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe. Validation ensures that
 * conflicting configurations (e.g., both packed and separate) cannot be
 * created.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * // No depth/stencil
 * CgDepthStencilSpec none = CgDepthStencilSpec.none();
 *
 * // Packed depth-stencil (most common)
 * CgDepthStencilSpec packed = CgDepthStencilSpec.packedDepthStencil(0x88F0); // GL_DEPTH24_STENCIL8
 *
 * // Depth only (packed is not applicable)
 * CgDepthStencilSpec depthOnly = CgDepthStencilSpec.depthOnly(0x1902); // GL_DEPTH_COMPONENT
 * </pre>
 */
public final class CgDepthStencilSpec {

    /** Singleton instance representing no depth/stencil. */
    private static final CgDepthStencilSpec NONE = new CgDepthStencilSpec(
            false, false, false, false,
            0, 0, 0
    );

    /** Whether depth attachment is requested. */
    private final boolean hasDepth;

    /** Whether stencil attachment is requested. */
    private final boolean hasStencil;

    /** Whether depth and stencil are packed in a single attachment. */
    private final boolean isPacked;

    /**
     * Whether depth should be allocated as a texture instead of a renderbuffer.
     *
     * <p>When {@code true}, the depth attachment is created as a {@code GL_TEXTURE_2D}
     * and can be sampled in shaders (e.g., for shadow mapping). Requires
     * {@code GL_ARB_depth_texture} support, which is validated at creation time
     * by {@link io.github.somehussar.crystalgraphics.gl.framebuffer.CgFramebufferFactory}.</p>
     *
     * <p><strong>Note:</strong> Depth-as-texture is only supported for the
     * {@linkplain #depthOnly(int) depthOnly} and {@linkplain #depthOnlyTexture(int) depthOnlyTexture}
     * modes. Stencil sampling from a packed depth-stencil texture is NOT supported
     * on Minecraft 1.7.10-era OpenGL (LWJGL 2.9 / GL 2.0–3.0 range). Do not use
     * depth-as-texture with packed depth-stencil specs.</p>
     */
    private final boolean depthAsTexture;

    /** Depth format constant (only meaningful if {@code hasDepth}). */
    private final int depthFormat;

    /** Stencil format constant (only meaningful if {@code hasStencil}). */
    private final int stencilFormat;

    /** Packed depth-stencil format constant (only meaningful if {@code isPacked}). */
    private final int packedFormat;

    /**
     * Private constructor; use factory methods instead.
     *
     * @param hasDepth        whether depth is attached
     * @param hasStencil      whether stencil is attached
     * @param isPacked        whether depth and stencil are packed
     * @param depthAsTexture  whether depth should be a texture instead of renderbuffer
     * @param depthFormat     depth format constant
     * @param stencilFormat   stencil format constant
     * @param packedFormat    packed depth-stencil format constant
     */
    private CgDepthStencilSpec(boolean hasDepth, boolean hasStencil, boolean isPacked,
                               boolean depthAsTexture,
                               int depthFormat, int stencilFormat, int packedFormat) {
        this.hasDepth = hasDepth;
        this.hasStencil = hasStencil;
        this.isPacked = isPacked;
        this.depthAsTexture = depthAsTexture;
        this.depthFormat = depthFormat;
        this.stencilFormat = stencilFormat;
        this.packedFormat = packedFormat;
    }

    /**
     * Returns a depth-stencil specification with no attachments.
     *
     * <p>The returned instance is a singleton and safe for reuse.</p>
     *
     * @return a {@code CgDepthStencilSpec} with no depth or stencil
     */
    public static CgDepthStencilSpec none() {
        return NONE;
    }

    /**
     * Returns a depth-stencil specification with a packed depth-stencil attachment.
     *
     * <p>Packed depth-stencil is the most efficient and widely supported
     * configuration. Both depth and stencil are stored in a single texture
     * (e.g., {@code GL_DEPTH24_STENCIL8}).</p>
     *
     * @param packedFormat the OpenGL format constant for packed depth-stencil
     *                     (e.g., {@code 0x88F0} for {@code GL_DEPTH24_STENCIL8})
     * @return a packed depth-stencil {@code CgDepthStencilSpec}
     * @throws IllegalArgumentException if {@code packedFormat} is not positive
     */
    public static CgDepthStencilSpec packedDepthStencil(int packedFormat) {
        if (packedFormat <= 0) {
            throw new IllegalArgumentException("Packed format must be positive, got: " + packedFormat);
        }
        return new CgDepthStencilSpec(true, true, true, false, 0, 0, packedFormat);
    }

    /**
     * Returns a depth-stencil specification with only depth attachment.
     *
     * <p>Stencil is not attached. This is useful for applications that do
     * not require stencil functionality and want to save memory.</p>
     *
     * @param depthFormat the OpenGL format constant for depth
     *                    (e.g., {@code 0x1902} for {@code GL_DEPTH_COMPONENT})
     * @return a depth-only {@code CgDepthStencilSpec}
     * @throws IllegalArgumentException if {@code depthFormat} is not positive
     */
    public static CgDepthStencilSpec depthOnly(int depthFormat) {
        if (depthFormat <= 0) {
            throw new IllegalArgumentException("Depth format must be positive, got: " + depthFormat);
        }
        return new CgDepthStencilSpec(true, false, false, false, depthFormat, 0, 0);
    }

    /**
     * Returns a depth-stencil specification with only depth attachment,
     * allocated as a texture instead of a renderbuffer.
     *
     * <p>This allows the depth buffer to be sampled in shaders (e.g., for
     * shadow mapping). Requires {@code GL_ARB_depth_texture} hardware support,
     * validated at framebuffer creation time.</p>
     *
     * <p><strong>Important:</strong> Stencil sampling from a packed depth-stencil
     * texture is NOT supported on Minecraft 1.7.10-era OpenGL (LWJGL 2.9 /
     * GL 2.0–3.0 range). This mode is only valid for depth-only configurations.</p>
     *
     * @param depthFormat the OpenGL internal format constant for depth
     *                    (e.g., {@code 0x81A5} for {@code GL_DEPTH_COMPONENT16},
     *                     {@code 0x81A6} for {@code GL_DEPTH_COMPONENT24},
     *                     {@code 0x81A7} for {@code GL_DEPTH_COMPONENT32})
     * @return a depth-only texture {@code CgDepthStencilSpec}
     * @throws IllegalArgumentException if {@code depthFormat} is not positive
     */
    public static CgDepthStencilSpec depthOnlyTexture(int depthFormat) {
        if (depthFormat <= 0) {
            throw new IllegalArgumentException("Depth format must be positive, got: " + depthFormat);
        }
        return new CgDepthStencilSpec(true, false, false, true, depthFormat, 0, 0);
    }

    /**
     * Returns a depth-stencil specification with only stencil attachment.
     *
     * <p>Depth is not attached. This is rarely used in practice.</p>
     *
     * @param stencilFormat the OpenGL format constant for stencil
     *                      (e.g., {@code 0x1901} for {@code GL_STENCIL_INDEX})
     * @return a stencil-only {@code CgDepthStencilSpec}
     * @throws IllegalArgumentException if {@code stencilFormat} is not positive
     */
    public static CgDepthStencilSpec stencilOnly(int stencilFormat) {
        if (stencilFormat <= 0) {
            throw new IllegalArgumentException("Stencil format must be positive, got: " + stencilFormat);
        }
        return new CgDepthStencilSpec(false, true, false, false, 0, stencilFormat, 0);
    }

    /**
     * Returns a depth-stencil specification with separate depth and stencil attachments.
     *
     * <p>Depth and stencil are attached as separate textures. This is less
     * efficient than packed depth-stencil but provides more flexibility.</p>
     *
     * @param depthFormat   the OpenGL format constant for depth
     * @param stencilFormat the OpenGL format constant for stencil
     * @return a separate depth-stencil {@code CgDepthStencilSpec}
     * @throws IllegalArgumentException if either format is not positive
     */
    public static CgDepthStencilSpec separate(int depthFormat, int stencilFormat) {
        if (depthFormat <= 0) {
            throw new IllegalArgumentException("Depth format must be positive, got: " + depthFormat);
        }
        if (stencilFormat <= 0) {
            throw new IllegalArgumentException("Stencil format must be positive, got: " + stencilFormat);
        }
        return new CgDepthStencilSpec(true, true, false, false, depthFormat, stencilFormat, 0);
    }

    /**
     * Returns whether depth attachment is configured.
     *
     * @return {@code true} if a depth buffer is requested
     */
    public boolean hasDepth() {
        return hasDepth;
    }

    /**
     * Returns whether stencil attachment is configured.
     *
     * @return {@code true} if a stencil buffer is requested
     */
    public boolean hasStencil() {
        return hasStencil;
    }

    /**
     * Returns whether depth and stencil are packed into a single attachment.
     *
     * @return {@code true} if using packed depth-stencil; {@code false} if
     *         using separate attachments or if neither is configured
     */
    public boolean isPacked() {
        return isPacked;
    }

    /**
     * Returns whether depth should be allocated as a texture instead of a renderbuffer.
     *
     * <p>When {@code true}, the depth attachment will be a {@code GL_TEXTURE_2D} that
     * can be sampled in shaders. Only valid for depth-only configurations; NOT
     * supported with packed depth-stencil on 1.7.10-era GL.</p>
     *
     * @return {@code true} if depth is a texture; {@code false} if a renderbuffer (default)
     */
    public boolean isDepthTexture() {
        return depthAsTexture;
    }

    /**
     * Returns the depth format constant.
     *
     * <p>This value is only meaningful if {@link #hasDepth()} returns {@code true}
     * and {@link #isPacked()} returns {@code false}.</p>
     *
     * @return the depth format constant
     */
    public int getDepthFormat() {
        return depthFormat;
    }

    /**
     * Returns the stencil format constant.
     *
     * <p>This value is only meaningful if {@link #hasStencil()} returns {@code true}
     * and {@link #isPacked()} returns {@code false}.</p>
     *
     * @return the stencil format constant
     */
    public int getStencilFormat() {
        return stencilFormat;
    }

    /**
     * Returns the packed depth-stencil format constant.
     *
     * <p>This value is only meaningful if {@link #isPacked()} returns {@code true}.</p>
     *
     * @return the packed depth-stencil format constant
     */
    public int getPackedFormat() {
        return packedFormat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgDepthStencilSpec that = (CgDepthStencilSpec) o;

        if (hasDepth != that.hasDepth) return false;
        if (hasStencil != that.hasStencil) return false;
        if (isPacked != that.isPacked) return false;
        if (depthAsTexture != that.depthAsTexture) return false;
        if (isPacked) {
            if (packedFormat != that.packedFormat) return false;
        } else {
            if (hasDepth && depthFormat != that.depthFormat) return false;
            if (hasStencil && stencilFormat != that.stencilFormat) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = hasDepth ? 1 : 0;
        result = 31 * result + (hasStencil ? 1 : 0);
        result = 31 * result + (isPacked ? 1 : 0);
        result = 31 * result + (depthAsTexture ? 1 : 0);
        if (isPacked) {
            result = 31 * result + packedFormat;
        } else {
            if (hasDepth) result = 31 * result + depthFormat;
            if (hasStencil) result = 31 * result + stencilFormat;
        }
        return result;
    }

    @Override
    public String toString() {
        if (!hasDepth && !hasStencil) {
            return "CgDepthStencilSpec{none}";
        }
        if (isPacked) {
            return "CgDepthStencilSpec{packed, format=" + packedFormat + '}';
        }
        StringBuilder sb = new StringBuilder("CgDepthStencilSpec{");
        if (hasDepth) {
            sb.append("depth=").append(depthFormat);
            if (depthAsTexture) {
                sb.append("(texture)");
            }
        }
        if (hasStencil) {
            if (hasDepth) sb.append(", ");
            sb.append("stencil=").append(stencilFormat);
        }
        sb.append('}');
        return sb.toString();
    }
}
