package com.crystalgraphics.util;

/**
 * IEEE-754 binary16 ("half float") conversion — the format behind {@code GL_HALF_FLOAT} and the
 * {@code *16F} texture internal formats.
 *
 * <h3>Why this is hand-rolled</h3>
 * <p>{@link Float#floatToFloat16(float)} does exactly this and would be the obvious choice, but it
 * is a Java 20 <em>runtime</em> API. This module is authored in modern Java and compiled to Java 8
 * bytecode via Jabel/jvmDowngrader, which desugars syntax but does not backport arbitrary new
 * stdlib methods — so calling it would compile cleanly and then fail with
 * {@code NoSuchMethodError} on an actual Java 8 runtime (MC 1.7.10). Swap this implementation for
 * the JDK's the day the minimum runtime clears Java 20; the behaviour is identical.</p>
 *
 * <h3>Why it matters</h3>
 * <p>Uploading {@code GL_FLOAT} data into a {@code *16F} texture makes the driver quantize to half
 * anyway, so converting on the CPU is free precision-wise while halving bus traffic and skipping
 * the driver's per-pixel conversion.</p>
 *
 * <p><strong>Currently unused.</strong> Its one caller was the distance-field glyph atlas upload
 * path, which moved to 8-bit unorm once measurement showed {@code RGBA16F} bought no visible
 * quality for distance fields (see {@code CgMsdfFieldStorageTest}). Kept because half-float
 * conversion is a general primitive with a passing test and is the obvious thing to reach for the
 * next time anything targets an {@code RGBA16F} texture — HDR render targets, packed instance
 * data — not because anything depends on it today.</p>
 *
 * <h3>Correctness</h3>
 * <p>Round-to-nearest-even, with the edge cases such conversions classically get wrong handled
 * explicitly: signed zero, subnormals in both directions, overflow saturating to infinity rather
 * than wrapping, and NaN staying NaN rather than being shifted into an infinity. Verified by
 * {@code CgHalfFloatTest}, including monotonicity across the unit range — a non-monotonic step
 * would surface as a visible artifact in distance-field reconstruction.</p>
 */
public final class CgHalfFloat {

    private CgHalfFloat() {}

    /**
     * Converts a binary32 {@code float} to the raw 16 bits of its binary16 equivalent.
     *
     * @param value any finite value, infinity, or NaN
     * @return the binary16 bit pattern, in the low 16 bits of the returned {@code short}
     */
    public static short toHalf(float value) {
        int bits = Float.floatToRawIntBits(value);
        int sign = (bits >>> 16) & 0x8000;
        int magnitude = bits & 0x7fffffff;

        // NaN / Infinity
        if (magnitude >= 0x7f800000) {
            if (magnitude > 0x7f800000) {
                // NaN — keep a non-zero mantissa, otherwise the shift below would silently
                // turn it into an infinity.
                int mantissa = (magnitude >>> 13) & 0x03ff;
                return (short) (sign | 0x7c00 | (mantissa == 0 ? 1 : mantissa));
            }
            return (short) (sign | 0x7c00);
        }

        int rounded = magnitude + 0x1000; // round-to-nearest-even bias on the bits about to be dropped
        if (rounded >= 0x47800000) {
            return (short) (sign | 0x7c00); // beyond half's range → infinity
        }
        if (rounded >= 0x38800000) {
            return (short) (sign | ((rounded - 0x38000000) >>> 13)); // normal
        }
        if (magnitude < 0x33000000) {
            return (short) sign; // too small even for a subnormal → signed zero
        }
        int exponent = magnitude >>> 23;
        int subnormal = ((magnitude & 0x7fffff) | 0x800000) + (0x800000 >>> (exponent - 102));
        return (short) (sign | (subnormal >>> (126 - exponent)));
    }

    /**
     * Converts the raw 16 bits of a binary16 value back to {@code float} — the exact inverse of
     * {@link #toHalf(float)} for every value binary16 can represent.
     *
     * @param half the binary16 bit pattern, read from the low 16 bits
     */
    public static float toFloat(short half) {
        int h = half & 0xffff;
        int sign = (h & 0x8000) << 16;
        int exponent = (h >>> 10) & 0x1f;
        int mantissa = h & 0x03ff;

        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign); // +/- 0
            // Subnormal — normalize into binary32's normal range.
            exponent = 1;
            while ((mantissa & 0x0400) == 0) {
                mantissa <<= 1;
                exponent--;
            }
            mantissa &= 0x03ff;
            return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
        }
        if (exponent == 0x1f) {
            return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13)); // Inf / NaN
        }
        return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
    }
}
