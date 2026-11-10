package com.crystalgraphics.widget;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A colour in linear light, Unreal's {@code FLinearColor}: what a widget's tints are, multiplied down its tree in linear
 * and encoded to sRGB only where it is drawn, as Slate packs a vertex colour.
 *
 * <pre>{@code
 * CgLinearColour glow = new CgLinearColour(0.5f, 0.1f, 0f, 0.5f);   // as a kit file writes it: [0.5, 0.1, 0, 0.5]
 * CgLinearColour gold = CgLinearColour.argb(0xFFFFC040);            // an sRGB colour picked by eye
 * }</pre>
 *
 * <ul>
 *   <li>Alpha is never encoded: 0.5 is half coverage either way.</li>
 *   <li>{@link #argb(int)} decodes; {@link #toArgb()} encodes. A linear 0.5 is about sRGB 0xBC, not 0x80.</li>
 *   <li>A file of your own writes one as sRGB {@code 0xAARRGGBB}, read by {@link #ARGB}.</li>
 * </ul>
 */
public record CgLinearColour(float r, float g, float b, float a) {

    public static final CgLinearColour WHITE = new CgLinearColour(1f, 1f, 1f, 1f);

    /**
     * A colour written as sRGB {@code 0xAARRGGBB}, decoded to linear; written back encoded. For a mod's own data files:
     *
     * <pre>{@code
     * // grades.yaml: - { grade: Z, glow: 0xFFD80A16 }
     * CgCodecs.field("glow", CgLinearColour.ARGB, Grade::glow)
     * }</pre>
     *
     * Written back it is the 8-bit colour nearest, so a colour not picked as one moves by up to half a step.
     */
    public static final CgCodec<CgLinearColour> ARGB = CgCodecs.xmap(CgCodecs.LONG, v -> argb((int) (long) v),
            c -> c.toArgb() & 0xFFFFFFFFL);

    /** {@code argb}, sRGB as picked, decoded to linear. */
    public static CgLinearColour argb(int argb) {
        return new CgLinearColour(linear((argb >>> 16 & 0xFF) / 255f), linear((argb >>> 8 & 0xFF) / 255f),
                linear((argb & 0xFF) / 255f), (argb >>> 24) / 255f);
    }

    /** As sRGB ARGB, each channel clamped to 0 to 1. */
    public int toArgb() {
        return argb(r, g, b, a);
    }

    /** Linear channels to sRGB ARGB, each clamped to 0 to 1. */
    public static int argb(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(srgb(r)) << 16 | byteOf(srgb(g)) << 8 | byteOf(srgb(b));
    }

    /** An sRGB channel, 0 to 1, in linear light. */
    public static float linear(float srgb) {
        if (srgb <= 0f) return 0f;
        if (srgb >= 1f) return 1f;
        return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4);
    }

    /** A linear channel, 0 to 1, as sRGB. */
    public static float srgb(float linear) {
        if (linear <= 0f) return 0f;
        if (linear >= 1f) return 1f;
        return linear <= 0.0031308f ? linear * 12.92f : 1.055f * (float) Math.pow(linear, 1 / 2.4) - 0.055f;
    }

    private static int byteOf(float value) {
        return Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }
}
