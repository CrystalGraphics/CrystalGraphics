package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.platform.rhi.format.CgFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Texels between a device format and RGBA, as {@code glReadPixels} and {@code glTexSubImage2D} converted them: read, a
 * missing channel is 0 and a missing alpha 1; floats are clamped and rounded into bytes either way.
 */
public final class CgTexelConvert {

    private CgTexelConvert() {
    }

    /** {@code texels} of {@code format} from {@code src} as RGBA8 into {@code out}. */
    static void toRgba8(ByteBuffer src, CgFormat format, int texels, ByteBuffer out) {
        float[] px = new float[4];
        for (int i = 0; i < texels; i++) {
            read(src, format, i, px);
            for (int c = 0; c < 4; c++) out.put((byte) Math.round(Math.max(0f, Math.min(1f, px[c])) * 255f));
        }
        out.flip();
    }

    /** {@code texels} of {@code format} from {@code src} as RGBA32F, native order, into {@code out}. */
    static void toRgba32f(ByteBuffer src, CgFormat format, int texels, ByteBuffer out) {
        float[] px = new float[4];
        out.order(ByteOrder.nativeOrder());
        for (int i = 0; i < texels; i++) {
            read(src, format, i, px);
            for (int c = 0; c < 4; c++) out.putFloat(px[c]);
        }
        out.flip();
    }

    /**
     * {@code texels} RGBA floats as {@code format} holds them, as {@code glTexSubImage2D(GL_RGBA, GL_FLOAT)} converts:
     * channels the format lacks dropped, unorm clamped and rounded.
     */
    public static ByteBuffer fromRgba32f(float[] rgba, CgFormat format, int texels) {
        ByteBuffer out = ByteBuffer.allocateDirect((int) format.bytes(texels, 1, 1)).order(ByteOrder.nativeOrder());
        int channels = channels(format);
        for (int i = 0; i < texels; i++) {
            for (int c = 0; c < channels; c++) {
                float v = rgba[i * 4 + c];
                switch (format) {
                    case R8_UNORM: case RG8_UNORM: case RGBA8_UNORM: case RGBA8_SRGB:
                        out.put((byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f));
                        break;
                    case R16_FLOAT: case RG16_FLOAT: case RGBA16_FLOAT: out.putShort(toHalf(v)); break;
                    default: out.putFloat(v);
                }
            }
        }
        out.flip();
        return out;
    }

    private static int channels(CgFormat format) {
        switch (format) {
            case R8_UNORM: case R16_FLOAT: case R32_FLOAT: return 1;
            case RG8_UNORM: case RG16_FLOAT: case RG32_FLOAT: return 2;
            case RGBA8_UNORM: case RGBA8_SRGB: case RGBA16_FLOAT: case RGBA32_FLOAT: return 4;
            default: throw new IllegalArgumentException("no RGBA float writing of " + format);
        }
    }

    /** IEEE half of {@code v}, rounded to nearest even; infinities and NaN kept. */
    private static short toHalf(float v) {
        int f = Float.floatToRawIntBits(v), sign = f >>> 16 & 0x8000, exp = f >>> 23 & 0xFF, mant = f & 0x7FFFFF;
        if (exp == 0xFF) return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
        int e = exp - 127 + 15;
        if (e >= 31) return (short) (sign | 0x7C00);
        if (e <= 0) {
            if (e < -10) return (short) sign;
            mant |= 0x800000;
            int shift = 14 - e, half = mant >> shift, rem = mant & ((1 << shift) - 1), mid = 1 << (shift - 1);
            if (rem > mid || rem == mid && (half & 1) != 0) half++;
            return (short) (sign | half);
        }
        int half = e << 10 | mant >> 13, rem = mant & 0x1FFF;
        if (rem > 0x1000 || rem == 0x1000 && (half & 1) != 0) half++;
        return (short) (sign | half);
    }

    private static void read(ByteBuffer src, CgFormat format, int i, float[] px) {
        px[0] = px[1] = px[2] = 0f;
        px[3] = 1f;
        switch (format) {
            case R8_UNORM: px[0] = u8(src, i); break;
            case RG8_UNORM: px[0] = u8(src, i * 2); px[1] = u8(src, i * 2 + 1); break;
            case RGBA8_UNORM: case RGBA8_SRGB: for (int c = 0; c < 4; c++) px[c] = u8(src, i * 4 + c); break;
            case BGRA8_UNORM:
                px[2] = u8(src, i * 4); px[1] = u8(src, i * 4 + 1); px[0] = u8(src, i * 4 + 2); px[3] = u8(src, i * 4 + 3);
                break;
            case R16_FLOAT: px[0] = half(src, i * 2); break;
            case RG16_FLOAT: px[0] = half(src, i * 4); px[1] = half(src, i * 4 + 2); break;
            case RGBA16_FLOAT: for (int c = 0; c < 4; c++) px[c] = half(src, i * 8 + c * 2); break;
            case R32_FLOAT: px[0] = f32(src, i * 4); break;
            case RG32_FLOAT: px[0] = f32(src, i * 8); px[1] = f32(src, i * 8 + 4); break;
            case RGBA32_FLOAT: for (int c = 0; c < 4; c++) px[c] = f32(src, i * 16 + c * 4); break;
            case RGB10A2_UNORM: {
                int v = src.order(ByteOrder.nativeOrder()).getInt(i * 4);
                px[0] = (v & 0x3FF) / 1023f; px[1] = (v >>> 10 & 0x3FF) / 1023f; px[2] = (v >>> 20 & 0x3FF) / 1023f;
                px[3] = (v >>> 30) / 3f;
                break;
            }
            default: throw new IllegalArgumentException("no RGBA reading of " + format);
        }
    }

    private static float u8(ByteBuffer b, int at) {
        return (b.get(at) & 0xFF) / 255f;
    }

    private static float f32(ByteBuffer b, int at) {
        return b.order(ByteOrder.nativeOrder()).getFloat(at);
    }

    /** IEEE half at byte {@code at}, native order. */
    private static float half(ByteBuffer b, int at) {
        int h = b.order(ByteOrder.nativeOrder()).getShort(at) & 0xFFFF;
        int sign = h >>> 15, exp = h >>> 10 & 0x1F, mant = h & 0x3FF;
        float v;
        if (exp == 0) v = mant * (1f / (1 << 24));
        else if (exp == 31) v = mant == 0 ? Float.POSITIVE_INFINITY : Float.NaN;
        else v = Float.intBitsToFloat((exp + 112) << 23 | mant << 13);
        return sign == 0 ? v : -v;
    }
}
