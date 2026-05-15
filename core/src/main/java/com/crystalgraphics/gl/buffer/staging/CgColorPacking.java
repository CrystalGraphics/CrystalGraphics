package com.crystalgraphics.gl.buffer.staging;

import java.nio.ByteOrder;

/**
 * Utility for packing RGBA color components into the ABGR int format expected
 * by OpenGL normalized unsigned byte color attributes.
 *
 * <p>The ABGR packing order is endian-aware: on little-endian systems (virtually
 * all x86 hardware), the byte order in memory is R,G,B,A which maps to ABGR when
 * read as a 32-bit int. On big-endian systems the order is reversed.</p>
 *
 * <p>This class extracts packing logic that was previously duplicated between
 * {@link CgVertexWriter} and {@link CgInstanceWriter}.</p>
 */
public final class CgColorPacking {

    private CgColorPacking() {}

    /**
     * Packs RGBA byte components into an ABGR-ordered int for GPU upload.
     * The returned int should be stored via {@link Float#intBitsToFloat(int)}
     * into a float staging buffer.
     *
     * @param r red component [0, 255]
     * @param g green component [0, 255]
     * @param b blue component [0, 255]
     * @param a alpha component [0, 255]
     * @return ABGR-packed int
     */
    public static int packNativeOrder(int r, int g, int b, int a) {
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((g & 0xFF) << 8) | (r & 0xFF);
        } else {
            return ((r & 0xFF) << 24) | ((g & 0xFF) << 16) | ((b & 0xFF) << 8) | (a & 0xFF);
        }
    }

    /**
     * Packs an ARGB int (e.g. Java {@code Color.getRGB()}, Minecraft color ints)
     * into an ABGR int for GPU upload.
     *
     * @param argb ARGB-packed int (alpha in highest byte)
     * @return ABGR-packed int
     */
    public static int packARGB(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b =  argb        & 0xFF;
        return packNativeOrder(r, g, b, a);
    }

    /**
     * Packs an RGBA int (e.g. from shader/texture coordinates)
     * into an ABGR int for GPU upload.
     *
     * @param rgba RGBA-packed int (red in highest byte)
     * @return ABGR-packed int
     */
    public static int packRGBA(int rgba) {
        int r = (rgba >> 24) & 0xFF;
        int g = (rgba >> 16) & 0xFF;
        int b = (rgba >> 8)  & 0xFF;
        int a =  rgba        & 0xFF;
        return packNativeOrder(r, g, b, a);
    }
}
