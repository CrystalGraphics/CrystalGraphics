package com.crystalgraphics.platform.device.format;

import com.crystalgraphics.platform.device.CgDevice;

/**
 * A texel format a {@link CgDevice} allocates and samples. The names are WebGPU's; a device maps each onto
 * its API's own.
 *
 * <p>{@code DEPTH24_PLUS} and {@code DEPTH24_PLUS_STENCIL8} leave the depth precision to the device: 24-bit
 * where it has it, 32-bit float where it does not (Apple GPUs). There are no 3-channel formats; the tracked
 * backend expands RGB uploads to RGBA.</p>
 */
public enum CgFormat {
    R8_UNORM(1), R8_SNORM(1), R8_UINT(1, Numeric.INT), R8_SINT(1, Numeric.INT),
    RG8_UNORM(2), RG8_UINT(2, Numeric.INT), RG8_SINT(2, Numeric.INT),
    RGBA8_UNORM(4), RGBA8_SNORM(4), RGBA8_UINT(4, Numeric.INT), RGBA8_SINT(4, Numeric.INT), RGBA8_SRGB(4),
    BGRA8_UNORM(4),
    R16_FLOAT(2), R16_UINT(2, Numeric.INT), R16_SINT(2, Numeric.INT),
    RG16_FLOAT(4), RG16_UINT(4, Numeric.INT), RG16_SINT(4, Numeric.INT),
    RGBA16_FLOAT(8), RGBA16_UINT(8, Numeric.INT), RGBA16_SINT(8, Numeric.INT),
    R32_FLOAT(4), R32_UINT(4, Numeric.INT), R32_SINT(4, Numeric.INT),
    RG32_FLOAT(8), RG32_UINT(8, Numeric.INT), RG32_SINT(8, Numeric.INT),
    RGBA32_FLOAT(16), RGBA32_UINT(16, Numeric.INT), RGBA32_SINT(16, Numeric.INT),
    RGB10A2_UNORM(4), RGB10A2_UINT(4, Numeric.INT), RG11B10_UFLOAT(4),
    RGBA4_UNORM(2), RGB5A1_UNORM(2),
    DEPTH16_UNORM(2, Aspect.DEPTH), DEPTH24_PLUS(4, Aspect.DEPTH), DEPTH32_FLOAT(4, Aspect.DEPTH),
    DEPTH24_PLUS_STENCIL8(4, Aspect.DEPTH_STENCIL), DEPTH32_FLOAT_STENCIL8(8, Aspect.DEPTH_STENCIL),
    STENCIL8(1, Aspect.STENCIL);

    public enum Aspect { COLOR, DEPTH, STENCIL, DEPTH_STENCIL }

    /** How a shader reads it and how a clear value is given: as floats, or as integers. */
    public enum Numeric { FLOAT, INT }

    private final int bytes;
    private final Aspect aspect;
    private final Numeric numeric;

    CgFormat(int bytes) { this(bytes, Aspect.COLOR, Numeric.FLOAT); }

    CgFormat(int bytes, Numeric numeric) { this(bytes, Aspect.COLOR, numeric); }

    CgFormat(int bytes, Aspect aspect) { this(bytes, aspect, Numeric.FLOAT); }

    CgFormat(int bytes, Aspect aspect, Numeric numeric) {
        this.bytes = bytes;
        this.aspect = aspect;
        this.numeric = numeric;
    }

    /** Bytes per texel as an upload lays them out. */
    public int bytes() { return bytes; }

    public Aspect aspect() { return aspect; }

    public Numeric numeric() { return numeric; }

    public boolean hasDepth() { return aspect == Aspect.DEPTH || aspect == Aspect.DEPTH_STENCIL; }

    public boolean hasStencil() { return aspect == Aspect.STENCIL || aspect == Aspect.DEPTH_STENCIL; }
}
