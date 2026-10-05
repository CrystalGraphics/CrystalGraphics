package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GL's texel formats on a device: which device format an internal format becomes, and GL's pixel transfer —
 * the unpack and pack state, the (format, type) pair, RGB widened to RGBA — into and out of the device's tightly
 * packed texels. A combination not here throws, naming it.
 */
public final class GlPixels {

    static final int GL_BGRA = 0x80E1, GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    static final int GL_SRGB = 0x8C40, GL_SRGB8 = 0x8C41, GL_SRGB_ALPHA = 0x8C42, GL_RGB10_A2UI = 0x906F;
    static final int GL_PACK_ROW_LENGTH = 0x0D02, GL_PACK_SKIP_ROWS = 0x0D03, GL_PACK_SKIP_PIXELS = 0x0D04;

    /** GL's pixel store, for one direction. */
    static final class Store {
        int alignment = 4, rowLength, skipRows, skipPixels, imageHeight, skipImages;
    }

    private GlPixels() {}

    /** The device format for a GL internal format. */
    static CgFormat device(int internalFormat) {
        switch (internalFormat) {
            case CgGL.GL_R8: case CgGL.GL_RED:              return CgFormat.R8_UNORM;
            case CgGL.GL_R8_SNORM:                          return CgFormat.R8_SNORM;
            case CgGL.GL_R8UI:                              return CgFormat.R8_UINT;
            case CgGL.GL_R8I:                               return CgFormat.R8_SINT;
            case CgGL.GL_RG8: case CgGL.GL_RG:              return CgFormat.RG8_UNORM;
            case CgGL.GL_RG8UI:                             return CgFormat.RG8_UINT;
            case CgGL.GL_RG8I:                              return CgFormat.RG8_SINT;
            case CgGL.GL_RGBA8: case CgGL.GL_RGBA: case CgGL.GL_RGB8: case CgGL.GL_RGB: return CgFormat.RGBA8_UNORM;
            case CgGL.GL_RGBA8_SNORM:                       return CgFormat.RGBA8_SNORM;
            case CgGL.GL_RGBA8UI:                           return CgFormat.RGBA8_UINT;
            case CgGL.GL_RGBA8I:                            return CgFormat.RGBA8_SINT;
            case CgGL.GL_SRGB8_ALPHA8: case GL_SRGB8: case GL_SRGB: case GL_SRGB_ALPHA: return CgFormat.RGBA8_SRGB;
            case CgGL.GL_R16F:                              return CgFormat.R16_FLOAT;
            case CgGL.GL_R16UI:                             return CgFormat.R16_UINT;
            case CgGL.GL_R16I:                              return CgFormat.R16_SINT;
            case CgGL.GL_RG16F:                             return CgFormat.RG16_FLOAT;
            case CgGL.GL_RG16UI:                            return CgFormat.RG16_UINT;
            case CgGL.GL_RG16I:                             return CgFormat.RG16_SINT;
            case CgGL.GL_RGBA16F: case CgGL.GL_RGB16F:      return CgFormat.RGBA16_FLOAT;
            case CgGL.GL_RGBA16UI:                          return CgFormat.RGBA16_UINT;
            case CgGL.GL_RGBA16I:                           return CgFormat.RGBA16_SINT;
            case CgGL.GL_R32F:                              return CgFormat.R32_FLOAT;
            case CgGL.GL_R32UI:                             return CgFormat.R32_UINT;
            case CgGL.GL_R32I:                              return CgFormat.R32_SINT;
            case CgGL.GL_RG32F:                             return CgFormat.RG32_FLOAT;
            case CgGL.GL_RG32UI:                            return CgFormat.RG32_UINT;
            case CgGL.GL_RG32I:                             return CgFormat.RG32_SINT;
            case CgGL.GL_RGBA32F: case CgGL.GL_RGB32F:      return CgFormat.RGBA32_FLOAT;
            case CgGL.GL_RGBA32UI:                          return CgFormat.RGBA32_UINT;
            case CgGL.GL_RGBA32I:                           return CgFormat.RGBA32_SINT;
            case CgGL.GL_RGB10_A2:                          return CgFormat.RGB10A2_UNORM;
            case GL_RGB10_A2UI:                             return CgFormat.RGB10A2_UINT;
            case CgGL.GL_R11F_G11F_B10F:                    return CgFormat.RG11B10_UFLOAT;
            case CgGL.GL_RGBA4:                             return CgFormat.RGBA4_UNORM;
            case CgGL.GL_RGB5_A1:                           return CgFormat.RGB5A1_UNORM;
            case CgGL.GL_DEPTH_COMPONENT16:                 return CgFormat.DEPTH16_UNORM;
            case CgGL.GL_DEPTH_COMPONENT24: case CgGL.GL_DEPTH_COMPONENT: return CgFormat.DEPTH24_PLUS;
            case CgGL.GL_DEPTH_COMPONENT32: case CgGL.GL_DEPTH_COMPONENT32F: return CgFormat.DEPTH32_FLOAT;
            case CgGL.GL_DEPTH24_STENCIL8: case CgGL.GL_DEPTH_STENCIL: return CgFormat.DEPTH24_PLUS_STENCIL8;
            case CgGL.GL_DEPTH32F_STENCIL8:                 return CgFormat.DEPTH32_FLOAT_STENCIL8;
            case CgGL.GL_STENCIL_INDEX8:                    return CgFormat.STENCIL8;
            default: throw new UnsupportedOperationException("Internal format 0x" + Integer.toHexString(internalFormat)
                    + " has no device format");
        }
    }

