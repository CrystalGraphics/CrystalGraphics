package com.crystalgraphics.platform.device.format;

/**
 * The format of one vertex attribute in a buffer. {@code UNORM}/{@code SNORM} read as normalised floats,
 * {@code USCALED}/{@code SSCALED} as unnormalised floats (GL's non-normalised integers into a float
 * attribute), {@code UINT}/{@code SINT} as integers.
 */
public enum CgAttribFormat {
    FLOAT32(4), FLOAT32X2(8), FLOAT32X3(12), FLOAT32X4(16),
    FLOAT16X2(4), FLOAT16X4(8),
    UNORM8X2(2), UNORM8X4(4), SNORM8X2(2), SNORM8X4(4),
    USCALED8X2(2), USCALED8X4(4), SSCALED8X2(2), SSCALED8X4(4),
    UINT8X2(2), UINT8X4(4), SINT8X2(2), SINT8X4(4),
    UNORM16X2(4), UNORM16X4(8), SNORM16X2(4), SNORM16X4(8),
    USCALED16X2(4), USCALED16X4(8), SSCALED16X2(4), SSCALED16X4(8),
    UINT16X2(4), UINT16X4(8), SINT16X2(4), SINT16X4(8),
    UINT32(4), UINT32X2(8), UINT32X3(12), UINT32X4(16),
    SINT32(4), SINT32X2(8), SINT32X3(12), SINT32X4(16);

    private final int bytes;

    CgAttribFormat(int bytes) { this.bytes = bytes; }

    public int bytes() { return bytes; }
}
