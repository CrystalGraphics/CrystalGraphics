package io.github.somehussar.crystalgraphics.api.texture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;

/**
 * Typed enum of all supported GL texture formats.
 *
 * <p>Single source of truth for the (internalFormat, baseFormat, type) triple
 * for both regular textures and FBO attachments. Used by {@link CgTextureSpec}
 * ({@code CgTextureSpec.builder().type(CgTextureType.RGBA8)}) and by
 * {@code CgFrameBufferFormat} ({@code .color(0, CgTextureType.RGBA8)}) in Phase 2.</p>
 *
 * <p>Zero hex literals — all GL values come from LWJGL imports.</p>
 *
 * <h3>Groups</h3>
 * <ul>
 *   <li>8-bit color: {@link #R8}, {@link #R8_SNORM}, {@link #R8I}, {@link #R8UI},
 *       {@link #RG8}, {@link #RG8I}, {@link #RG8UI},
 *       {@link #RGBA8}, {@link #RGBA8_SNORM}, {@link #RGBA8I}, {@link #RGBA8UI},
 *       {@link #SRGB8_ALPHA8}</li>
 *   <li>16-bit color: {@link #R16F}, {@link #R16I}, {@link #R16UI},
 *       {@link #RG16F}, {@link #RG16I}, {@link #RG16UI},
 *       {@link #RGBA16F}, {@link #RGBA16I}, {@link #RGBA16UI}</li>
 *   <li>32-bit color: {@link #R32F}, {@link #R32I}, {@link #R32UI},
 *       {@link #RG32F}, {@link #RG32I}, {@link #RG32UI},
 *       {@link #RGBA32F}, {@link #RGBA32I}, {@link #RGBA32UI}</li>
 *   <li>Packed / special color: {@link #RGB10_A2}, {@link #RGB10_A2UI},
 *       {@link #R11F_G11F_B10F}, {@link #RGBA4}, {@link #RGB5_A1}</li>
 *   <li>Depth: {@link #DEPTH16}, {@link #DEPTH24}, {@link #DEPTH32F}</li>
 *   <li>Depth + stencil: {@link #DEPTH24_STENCIL8}, {@link #DEPTH32F_STENCIL8}</li>
 *   <li>Stencil-only: {@link #STENCIL8}</li>
 * </ul>
 */
public enum CgTextureType {

    // ── 8-bit color ────────────────────────────────────────────────────────────
    R8          (GL30.GL_R8,              GL11.GL_RED,          GL11.GL_UNSIGNED_BYTE, false, false),
    R8_SNORM    (GL31.GL_R8_SNORM,        GL11.GL_RED,          GL11.GL_BYTE,          false, false),
    R8I         (GL30.GL_R8I,             GL30.GL_RED_INTEGER,  GL11.GL_BYTE,          false, false),
    R8UI        (GL30.GL_R8UI,            GL30.GL_RED_INTEGER,  GL11.GL_UNSIGNED_BYTE, false, false),
    RG8         (GL30.GL_RG8,             GL30.GL_RG,           GL11.GL_UNSIGNED_BYTE, false, false),
    RG8I        (GL30.GL_RG8I,            GL30.GL_RG_INTEGER,   GL11.GL_BYTE,          false, false),
    RG8UI       (GL30.GL_RG8UI,           GL30.GL_RG_INTEGER,   GL11.GL_UNSIGNED_BYTE, false, false),
    RGBA8       (GL11.GL_RGBA8,           GL11.GL_RGBA,         GL11.GL_UNSIGNED_BYTE, false, false),
    RGBA8_SNORM (GL31.GL_RGBA8_SNORM,     GL11.GL_RGBA,         GL11.GL_BYTE,          false, false),
    RGBA8I      (GL30.GL_RGBA8I,          GL30.GL_RGBA_INTEGER, GL11.GL_BYTE,          false, false),
    RGBA8UI     (GL30.GL_RGBA8UI,         GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_BYTE, false, false),
    SRGB8_ALPHA8(GL21.GL_SRGB8_ALPHA8,   GL11.GL_RGBA,         GL11.GL_UNSIGNED_BYTE, false, false),