    /** {@code pixels} as the device's tightly packed texels of {@code dst}: {@code width x height x depth}. */
    static ByteBuffer unpack(ByteBuffer pixels, int format, int type, int width, int height, int depth, Store store, CgFormat dst) {
        int comps = components(format);
        int bpp = pixelBytes(format, type);
        int rowLength = store.rowLength > 0 ? store.rowLength : width;
        int rowBytes = rowBytes(rowLength, bpp, componentBytes(type), store.alignment);
        int imageBytes = rowBytes * (store.imageHeight > 0 ? store.imageHeight : height);
        int base = pixels.position() + store.skipImages * imageBytes + store.skipRows * rowBytes + store.skipPixels * bpp;
        ByteBuffer out = ByteBuffer.allocateDirect(width * height * depth * dst.bytes()).order(ByteOrder.nativeOrder());
        ByteBuffer in = pixels.duplicate().order(ByteOrder.nativeOrder());
        boolean copy = sameLayout(format, type, dst);
        float[] px = new float[4];
        int[] ipx = new int[4];
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                int row = base + z * imageBytes + y * rowBytes;
                if (copy) {
                    ByteBuffer r = in.duplicate();
                    r.limit(row + width * bpp);
                    r.position(row);
                    out.put(r);
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int at = row + x * bpp;
                    if (dst.numeric() == CgFormat.Numeric.INT) {
                        readInts(in, at, format, type, comps, ipx);
                        writeInts(out, dst, ipx);
                    } else {
                        readFloats(in, at, format, type, comps, px);
                        writeFloats(out, dst, px);
                    }
                }
            }
        }
        out.flip();
        return out;
    }

    /** Device texels of {@code src} into {@code out} as GL's {@code (format, type)} under {@code store}. */
    static void pack(ByteBuffer texels, CgFormat src, int width, int height, int format, int type, Store store, ByteBuffer out) {
        int bpp = pixelBytes(format, type);
        int rowLength = store.rowLength > 0 ? store.rowLength : width;
        int rowBytes = rowBytes(rowLength, bpp, componentBytes(type), store.alignment);
        int base = out.position() + store.skipRows * rowBytes + store.skipPixels * bpp;
        ByteBuffer in = texels.duplicate().order(ByteOrder.nativeOrder());
        ByteBuffer o = out.duplicate().order(ByteOrder.nativeOrder());
        boolean copy = sameLayout(format, type, src);
        float[] px = new float[4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int from = (y * width + x) * src.bytes(), to = base + y * rowBytes + x * bpp;
                if (copy) {
                    for (int b = 0; b < bpp; b++) o.put(to + b, in.get(from + b));
                } else {
                    decode(in, from, src, px);
                    encode(o, to, format, type, px);
                }
            }
        }
    }

    // ── layouts ────────────────────────────────────────────────────────────────

    /** Whether {@code store} reads {@code width x height} pixels of {@code (format, type)} as one run from the start. */
    static boolean tight(Store store, int format, int type, int width, int height) {
        if (store.skipPixels != 0 || store.skipRows != 0 || store.skipImages != 0) return false;
        if (store.rowLength != 0 && store.rowLength != width) return false;
        if (store.imageHeight != 0 && store.imageHeight != height) return false;
        int bpp = pixelBytes(format, type);
        return rowBytes(width, bpp, componentBytes(type), store.alignment) == width * bpp;
    }

    /** Whether GL's {@code (format, type)} is {@code dst}'s tightly packed texels as they are. */
    static boolean sameLayout(int format, int type, CgFormat dst) {
        switch (dst) {
            case R8_UNORM: return format == CgGL.GL_RED && type == CgGL.GL_UNSIGNED_BYTE;
            case RG8_UNORM: return format == CgGL.GL_RG && type == CgGL.GL_UNSIGNED_BYTE;
            case RGBA8_UNORM: case RGBA8_SRGB: return format == CgGL.GL_RGBA && type == CgGL.GL_UNSIGNED_BYTE;
            case R8_UINT: return format == CgGL.GL_RED_INTEGER && type == CgGL.GL_UNSIGNED_BYTE;
            case RGBA8_UINT: return format == CgGL.GL_RGBA_INTEGER && type == CgGL.GL_UNSIGNED_BYTE;
            case R16_FLOAT: return format == CgGL.GL_RED && type == CgGL.GL_HALF_FLOAT;
            case RG16_FLOAT: return format == CgGL.GL_RG && type == CgGL.GL_HALF_FLOAT;
            case RGBA16_FLOAT: return format == CgGL.GL_RGBA && type == CgGL.GL_HALF_FLOAT;
            case R32_FLOAT: return format == CgGL.GL_RED && type == CgGL.GL_FLOAT;
            case RG32_FLOAT: return format == CgGL.GL_RG && type == CgGL.GL_FLOAT;
            case RGBA32_FLOAT: return format == CgGL.GL_RGBA && type == CgGL.GL_FLOAT;
            case R32_UINT: return format == CgGL.GL_RED_INTEGER && type == CgGL.GL_UNSIGNED_INT;
            case RGB10A2_UNORM: return format == CgGL.GL_RGBA && type == CgGL.GL_UNSIGNED_INT_2_10_10_10_REV;
            case RG11B10_UFLOAT: return format == CgGL.GL_RGB && type == CgGL.GL_UNSIGNED_INT_10F_11F_11F_REV;
            case DEPTH32_FLOAT: return format == CgGL.GL_DEPTH_COMPONENT && type == CgGL.GL_FLOAT;
            default: return false;
        }
    }

    private static int components(int format) {
        switch (format) {
            case CgGL.GL_RED: case CgGL.GL_RED_INTEGER: case CgGL.GL_DEPTH_COMPONENT: case CgGL.GL_STENCIL_INDEX: return 1;
            case CgGL.GL_RG: case CgGL.GL_RG_INTEGER: return 2;
            case CgGL.GL_RGB: case CgGL.GL_RGB_INTEGER: return 3;
            case CgGL.GL_RGBA: case CgGL.GL_RGBA_INTEGER: case GL_BGRA: return 4;
            default: throw new UnsupportedOperationException("Pixel format 0x" + Integer.toHexString(format));
        }
    }

    private static int componentBytes(int type) {
        switch (type) {
            case CgGL.GL_UNSIGNED_BYTE: case CgGL.GL_BYTE: return 1;
            case CgGL.GL_UNSIGNED_SHORT: case CgGL.GL_SHORT: case CgGL.GL_HALF_FLOAT: return 2;
            default: return 4;
        }
    }

    private static int pixelBytes(int format, int type) {
        if (type == CgGL.GL_UNSIGNED_INT_2_10_10_10_REV || type == CgGL.GL_UNSIGNED_INT_10F_11F_11F_REV
                || type == GL_UNSIGNED_INT_8_8_8_8_REV || type == CgGL.GL_UNSIGNED_INT_24_8) return 4;
        if (type == CgGL.GL_FLOAT_32_UNSIGNED_INT_24_8_REV) return 8;
        return components(format) * componentBytes(type);
    }

    /** GL's row length in bytes: padded to the alignment unless a component is at least that big. */
    private static int rowBytes(int rowLength, int bpp, int componentBytes, int alignment) {
        int bytes = rowLength * bpp;
        return componentBytes >= alignment ? bytes : (bytes + alignment - 1) / alignment * alignment;
    }

    // ── reading GL's pixels ────────────────────────────────────────────────────

    private static void readFloats(ByteBuffer in, int at, int format, int type, int comps, float[] px) {
        px[0] = px[1] = px[2] = 0;
        px[3] = 1;
        if (type == GL_UNSIGNED_INT_8_8_8_8_REV && format == GL_BGRA) {
            int v = in.getInt(at);
            px[2] = (v & 0xFF) / 255f; px[1] = ((v >>> 8) & 0xFF) / 255f; px[0] = ((v >>> 16) & 0xFF) / 255f; px[3] = (v >>> 24) / 255f;
            return;
        }
        int size = componentBytes(type);
        for (int c = 0; c < comps; c++) {
            int o = at + c * size;
            float v;
            switch (type) {
                case CgGL.GL_UNSIGNED_BYTE:  v = (in.get(o) & 0xFF) / 255f; break;
                case CgGL.GL_BYTE:           v = Math.max(in.get(o) / 127f, -1f); break;
                case CgGL.GL_UNSIGNED_SHORT: v = (in.getShort(o) & 0xFFFF) / 65535f; break;
                case CgGL.GL_SHORT:          v = Math.max(in.getShort(o) / 32767f, -1f); break;
                case CgGL.GL_HALF_FLOAT:     v = halfToFloat(in.getShort(o)); break;
                case CgGL.GL_FLOAT:          v = in.getFloat(o); break;
                case CgGL.GL_UNSIGNED_INT:   v = (float) ((in.getInt(o) & 0xFFFFFFFFL) / 4294967295.0); break;
                default: throw new UnsupportedOperationException("Pixel type 0x" + Integer.toHexString(type));
            }
            px[format == GL_BGRA && c < 3 ? 2 - c : c] = v;
        }
    }

    private static void readInts(ByteBuffer in, int at, int format, int type, int comps, int[] px) {
        px[0] = px[1] = px[2] = 0;
        px[3] = 1;
        int size = componentBytes(type);
        for (int c = 0; c < comps; c++) {
            int o = at + c * size;
            switch (type) {
                case CgGL.GL_UNSIGNED_BYTE:  px[c] = in.get(o) & 0xFF; break;
                case CgGL.GL_BYTE:           px[c] = in.get(o); break;
                case CgGL.GL_UNSIGNED_SHORT: px[c] = in.getShort(o) & 0xFFFF; break;
                case CgGL.GL_SHORT:          px[c] = in.getShort(o); break;
                case CgGL.GL_UNSIGNED_INT: case CgGL.GL_INT: px[c] = in.getInt(o); break;
                default: throw new UnsupportedOperationException("Integer pixel type 0x" + Integer.toHexString(type));
            }
        }
    }

    // ── writing device texels ──────────────────────────────────────────────────

    private static void writeFloats(ByteBuffer out, CgFormat dst, float[] px) {
        switch (dst) {
            case R8_UNORM: out.put(unorm8(px[0])); break;
            case RG8_UNORM: out.put(unorm8(px[0])).put(unorm8(px[1])); break;
            case RGBA8_UNORM: case RGBA8_SRGB: for (int c = 0; c < 4; c++) out.put(unorm8(px[c])); break;
            case R8_SNORM: out.put(snorm8(px[0])); break;
            case RGBA8_SNORM: for (int c = 0; c < 4; c++) out.put(snorm8(px[c])); break;
            case R16_FLOAT: out.putShort(floatToHalf(px[0])); break;
            case RG16_FLOAT: out.putShort(floatToHalf(px[0])).putShort(floatToHalf(px[1])); break;
            case RGBA16_FLOAT: for (int c = 0; c < 4; c++) out.putShort(floatToHalf(px[c])); break;
            case R32_FLOAT: out.putFloat(px[0]); break;
            case RG32_FLOAT: out.putFloat(px[0]).putFloat(px[1]); break;
            case RGBA32_FLOAT: for (int c = 0; c < 4; c++) out.putFloat(px[c]); break;
            case RGBA4_UNORM:
                out.putShort((short) (q(px[0], 15) << 12 | q(px[1], 15) << 8 | q(px[2], 15) << 4 | q(px[3], 15)));
                break;
            case RGB5A1_UNORM:
                out.putShort((short) (q(px[0], 31) << 11 | q(px[1], 31) << 6 | q(px[2], 31) << 1 | q(px[3], 1)));
                break;
            default: throw new UnsupportedOperationException("Converting pixels into " + dst);
        }
    }

    private static void writeInts(ByteBuffer out, CgFormat dst, int[] px) {
        int comps = dst.bytes() / componentBytesOf(dst);
        for (int c = 0; c < comps; c++) {
            switch (componentBytesOf(dst)) {
                case 1: out.put((byte) px[c]); break;
                case 2: out.putShort((short) px[c]); break;
                default: out.putInt(px[c]);
            }
        }
    }

    private static int componentBytesOf(CgFormat f) {
        switch (f) {
            case R8_UINT: case R8_SINT: case RG8_UINT: case RG8_SINT: case RGBA8_UINT: case RGBA8_SINT: return 1;
            case R16_UINT: case R16_SINT: case RG16_UINT: case RG16_SINT: case RGBA16_UINT: case RGBA16_SINT: return 2;
            case R32_UINT: case R32_SINT: case RG32_UINT: case RG32_SINT: case RGBA32_UINT: case RGBA32_SINT: return 4;
            default: throw new UnsupportedOperationException("Converting integer pixels into " + f);
        }
    }

    // ── reading device texels back ─────────────────────────────────────────────

    private static void decode(ByteBuffer in, int at, CgFormat src, float[] px) {
        px[0] = px[1] = px[2] = 0;
        px[3] = 1;
        switch (src) {
            case R8_UNORM: px[0] = (in.get(at) & 0xFF) / 255f; break;
            case RG8_UNORM: px[0] = (in.get(at) & 0xFF) / 255f; px[1] = (in.get(at + 1) & 0xFF) / 255f; break;
            case RGBA8_UNORM: case RGBA8_SRGB: case BGRA8_UNORM:
                for (int c = 0; c < 4; c++) px[c] = (in.get(at + c) & 0xFF) / 255f;
                if (src == CgFormat.BGRA8_UNORM) { float t = px[0]; px[0] = px[2]; px[2] = t; }
                break;
            case R16_FLOAT: px[0] = halfToFloat(in.getShort(at)); break;
            case RGBA16_FLOAT: for (int c = 0; c < 4; c++) px[c] = halfToFloat(in.getShort(at + 2 * c)); break;
            case R32_FLOAT: case DEPTH32_FLOAT: px[0] = in.getFloat(at); break;
            case RGBA32_FLOAT: for (int c = 0; c < 4; c++) px[c] = in.getFloat(at + 4 * c); break;
            default: throw new UnsupportedOperationException("Reading back " + src + " as another format");
        }
    }

    private static void encode(ByteBuffer out, int at, int format, int type, float[] px) {
        int comps = components(format);
        for (int c = 0; c < comps; c++) {
            float v = px[format == GL_BGRA && c < 3 ? 2 - c : c];
            switch (type) {
                case CgGL.GL_UNSIGNED_BYTE: out.put(at + c, unorm8(v)); break;
                case CgGL.GL_FLOAT: out.putFloat(at + 4 * c, v); break;
                case CgGL.GL_HALF_FLOAT: out.putShort(at + 2 * c, floatToHalf(v)); break;
                default: throw new UnsupportedOperationException("Reading back as type 0x" + Integer.toHexString(type));
            }
        }
    }

    // ── numbers ────────────────────────────────────────────────────────────────

    private static byte unorm8(float v) { return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f); }

    private static byte snorm8(float v) { return (byte) Math.round(Math.max(-1f, Math.min(1f, v)) * 127f); }

    private static int q(float v, int max) { return Math.round(Math.max(0f, Math.min(1f, v)) * max); }

    static float halfToFloat(short h) {
        int bits = h & 0xFFFF, sign = (bits & 0x8000) << 16, exp = (bits >>> 10) & 0x1F, mant = bits & 0x3FF;
        if (exp == 0) return (sign != 0 ? -1 : 1) * mant * 5.9604645e-8f;           // subnormal: mant * 2^-24
        if (exp == 31) return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        return Float.intBitsToFloat(sign | ((exp + 112) << 23) | (mant << 13));
    }

    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f), sign = (bits >>> 16) & 0x8000, exp = ((bits >>> 23) & 0xFF) - 112;
        int mant = bits & 0x7FFFFF;
        if (((bits >>> 23) & 0xFF) == 0xFF) return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
        if (exp >= 31) return (short) (sign | 0x7C00);
        if (exp <= 0) {
            if (exp < -10) return (short) sign;
            mant |= 0x800000;
            return (short) (sign | ((mant >> (14 - exp)) + ((mant >> (13 - exp)) & 1)));
        }
        return (short) (sign | ((exp << 10) + ((mant + 0x1000) >> 13)));   // a rounding carry moves into the exponent
    }
}
