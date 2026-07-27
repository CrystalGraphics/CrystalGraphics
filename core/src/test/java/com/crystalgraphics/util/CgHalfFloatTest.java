package com.crystalgraphics.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgTexture2DArray#floatToHalf} — the CPU-side IEEE-754 binary32 →
 * binary16 conversion used by {@code uploadLayerRegionAsHalf} to halve distance-field glyph
 * upload bandwidth.
 *
 * <p>Hand-rolled bit manipulation (see that method's javadoc for why {@code Float.floatToFloat16}
 * is unavailable here), so it is verified against the reference semantics directly rather than
 * assumed correct: round-trip accuracy across the value range MSDF data actually occupies,
 * plus the edge cases such conversions classically get wrong — signed zero, subnormals,
 * overflow to infinity, and NaN staying NaN.</p>
 */
public class CgHalfFloatTest {

    /**
     * Independent binary16 → float decoder, kept private to this test rather than delegating to
     * {@link CgHalfFloat#toFloat(short)}: the point is to validate {@link CgHalfFloat#toHalf}
     * against a reference, and checking it against its own inverse in the same class would be
     * circular. {@link #testToFloat_agreesWithTheReferenceDecoder()} then pins the production
     * decoder to this one.
     */
    private static float halfToFloat(short half) {
        int h = half & 0xffff;
        int sign = (h & 0x8000) << 16;
        int exponent = (h >>> 10) & 0x1f;
        int mantissa = h & 0x03ff;

        if (exponent == 0) {
            if (mantissa == 0) return Float.intBitsToFloat(sign); // +/- 0
            // Subnormal: normalize it.
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

    private static void assertRoundTrip(float value, float tolerance) {
        float actual = halfToFloat(CgHalfFloat.toHalf(value));
        assertEquals("round-trip of " + value, value, actual, tolerance);
    }

    @Test
    public void testExactlyRepresentableValues_roundTripExactly() {
        // Powers of two and simple fractions are exact in binary16.
        for (float v : new float[]{0f, 0.5f, 0.25f, 1f, 2f, 4f, 0.125f, 256f, -1f, -0.5f, -256f}) {
            assertRoundTrip(v, 0f);
        }
    }

    @Test
    public void testUnitRange_roundTripsWithinHalfPrecision() {
        // The range MSDF/MTSDF texel data actually occupies. binary16 has ~3 decimal digits;
        // 1e-3 is comfortably within that across [0,1].
        for (int i = 0; i <= 1000; i++) {
            assertRoundTrip(i / 1000f, 1e-3f);
        }
    }

    @Test
    public void testNegativeUnitRange_roundTrips() {
        // Signed distances are genuinely negative outside the glyph outline.
        for (int i = 0; i <= 1000; i++) {
            assertRoundTrip(-i / 1000f, 1e-3f);
        }
    }

    @Test
    public void testSignedZero_preservesSign() {
        assertEquals("positive zero", 0, CgHalfFloat.toHalf(0f) & 0xffff);
        assertEquals("negative zero keeps its sign bit", 0x8000,
                CgHalfFloat.toHalf(-0f) & 0xffff);
    }

    @Test
    public void testOverflow_becomesInfinity_notGarbage() {
        // 65504 is the largest finite binary16; anything beyond must saturate to infinity
        // rather than wrapping into a bogus finite value.
        assertTrue(Float.isInfinite(halfToFloat(CgHalfFloat.toHalf(1e30f))));
        assertTrue(halfToFloat(CgHalfFloat.toHalf(1e30f)) > 0);
        assertTrue(Float.isInfinite(halfToFloat(CgHalfFloat.toHalf(-1e30f))));
        assertTrue(halfToFloat(CgHalfFloat.toHalf(-1e30f)) < 0);

        assertTrue(Float.isInfinite(halfToFloat(CgHalfFloat.toHalf(Float.POSITIVE_INFINITY))));
        assertTrue(Float.isInfinite(halfToFloat(CgHalfFloat.toHalf(Float.NEGATIVE_INFINITY))));
    }

    @Test
    public void testLargestFiniteHalf_staysFinite() {
        float max = 65504f;
        float actual = halfToFloat(CgHalfFloat.toHalf(max));
        assertFalse("65504 is representable and must not saturate to infinity", Float.isInfinite(actual));
        assertEquals(max, actual, 0f);
    }

    @Test
    public void testNaN_staysNaN() {
        assertTrue(Float.isNaN(halfToFloat(CgHalfFloat.toHalf(Float.NaN))));
    }

    @Test
    public void testSubnormals_doNotBecomeZeroPrematurely() {
        // Smallest positive binary16 subnormal is ~5.96e-8; values above it must survive.
        float smallestSubnormal = 5.960464e-8f;
        assertNotEquals("smallest subnormal must not flush to zero", 0,
                CgHalfFloat.toHalf(smallestSubnormal) & 0x7fff);

        // Well below the subnormal range, flushing to (signed) zero is correct.
        assertEquals("underflow flushes to +0", 0, CgHalfFloat.toHalf(1e-20f) & 0xffff);
        assertEquals("underflow keeps the sign", 0x8000, CgHalfFloat.toHalf(-1e-20f) & 0xffff);
    }

    @Test
    public void testToFloat_agreesWithTheReferenceDecoder() {
        // Sweeps every one of the 65536 binary16 bit patterns, so normals, subnormals, both
        // zeroes, both infinities and the whole NaN space are all covered exhaustively.
        for (int bits = 0; bits <= 0xffff; bits++) {
            short half = (short) bits;
            float expected = halfToFloat(half);
            float actual = CgHalfFloat.toFloat(half);
            if (Float.isNaN(expected)) {
                assertTrue("bit pattern 0x" + Integer.toHexString(bits) + " should decode to NaN",
                        Float.isNaN(actual));
            } else {
                assertEquals("bit pattern 0x" + Integer.toHexString(bits), expected, actual, 0f);
            }
        }
    }

    @Test
    public void testRoundTrip_throughBothDirections_isStableForRepresentableValues() {
        // toFloat(toHalf(x)) must be a fixed point once x is already representable — a second
        // pass through the pair must not drift.
        for (int bits = 0; bits <= 0xffff; bits++) {
            float once = CgHalfFloat.toFloat((short) bits);
            if (Float.isNaN(once) || Float.isInfinite(once)) continue;
            float twice = CgHalfFloat.toFloat(CgHalfFloat.toHalf(once));
            assertEquals("re-encoding an already-representable value must be lossless",
                    once, twice, 0f);
        }
    }

    @Test
    public void testMonotonicity_acrossUnitRange() {
        // A conversion bug in the rounding/exponent path typically shows up as a non-monotonic
        // step; MSDF reconstruction would render that as a visible artifact.
        float previous = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= 2000; i++) {
            float decoded = halfToFloat(CgHalfFloat.toHalf(i / 2000f));
            assertTrue("conversion must be monotonic non-decreasing at i=" + i, decoded >= previous);
            previous = decoded;
        }
    }
}
