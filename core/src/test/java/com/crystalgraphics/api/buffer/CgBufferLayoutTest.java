package com.crystalgraphics.api.buffer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for {@link CgBufferLayout.Builder#build()}'s final stride rounding.
 *
 * <p>std140/std430 both require a struct's own overall size — its per-element stride
 * when used in an array, which every {@code CgBufferLayout} here always is (SSBO
 * records) — to be rounded up to a multiple of 16 (the base alignment of {@code vec4}),
 * on top of each individual field's own alignment. Before this was fixed, {@code build()}
 * used the raw field-sum cursor as the stride with no final rounding; every format that
 * existed at the time happened to sum to an exact multiple of 16 by coincidence, so this
 * went uncaught until {@code CgQuadBatch.INSTANCE_FORMAT} added a trailing
 * {@code float atlasLayer} field after {@code vec3×3 + vec2×2 + vec4}, landing at 84
 * bytes — not a multiple of 16. The GPU still rounds every array-of-struct element to 16
 * bytes regardless of what the CPU assumes, so an unrounded CPU-side stride desyncs every
 * record after the first: each subsequent instance's fields get read from the wrong byte
 * offset, worse with each index — this is the exact bug that produced garbled text one
 * glyph at a time in {@code CgQuadBatch}-based rendering once a struct's raw field sum
 * stopped landing on a 16-byte boundary by luck.
 */
public class CgBufferLayoutTest {

    @Test
    public void build_roundsStrideUpTo16_whenFieldsAlreadyAligned() {
        // vec3(16) + vec3(16) + vec3(16) + vec2(8) + vec2(8) + vec4(16) = 80 bytes,
        // already a multiple of 16 — the case every pre-existing format happened to hit.
        CgBufferLayout f = CgBufferLayout.builder("AlreadyAligned", CgBufferLayout.MemoryLayout.STD430)
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
        // multiple of 16 — reproducing CgQuadBatch.INSTANCE_FORMAT's exact shape
        // (origin/right/up/uv0/uv1/color/atlasLayer). Must round up to 96, not stay at 84.
        CgBufferLayout f = CgBufferLayout.builder("TrailingScalar", CgBufferLayout.MemoryLayout.STD430)
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
    public void build_scalarAfterVec3TakesItsTail_asGlslPlacesIt() {
        CgBufferLayout f = CgBufferLayout.builder("Corner", CgBufferLayout.MemoryLayout.STD430)
                .vec3("origin").float_("fade").vec3("right").vec2("uv").vec4("color").build();
        assertEquals(12, f.getField("fade").getByteOffset());
        assertEquals(16, f.getField("right").getByteOffset());
        assertEquals(32, f.getField("uv").getByteOffset());   // a vec2 after a vec3 still starts at the next 16
        assertEquals(48, f.getField("color").getByteOffset());
        assertEquals(64, f.getStride());
    }

    @Test
    public void build_mat3ColumnsStayPadded() {
        CgBufferLayout f = CgBufferLayout.builder("M", CgBufferLayout.MemoryLayout.STD430)
                .add("m", CgGpuType.MAT3).float_("after").build();
        assertEquals(48, f.getField("after").getByteOffset());
    }

    @Test
    public void build_strideIsAlwaysAMultipleOf16_forArbitraryFieldCombinations() {
        // A handful of field-count parities that could plausibly land off a 16-byte
        // boundary if the rounding were ever removed again.
        assertTrue(isMultipleOf16(CgBufferLayout.builder("OneFloat", CgBufferLayout.MemoryLayout.STD430)
                .float_("a").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferLayout.builder("ThreeFloats", CgBufferLayout.MemoryLayout.STD430)
                .float_("a").float_("b").float_("c").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferLayout.builder("Vec3PlusFloat", CgBufferLayout.MemoryLayout.STD430)
                .vec3("v").float_("f").build().getStride()));
        assertTrue(isMultipleOf16(CgBufferLayout.builder("FiveVec2", CgBufferLayout.MemoryLayout.STD430)
                .vec2("a").vec2("b").vec2("c").vec2("d").vec2("e").build().getStride()));
    }

    private static boolean isMultipleOf16(int stride) {
        return stride % 16 == 0;
    }
}
