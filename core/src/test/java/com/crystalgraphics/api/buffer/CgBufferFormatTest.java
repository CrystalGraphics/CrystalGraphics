package com.crystalgraphics.api.buffer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for {@link CgBufferFormat.Builder#build()}'s final stride rounding.
 *
 * <p>std140/std430 both require a struct's own overall size — its per-element stride
 * when used in an array, which every {@code CgBufferFormat} here always is (SSBO/TBO
 * records) — to be rounded up to a multiple of 16 (the base alignment of {@code vec4}),
 * on top of each individual field's own alignment. Before this was fixed, {@code build()}
 * used the raw field-sum cursor as the stride with no final rounding; every format that
 * existed at the time happened to sum to an exact multiple of 16 by coincidence, so this
 * went uncaught until {@code CgQuadRenderer.INSTANCE_FORMAT} added a trailing
 * {@code float atlasLayer} field after {@code vec3×3 + vec2×2 + vec4}, landing at 84
 * bytes — not a multiple of 16. The GPU still rounds every array-of-struct element to 16
 * bytes regardless of what the CPU assumes, so an unrounded CPU-side stride desyncs every
 * record after the first: each subsequent instance's fields get read from the wrong byte
 * offset, worse with each index — this is the exact bug that produced garbled text one
 * glyph at a time in {@code CgQuadRenderer}-based rendering once a struct's raw field sum
 * stopped landing on a 16-byte boundary by luck.
 */
public class CgBufferFormatTest {

    @Test
    public void build_roundsStrideUpTo16_whenFieldsAlreadyAligned() {
        // vec3(16) + vec3(16) + vec3(16) + vec2(8) + vec2(8) + vec4(16) = 80 bytes,
        // already a multiple of 16 — the case every pre-existing format happened to hit.
        CgBufferFormat f = CgBufferFormat.builder("AlreadyAligned", CgBufferFormat.MemoryLayout.STD430)
                .vec3("origin").vec3("right").vec3("up")
                .vec2("uv0").vec2("uv1")
                .vec4("color")
                .build();
        assertEquals(80, f.getStride());
        assertEquals(20, f.getFloatCount());
    }

    @Test
    public void build_roundsStrideUpTo16_whenTrailingScalarBreaksAlignment() {
        // Same as above plus one trailing float: raw field sum is 84 bytes — NOT a
        // multiple of 16 — reproducing CgQuadRenderer.INSTANCE_FORMAT's exact shape
        // (origin/right/up/uv0/uv1/color/atlasLayer). Must round up to 96, not stay at 84.
        CgBufferFormat f = CgBufferFormat.builder("TrailingScalar", CgBufferFormat.MemoryLayout.STD430)
                .vec3("origin").vec3("right").vec3("up")
                .vec2("uv0").vec2("uv1")
                .vec4("color")
                .float_("atlasLayer")
                .build();
        assertEquals("Stride must round up to the next multiple of 16, not stay at the raw 84-byte field sum",
                96, f.getStride());
        assertEquals(24, f.getFloatCount());
        assertEquals(80, f.getField("atlasLayer").getByteOffset());
    }

    @Test
    public void build_strideIsAlwaysAMultipleOf16_forArbitraryFieldCombinations() {
        // A handful of field-count parities that could plausibly land off a 16-byte
        // boundary if the rounding were ever removed again.
        assertTrue(isMultipleOf16(CgBufferFormat.builder("OneFloat", CgBufferFormat.MemoryLayout.STD430)
                .float_("a").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferFormat.builder("ThreeFloats", CgBufferFormat.MemoryLayout.STD430)
                .float_("a").float_("b").float_("c").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferFormat.builder("Vec3PlusFloat", CgBufferFormat.MemoryLayout.STD430)
                .vec3("v").float_("f").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferFormat.builder("FiveVec2", CgBufferFormat.MemoryLayout.STD430)
                .vec2("a").vec2("b").vec2("c").vec2("d").vec2("e").build().getStride()));
    }

    private static boolean isMultipleOf16(int stride) {
        return stride % 16 == 0;
    }
}
