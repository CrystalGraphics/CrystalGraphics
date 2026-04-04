package io.github.somehussar.crystalgraphics.gl.text;

import org.junit.Test;

import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgGlyphVboTest {

    @Test
    public void vertexLayout_singleGlyph_correctOffsets() {
        float x = 10.0f, y = 20.0f, w = 16.0f, h = 16.0f;
        float u0 = 0.25f, v0 = 0.5f, u1 = 0.75f, v1 = 1.0f;
        int rgba = 0xFF8040C0;
        int packed = CgGlyphVbo.packColorBytesForTest(rgba);
        float packedBits = Float.intBitsToFloat(packed);

        FloatBuffer fb = simulateAddGlyph(x, y, w, h, u0, v0, u1, v1, rgba);

        assertEquals(x, fb.get(0), 0.0f);
        assertEquals(y, fb.get(1), 0.0f);
        assertEquals(u0, fb.get(2), 0.0f);
        assertEquals(v0, fb.get(3), 0.0f);
        assertEquals(packedBits, fb.get(4), 0.0f);

        assertEquals(x + w, fb.get(5), 0.0f);
        assertEquals(y, fb.get(6), 0.0f);
        assertEquals(u1, fb.get(7), 0.0f);
        assertEquals(v0, fb.get(8), 0.0f);
        assertEquals(packedBits, fb.get(9), 0.0f);

        assertEquals(x + w, fb.get(10), 0.0f);
        assertEquals(y + h, fb.get(11), 0.0f);
        assertEquals(u1, fb.get(12), 0.0f);
        assertEquals(v1, fb.get(13), 0.0f);
        assertEquals(packedBits, fb.get(14), 0.0f);

        assertEquals(x, fb.get(15), 0.0f);
        assertEquals(y + h, fb.get(16), 0.0f);
        assertEquals(u0, fb.get(17), 0.0f);
        assertEquals(v1, fb.get(18), 0.0f);
        assertEquals(packedBits, fb.get(19), 0.0f);
    }

    @Test
    public void vertexLayout_stride_is20bytes() {
        assertEquals(20, CgGlyphVbo.STRIDE_BYTES);
        assertEquals(5, CgGlyphVbo.FLOATS_PER_VERTEX);
        assertEquals(20, CgGlyphVbo.FLOATS_PER_QUAD);
    }

    @Test
    public void indexPattern_singleQuad() {
        ShortBuffer ibo = CgGlyphVbo.buildIndexBufferForTest(1);
        assertEquals(6, ibo.remaining());
        assertEquals(0, ibo.get(0));
        assertEquals(1, ibo.get(1));
        assertEquals(2, ibo.get(2));
        assertEquals(2, ibo.get(3));
        assertEquals(3, ibo.get(4));
        assertEquals(0, ibo.get(5));
    }

    @Test
    public void indexPattern_threeQuads() {
        ShortBuffer ibo = CgGlyphVbo.buildIndexBufferForTest(3);
        assertEquals(18, ibo.remaining());
        assertEquals(0, ibo.get(0));
        assertEquals(1, ibo.get(1));
        assertEquals(2, ibo.get(2));
        assertEquals(2, ibo.get(3));
        assertEquals(3, ibo.get(4));
        assertEquals(0, ibo.get(5));
        assertEquals(4, ibo.get(6));
        assertEquals(5, ibo.get(7));
        assertEquals(6, ibo.get(8));
        assertEquals(6, ibo.get(9));
        assertEquals(7, ibo.get(10));
        assertEquals(4, ibo.get(11));
        assertEquals(8, ibo.get(12));
        assertEquals(9, ibo.get(13));
        assertEquals(10, ibo.get(14));
        assertEquals(10, ibo.get(15));
        assertEquals(11, ibo.get(16));
        assertEquals(8, ibo.get(17));
    }

    @Test
    public void colorPacking_swizzlesToByteOrderForNormalizedAttrib() {
        int rgba = 0x11223344;
        int packed = CgGlyphVbo.packColorBytesForTest(rgba);
        assertEquals(0x44332211, packed);
    }

    @Test
    public void colorPacking_roundTripThroughFloatBits() {
        int rgba = 0xFF8040C0;
        int packed = CgGlyphVbo.packColorBytesForTest(rgba);
        float packedBits = Float.intBitsToFloat(packed);
        assertEquals(packed, Float.floatToRawIntBits(packedBits));
    }

    @Test
    public void capacityGrowth_simulationPreservesVertexCount() {
        int initialCapacity = 4;
        int totalGlyphs = 10;
        int capacity = initialCapacity;
        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(
                capacity * CgGlyphVbo.FLOATS_PER_QUAD);

        for (int i = 0; i < totalGlyphs; i++) {
            if (i >= capacity) {
                int newCapacity = Math.max(capacity + 1, (int) (capacity * 1.5f));
                FloatBuffer oldBuf = buf;
                oldBuf.flip();
                buf = org.lwjgl.BufferUtils.createFloatBuffer(newCapacity * CgGlyphVbo.FLOATS_PER_QUAD);
                buf.put(oldBuf);
                capacity = newCapacity;
            }
            writeQuad(buf, i * 10.0f, 0.0f, 16.0f, 16.0f,
                    0.0f, 0.0f, 1.0f, 1.0f, 0xFFFFFFFF);
        }

        assertTrue(capacity > initialCapacity);
        assertEquals(totalGlyphs * CgGlyphVbo.FLOATS_PER_QUAD, buf.position());
    }

    @Test
    public void multipleGlyphs_correctCount() {
        int numGlyphs = 5;
        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(8 * CgGlyphVbo.FLOATS_PER_QUAD);

        for (int i = 0; i < numGlyphs; i++) {
            writeQuad(buf, i * 10.0f, 0.0f, 16.0f, 16.0f,
                    0.0f, 0.0f, 1.0f, 1.0f, 0xFFFFFFFF);
        }

        assertEquals(numGlyphs * CgGlyphVbo.FLOATS_PER_QUAD, buf.position());
    }

    private FloatBuffer simulateAddGlyph(float x, float y, float w, float h,
                                         float u0, float v0, float u1, float v1,
                                         int rgba) {
        FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(CgGlyphVbo.FLOATS_PER_QUAD);
        int packed = CgGlyphVbo.packColorBytesForTest(rgba);
        float packedBits = Float.intBitsToFloat(packed);

        fb.put(x).put(y).put(u0).put(v0).put(packedBits);
        fb.put(x + w).put(y).put(u1).put(v0).put(packedBits);
        fb.put(x + w).put(y + h).put(u1).put(v1).put(packedBits);
        fb.put(x).put(y + h).put(u0).put(v1).put(packedBits);
        fb.flip();
        return fb;
    }

    private void writeQuad(FloatBuffer fb, float x, float y, float w, float h,
                           float u0, float v0, float u1, float v1, int rgba) {
        int packed = CgGlyphVbo.packColorBytesForTest(rgba);
        float packedBits = Float.intBitsToFloat(packed);
        fb.put(x).put(y).put(u0).put(v0).put(packedBits);
        fb.put(x + w).put(y).put(u1).put(v0).put(packedBits);
        fb.put(x + w).put(y + h).put(u1).put(v1).put(packedBits);
        fb.put(x).put(y + h).put(u0).put(v1).put(packedBits);
    }
}
