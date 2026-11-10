package com.crystalgraphics.api.texture;

import com.crystalgraphics.platform.gl.driver.GLC;



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
 *   <li>Block-compressed: {@link #BC7}, {@link #BC7_SRGB}: a cooked texture's, never a framebuffer's</li>
 * </ul>
 */
public enum CgTextureType {

    // ── 8-bit color ────────────────────────────────────────────────────────────
    R8          (GLC.GL_R8,              GLC.GL_RED,          GLC.GL_UNSIGNED_BYTE, false, false),
    R8_SNORM    (GLC.GL_R8_SNORM,        GLC.GL_RED,          GLC.GL_BYTE,          false, false),
    R8I         (GLC.GL_R8I,             GLC.GL_RED_INTEGER,  GLC.GL_BYTE,          false, false),
    R8UI        (GLC.GL_R8UI,            GLC.GL_RED_INTEGER,  GLC.GL_UNSIGNED_BYTE, false, false),
    RG8         (GLC.GL_RG8,             GLC.GL_RG,           GLC.GL_UNSIGNED_BYTE, false, false),
    RG8I        (GLC.GL_RG8I,            GLC.GL_RG_INTEGER,   GLC.GL_BYTE,          false, false),
    RG8UI       (GLC.GL_RG8UI,           GLC.GL_RG_INTEGER,   GLC.GL_UNSIGNED_BYTE, false, false),
    RGBA8       (GLC.GL_RGBA8,           GLC.GL_RGBA,         GLC.GL_UNSIGNED_BYTE, false, false),
    RGBA8_SNORM (GLC.GL_RGBA8_SNORM,     GLC.GL_RGBA,         GLC.GL_BYTE,          false, false),
    RGBA8I      (GLC.GL_RGBA8I,          GLC.GL_RGBA_INTEGER, GLC.GL_BYTE,          false, false),
    RGBA8UI     (GLC.GL_RGBA8UI,         GLC.GL_RGBA_INTEGER, GLC.GL_UNSIGNED_BYTE, false, false),
    SRGB8_ALPHA8(GLC.GL_SRGB8_ALPHA8,   GLC.GL_RGBA,         GLC.GL_UNSIGNED_BYTE, false, false),

    // ── 16-bit color ───────────────────────────────────────────────────────────
    R16F    (GLC.GL_R16F,    GLC.GL_RED,          GLC.GL_HALF_FLOAT,     false, false),
    R16I    (GLC.GL_R16I,    GLC.GL_RED_INTEGER,  GLC.GL_SHORT,          false, false),
    R16UI   (GLC.GL_R16UI,   GLC.GL_RED_INTEGER,  GLC.GL_UNSIGNED_SHORT, false, false),
    RG16F   (GLC.GL_RG16F,   GLC.GL_RG,           GLC.GL_HALF_FLOAT,     false, false),
    RG16I   (GLC.GL_RG16I,   GLC.GL_RG_INTEGER,   GLC.GL_SHORT,          false, false),
    RG16UI  (GLC.GL_RG16UI,  GLC.GL_RG_INTEGER,   GLC.GL_UNSIGNED_SHORT, false, false),
    RGBA16F (GLC.GL_RGBA16F, GLC.GL_RGBA,         GLC.GL_HALF_FLOAT,     false, false),
    RGBA16I (GLC.GL_RGBA16I, GLC.GL_RGBA_INTEGER, GLC.GL_SHORT,          false, false),
    RGBA16UI(GLC.GL_RGBA16UI,GLC.GL_RGBA_INTEGER, GLC.GL_UNSIGNED_SHORT, false, false),

    // ── 32-bit color ───────────────────────────────────────────────────────────
    R32F    (GLC.GL_R32F,    GLC.GL_RED,          GLC.GL_FLOAT,       false, false),
    R32I    (GLC.GL_R32I,    GLC.GL_RED_INTEGER,  GLC.GL_INT,         false, false),
    R32UI   (GLC.GL_R32UI,   GLC.GL_RED_INTEGER,  GLC.GL_UNSIGNED_INT,false, false),
    RG32F   (GLC.GL_RG32F,   GLC.GL_RG,           GLC.GL_FLOAT,       false, false),
    RG32I   (GLC.GL_RG32I,   GLC.GL_RG_INTEGER,   GLC.GL_INT,         false, false),
    RG32UI  (GLC.GL_RG32UI,  GLC.GL_RG_INTEGER,   GLC.GL_UNSIGNED_INT,false, false),
    RGBA32F (GLC.GL_RGBA32F, GLC.GL_RGBA,         GLC.GL_FLOAT,       false, false),
    RGBA32I (GLC.GL_RGBA32I, GLC.GL_RGBA_INTEGER, GLC.GL_INT,         false, false),
    RGBA32UI(GLC.GL_RGBA32UI,GLC.GL_RGBA_INTEGER, GLC.GL_UNSIGNED_INT,false, false),

    // ── Packed / special color ─────────────────────────────────────────────────
    RGB10_A2      (GLC.GL_RGB10_A2,       GLC.GL_RGBA,         GLC.GL_UNSIGNED_INT_2_10_10_10_REV,   false, false),
    /** Integer variant of RGB10_A2 — base format is RGBA_INTEGER. */
    RGB10_A2UI    (GLC.GL_RGB10_A2UI,     GLC.GL_RGBA_INTEGER, GLC.GL_UNSIGNED_INT_2_10_10_10_REV,   false, false),
    R11F_G11F_B10F(GLC.GL_R11F_G11F_B10F, GLC.GL_RGB,          GLC.GL_UNSIGNED_INT_10F_11F_11F_REV,  false, false),
    RGBA4         (GLC.GL_RGBA4,          GLC.GL_RGBA,         GLC.GL_UNSIGNED_BYTE,                 false, false),
    RGB5_A1       (GLC.GL_RGB5_A1,        GLC.GL_RGBA,         GLC.GL_UNSIGNED_BYTE,                 false, false),

    // ── Depth ──────────────────────────────────────────────────────────────────
    DEPTH16  (GLC.GL_DEPTH_COMPONENT16,  GLC.GL_DEPTH_COMPONENT, GLC.GL_UNSIGNED_SHORT, true, false),
    DEPTH24  (GLC.GL_DEPTH_COMPONENT24,  GLC.GL_DEPTH_COMPONENT, GLC.GL_UNSIGNED_INT,   true, false),
    DEPTH32F (GLC.GL_DEPTH_COMPONENT32F, GLC.GL_DEPTH_COMPONENT, GLC.GL_FLOAT,          true, false),

    // ── Depth + stencil ────────────────────────────────────────────────────────
    DEPTH24_STENCIL8 (GLC.GL_DEPTH24_STENCIL8,  GLC.GL_DEPTH_STENCIL, GLC.GL_UNSIGNED_INT_24_8,              true, true),
    DEPTH32F_STENCIL8(GLC.GL_DEPTH32F_STENCIL8, GLC.GL_DEPTH_STENCIL, GLC.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, true, true),

    // ── Stencil-only ───────────────────────────────────────────────────────────
    STENCIL8(GLC.GL_STENCIL_INDEX8, GLC.GL_STENCIL_INDEX, GLC.GL_UNSIGNED_BYTE, false, true),

    // ── Block-compressed: sampled only, uploaded as blocks ────────────────────────────────────────────────────
    BC7     (GLC.GL_COMPRESSED_RGBA_BPTC_UNORM,       GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE, false, false),
    BC7_SRGB(GLC.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM, GLC.GL_RGBA, GLC.GL_UNSIGNED_BYTE, false, false);

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
     * Whether it holds floats (half, full or packed 11/11/10): a colour target that stores light linearly, so a blend
     * into it mixes light, not encoded values.
     */
    public boolean isFloat() {
        return glType == GLC.GL_HALF_FLOAT || glType == GLC.GL_FLOAT || this == R11F_G11F_B10F;
    }

    /** Whether it is stored as 4x4 blocks (BC7): uploaded with {@code glCompressedTexImage2D}, never drawn to. */
    public boolean isCompressed() {
        return this == BC7 || this == BC7_SRGB;
    }

    /** What its blocks decode to where the context cannot sample them: {@link #RGBA8} or {@link #SRGB8_ALPHA8}. */
    public CgTextureType decoded() {
        return this == BC7 ? RGBA8 : this == BC7_SRGB ? SRGB8_ALPHA8 : this;
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
        if (isDepth && isStencil) return GLC.GL_DEPTH_STENCIL_ATTACHMENT;
        if (isDepth)              return GLC.GL_DEPTH_ATTACHMENT;
        if (isStencil)            return GLC.GL_STENCIL_ATTACHMENT;
        return GLC.GL_COLOR_ATTACHMENT0 + colorIndex;
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
        boolean intFmt = glBaseFormat == GLC.GL_RED_INTEGER
                      || glBaseFormat == GLC.GL_RG_INTEGER
                      || glBaseFormat == GLC.GL_RGBA_INTEGER;
        int filter = intFmt ? GLC.GL_NEAREST : GLC.GL_LINEAR;
        return CgTextureSpec.builder()
                .type(this)
                .minFilter(filter)
                .magFilter(filter)
                .build();
    }
}
