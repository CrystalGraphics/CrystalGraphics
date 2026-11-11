package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * What a texture of ours holds and how it samples at first: its texel type, whether it makes its own chain of levels,
 * and its sampler. The texture classes' own: a file's comes from its settings ({@link CgTextureSettings}), a made one's
 * from its format and levels.
 */
record Texels(CgTextureType type, boolean mipmapped, CgSampler sampler) {

    /**
     * A texture of ours of {@code format} and {@code levels} levels, its caller filling every one: sampled nearest for an
     * integer format, trilinear across several levels, else bilinear; clamped.
     */
    static Texels made(CgFormat format, int levels) {
        CgTextureType type = type(format);
        CgSampler sampler = format.numeric() == CgFormat.Numeric.INT ? CgSampler.NEAREST_CLAMP
                : levels > 1 ? CgSampler.TRILINEAR_CLAMP : CgSampler.BILINEAR_CLAMP;
        return new Texels(type, false, sampler);
    }

    /** A texture from {@code path} as {@code settings} say: its format, its chain, its sampling. */
    static Texels file(String path, CgTextureSettings settings) {
        return new Texels(type(settings.format(path)), settings.mips(), settings.defaultSampler());
    }

    private static CgTextureType type(CgFormat format) {
        CgTextureType type = CgTextureType.of(format);
        if (type == null) throw new IllegalArgumentException("no texture of " + format);
        return type;
    }

    /**
     * A target's texture: no chain of its own, an integer type nearest and any other linear across whatever levels a
     * pass or kernel makes.
     */
    static Texels target(CgTextureType type) {
        boolean integer = type.format().numeric() == CgFormat.Numeric.INT;
        return new Texels(type, false, integer ? CgSampler.NEAREST_CLAMP : CgSampler.TRILINEAR_CLAMP);
    }

    /** A texture from {@code path} as its settings file says, or loaded plain where it has none. */
    static Texels file(String path) {
        return file(path, CgTextureSettings.of(path));
    }

    int getGlInternalFormat() {
        return type.glInternalFormat;
    }

    int getGlBaseFormat() {
        return type.glBaseFormat;
    }

    int getGlType() {
        return type.glType;
    }

    CgTextureType getType() {
        return type;
    }

    CgFormat format() {
        return type.format();
    }
}
