package com.crystalgraphics.compute.source;

/**
 * A storage image's format, as GLSL's layout qualifier names it ({@code rgba8}, {@code r32ui}): what its texels hold,
 * and the internal format {@code glBindImageTexture} is given. The texture bound must be of the same format.
 */
public enum CgImageFormat {
    RGBA32F(0x8814, Kind.FLOAT), RGBA16F(0x881A, Kind.FLOAT), RG32F(0x8230, Kind.FLOAT), RG16F(0x822F, Kind.FLOAT),
    R11F_G11F_B10F(0x8C3A, Kind.FLOAT), R32F(0x822E, Kind.FLOAT), R16F(0x822D, Kind.FLOAT),
    RGBA16(0x805B, Kind.FLOAT), RGB10_A2(0x8059, Kind.FLOAT), RGBA8(0x8058, Kind.FLOAT), RG16(0x822C, Kind.FLOAT),
    RG8(0x822B, Kind.FLOAT), R16(0x822A, Kind.FLOAT), R8(0x8229, Kind.FLOAT),
    RGBA16_SNORM(0x8F9B, Kind.FLOAT), RGBA8_SNORM(0x8F97, Kind.FLOAT), RG16_SNORM(0x8F99, Kind.FLOAT),
    RG8_SNORM(0x8F95, Kind.FLOAT), R16_SNORM(0x8F98, Kind.FLOAT), R8_SNORM(0x8F94, Kind.FLOAT),
    RGBA32I(0x8D82, Kind.INT), RGBA16I(0x8D88, Kind.INT), RGBA8I(0x8D8E, Kind.INT), RG32I(0x823B, Kind.INT),
    RG16I(0x8239, Kind.INT), RG8I(0x8237, Kind.INT), R32I(0x8235, Kind.INT), R16I(0x8233, Kind.INT),
    R8I(0x8231, Kind.INT),
    RGBA32UI(0x8D70, Kind.UINT), RGBA16UI(0x8D76, Kind.UINT), RGB10_A2UI(0x906F, Kind.UINT),
    RGBA8UI(0x8D7C, Kind.UINT), RG32UI(0x823C, Kind.UINT), RG16UI(0x823A, Kind.UINT), RG8UI(0x8238, Kind.UINT),
    R32UI(0x8236, Kind.UINT), R16UI(0x8234, Kind.UINT), R8UI(0x8232, Kind.UINT);

    /** What a texel reads and writes as: {@code vec4}, {@code ivec4} or {@code uvec4}. */
    public enum Kind {
        FLOAT("", "vec4"), INT("i", "ivec4"), UINT("u", "uvec4");

        /** The prefix of the GLSL image type: {@code iimage2D}. */
        public final String prefix;
        public final String texel;

        Kind(String prefix, String texel) {
            this.prefix = prefix;
            this.texel = texel;
        }
    }

    /** The GL internal format. */
    public final int glFormat;
    public final Kind kind;

    CgImageFormat(int glFormat, Kind kind) {
        this.glFormat = glFormat;
        this.kind = kind;
    }

    /** The layout qualifier: {@code r11f_g11f_b10f}. */
    public String qualifier() { return name().toLowerCase(); }

    /** A colour target a fragment pass can write: every format but the SNORM ones, which GL 3 need not render. */
    public boolean renderable() { return !name().endsWith("_SNORM"); }

    /** Image atomics need a 32-bit integer format. */
    public boolean atomics() { return this == R32I || this == R32UI; }

    /** The format a layout qualifier names, or null. */
    public static CgImageFormat of(String qualifier) {
        for (CgImageFormat f : values()) if (f.qualifier().equals(qualifier)) return f;
        return null;
    }
}
