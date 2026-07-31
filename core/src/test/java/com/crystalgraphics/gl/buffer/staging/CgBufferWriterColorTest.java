package com.crystalgraphics.gl.buffer.staging;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Channel-order tests for {@link CgBufferWriter#color(String, int)}.
 *
 * <p>That method exists to be the single place packed ARGB becomes a GLSL {@code vec4}, which makes
 * its channel order load-bearing for every instanced renderer at once. The failure it guards against
 * is a red/blue swap or a dropped alpha — both of which read as perfectly reasonable code, produce no
 * error, and surface only as "the colours are wrong" across the whole engine.</p>
 *
 * <p>Pure CPU: a {@link CgStagingBuffer} needs no GL context.</p>
 */
public class CgBufferWriterColorTest {

    /** One vec4 field, so slots 0..3 of the record are exactly r,g,b,a. */
    private static final CgBufferFormat FORMAT = CgBufferFormat
            .builder("ColorTest", CgBufferFormat.MemoryLayout.STD430)
            .vec4("tint")
            .build();

    private static float[] writeColor(int argb) {
        CgStagingBuffer staging = new CgStagingBuffer(FORMAT.getFloatCount(), 1);
        CgBufferWriter writer = new CgBufferWriter(staging, FORMAT);
        writer.beginRecord().color("tint", argb).endRecord();
        return staging.rawData();
    }

    /** Each channel must land in its own slot, in (r, g, b, a) order. */
    @Test
    public void color_unpacksArgbIntoRgbaOrder() {
        // Distinct per channel so any permutation fails — 0xFFFFFFFF would pass regardless.
        float[] d = writeColor(0x11223344);
        assertEquals("red",   0x22 / 255f, d[0], 0f);
        assertEquals("green", 0x33 / 255f, d[1], 0f);
        assertEquals("blue",  0x44 / 255f, d[2], 0f);
        assertEquals("alpha", 0x11 / 255f, d[3], 0f);
    }

    /** Pure red must not come out as pure blue — the specific swap most likely to happen. */
    @Test
    public void color_doesNotSwapRedAndBlue() {
        float[] d = writeColor(0xFFFF0000);
        assertEquals(1f, d[0], 0f);
        assertEquals(0f, d[1], 0f);
        assertEquals(0f, d[2], 0f);
        assertEquals(1f, d[3], 0f);
    }

    /** A fully transparent colour must produce alpha 0, not 1 from a dropped high byte. */
    @Test
    public void color_preservesZeroAlpha() {
        float[] d = writeColor(0x00FFFFFF);
        assertEquals(0f, d[3], 0f);
        assertEquals(1f, d[0], 0f);
    }

    /** The high bit of alpha must survive — 0x80+ is where a signed shift would corrupt it. */
    @Test
    public void color_handlesHighBitAlphaWithoutSignExtension() {
        float[] d = writeColor(0x80000000);
        assertEquals(0x80 / 255f, d[3], 0f);
        assertEquals(0f, d[0], 0f);
    }

    /** Opaque white is all ones — the sanity anchor. */
    @Test
    public void color_opaqueWhiteIsAllOnes() {
        float[] d = writeColor(0xFFFFFFFF);
        for (int i = 0; i < 4; i++) {
            assertEquals("channel " + i, 1f, d[i], 0f);
        }
    }

    /**
     * Must be bit-identical to the hand-rolled unpacking this replaced in {@code CgQuadRenderer},
     * which is the path every glyph in the engine draws through. A reciprocal multiply would be
     * within an ULP and still a change to already-shipped output.
     */
    @Test
    public void color_isBitIdenticalToTheDivisionItReplaced() {
        for (int v = 0; v < 256; v++) {
            int argb = (v << 24) | (v << 16) | (v << 8) | v;
            float[] d = writeColor(argb);
            float expected = v / 255f;
            for (int i = 0; i < 4; i++) {
                assertEquals("channel " + i + " for byte " + v,
                        Float.floatToIntBits(expected), Float.floatToIntBits(d[i]));
            }
        }
    }

    /** Writing a colour to a non-VEC4 field must fail loudly rather than scribble. */
    @Test
    public void color_rejectsANonVec4Field() {
        CgBufferFormat wrong = CgBufferFormat
                .builder("WrongTest", CgBufferFormat.MemoryLayout.STD430)
                .vec2("tint")
                .build();
        CgStagingBuffer staging = new CgStagingBuffer(wrong.getFloatCount(), 1);
        CgBufferWriter writer = new CgBufferWriter(staging, wrong);
        writer.beginRecord();
        try {
            writer.color("tint", 0xFFFFFFFF);
            fail("expected a type mismatch for a VEC2 field");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("tint"));
        }
    }
}