    // ── 16-bit color ───────────────────────────────────────────────────────────
    R16F    (GL30.GL_R16F,    GL11.GL_RED,          GL30.GL_HALF_FLOAT,     false, false),
    R16I    (GL30.GL_R16I,    GL30.GL_RED_INTEGER,  GL11.GL_SHORT,          false, false),
    R16UI   (GL30.GL_R16UI,   GL30.GL_RED_INTEGER,  GL11.GL_UNSIGNED_SHORT, false, false),
    RG16F   (GL30.GL_RG16F,   GL30.GL_RG,           GL30.GL_HALF_FLOAT,     false, false),
    RG16I   (GL30.GL_RG16I,   GL30.GL_RG_INTEGER,   GL11.GL_SHORT,          false, false),
    RG16UI  (GL30.GL_RG16UI,  GL30.GL_RG_INTEGER,   GL11.GL_UNSIGNED_SHORT, false, false),
    RGBA16F (GL30.GL_RGBA16F, GL11.GL_RGBA,         GL30.GL_HALF_FLOAT,     false, false),
    RGBA16I (GL30.GL_RGBA16I, GL30.GL_RGBA_INTEGER, GL11.GL_SHORT,          false, false),
    RGBA16UI(GL30.GL_RGBA16UI,GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_SHORT, false, false),

    // ── 32-bit color ───────────────────────────────────────────────────────────
    R32F    (GL30.GL_R32F,    GL11.GL_RED,          GL11.GL_FLOAT,       false, false),
    R32I    (GL30.GL_R32I,    GL30.GL_RED_INTEGER,  GL11.GL_INT,         false, false),
    R32UI   (GL30.GL_R32UI,   GL30.GL_RED_INTEGER,  GL11.GL_UNSIGNED_INT,false, false),
    RG32F   (GL30.GL_RG32F,   GL30.GL_RG,           GL11.GL_FLOAT,       false, false),
    RG32I   (GL30.GL_RG32I,   GL30.GL_RG_INTEGER,   GL11.GL_INT,         false, false),
    RG32UI  (GL30.GL_RG32UI,  GL30.GL_RG_INTEGER,   GL11.GL_UNSIGNED_INT,false, false),
    RGBA32F (GL30.GL_RGBA32F, GL11.GL_RGBA,         GL11.GL_FLOAT,       false, false),
    RGBA32I (GL30.GL_RGBA32I, GL30.GL_RGBA_INTEGER, GL11.GL_INT,         false, false),
    RGBA32UI(GL30.GL_RGBA32UI,GL30.GL_RGBA_INTEGER, GL11.GL_UNSIGNED_INT,false, false),

    // ── Packed / special color ─────────────────────────────────────────────────
    RGB10_A2      (GL11.GL_RGB10_A2,       GL11.GL_RGBA,         GL12.GL_UNSIGNED_INT_2_10_10_10_REV,   false, false),
    /** Integer variant of RGB10_A2 — base format is RGBA_INTEGER. */
    RGB10_A2UI    (GL33.GL_RGB10_A2UI,     GL30.GL_RGBA_INTEGER, GL12.GL_UNSIGNED_INT_2_10_10_10_REV,   false, false),
    R11F_G11F_B10F(GL30.GL_R11F_G11F_B10F, GL11.GL_RGB,          GL30.GL_UNSIGNED_INT_10F_11F_11F_REV,  false, false),
    RGBA4         (GL11.GL_RGBA4,          GL11.GL_RGBA,         GL11.GL_UNSIGNED_BYTE,                 false, false),
    RGB5_A1       (GL11.GL_RGB5_A1,        GL11.GL_RGBA,         GL11.GL_UNSIGNED_BYTE,                 false, false),

    // ── Depth ──────────────────────────────────────────────────────────────────
    DEPTH16  (GL14.GL_DEPTH_COMPONENT16,  GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_SHORT, true, false),
    DEPTH24  (GL14.GL_DEPTH_COMPONENT24,  GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT,   true, false),
    DEPTH32F (GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,          true, false),

    // ── Depth + stencil ────────────────────────────────────────────────────────
    DEPTH24_STENCIL8 (GL30.GL_DEPTH24_STENCIL8,  GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8,              true, true),
    DEPTH32F_STENCIL8(GL30.GL_DEPTH32F_STENCIL8, GL30.GL_DEPTH_STENCIL, GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, true, true),

    // ── Stencil-only ───────────────────────────────────────────────────────────
    STENCIL8(GL30.GL_STENCIL_INDEX8, GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_BYTE, false, true);

    // ── Fields ─────────────────────────────────────────────────────────────────

    /** GL internal format constant (e.g. {@code GL_RGBA8}, {@code GL_DEPTH_COMPONENT24}). */
    public final int glInternalFormat;
    /** GL base/pixel format constant (e.g. {@code GL_RGBA}, {@code GL_DEPTH_COMPONENT}). */
    public final int glBaseFormat;
    /** GL pixel data type constant (e.g. {@code GL_UNSIGNED_BYTE}, {@code GL_FLOAT}). */
    public final int glType;
    /** {@code true} for depth and depth+stencil formats. */
    public final boolean isDepth;
    /** {@code true} for stencil and depth+stencil formats. */
    public final boolean isStencil;

    CgTextureType(int glInternalFormat, int glBaseFormat, int glType,
                  boolean isDepth, boolean isStencil) {
        this.glInternalFormat = glInternalFormat;
        this.glBaseFormat     = glBaseFormat;
        this.glType           = glType;
        this.isDepth          = isDepth;
        this.isStencil        = isStencil;
    }

    // ── Derived properties ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if this format is a pure color format
     * (neither depth nor stencil).
     */
    public boolean isColor() {
        return !isDepth && !isStencil;
    }

    /**
     * Returns the GL attachment point constant for use with
     * {@code glFramebufferTexture2D} / {@code glFramebufferRenderbuffer}:
     * <ul>
     *   <li>depth + stencil → {@code GL_DEPTH_STENCIL_ATTACHMENT}</li>
     *   <li>depth only      → {@code GL_DEPTH_ATTACHMENT}</li>
     *   <li>stencil only    → {@code GL_STENCIL_ATTACHMENT}</li>
     *   <li>color           → {@code GL_COLOR_ATTACHMENT0 + colorIndex}</li>
     * </ul>
     *
     * @param colorIndex 0-based color attachment slot (ignored for non-color formats)
     */
    public int glAttachmentPoint(int colorIndex) {
        if (isDepth && isStencil) return GL30.GL_DEPTH_STENCIL_ATTACHMENT;
        if (isDepth)              return GL30.GL_DEPTH_ATTACHMENT;
        if (isStencil)            return GL30.GL_STENCIL_ATTACHMENT;
        return GL30.GL_COLOR_ATTACHMENT0 + colorIndex;
    }

    /**
     * Builds a {@link CgTextureSpec} for this format using safe defaults:
     * clamp-to-edge wrapping, no mipmaps, and filter appropriate for the format.
     *
     * <p><strong>Filter selection</strong>: integer formats ({@code GL_RED_INTEGER},
     * {@code GL_RG_INTEGER}, {@code GL_RGBA_INTEGER}) use {@code GL_NEAREST} since
     * linear filtering is invalid on integer textures. All other formats use
     * {@code GL_LINEAR}.</p>
     *
     * <p><strong>Neutral depth spec</strong>: depth formats do NOT auto-enable shadow
     * comparison. Use {@link CgTextureSpec#DEPTH24_SHADOW} for PCF shadow sampling.</p>
     */
    public CgTextureSpec toTextureSpec() {
        // Integer formats cannot use linear filtering — GL_INVALID_OPERATION otherwise.
        boolean intFmt = glBaseFormat == GL30.GL_RED_INTEGER
                      || glBaseFormat == GL30.GL_RG_INTEGER
                      || glBaseFormat == GL30.GL_RGBA_INTEGER;
        int filter = intFmt ? GL11.GL_NEAREST : GL11.GL_LINEAR;
        return CgTextureSpec.builder()
                .type(this)
                .minFilter(filter)
                .magFilter(filter)
                .build();
    }

    /**
     * Probes whether this format is usable as an FBO attachment on the current hardware.
     *
     * <p>Phase 1 stub — always returns {@code true}. This will be replaced in Phase 2
     * (Task 5) when {@code CgFrameBuffer.probeRenderable(this)} becomes available.</p>
     *
     * @return {@code true} (Phase 1 stub; full probe implemented in Phase 2)
     * @see <a href="#TODO-Task5">TODO: Task 5 — wire to CgFrameBuffer.probeRenderable</a>
     */
    public boolean isRenderable() {
        // TODO: Task 5 — replace with CgFrameBuffer.probeRenderable(this)
        return true;
    }
}
