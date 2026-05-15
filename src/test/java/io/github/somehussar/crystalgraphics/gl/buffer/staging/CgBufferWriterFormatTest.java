package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Exhaustive permutation tests for {@link CgBufferWriter} in format-aware mode.
 * All tests are pure Java — no GL context required.
 */
public class CgBufferWriterFormatTest {

    // ── 1. Single field format ─────────────────────────────────────────────────

    @Test
    public void singleMat4Format_allSixteenFloatsWritten() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("single_mat4", CgBufferFormat.MemoryLayout.STD430)
                .mat4("m")
                .build();
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);

        Matrix4f mat = new Matrix4f(
                1,2,3,4,
                5,6,7,8,
                9,10,11,12,
                13,14,15,16
        );
        w.beginRecord();
        w.mat4("m", mat);

        float[] data = staging.rawData();
        // Column-major: col0=[m00,m01,m02,m03], col1=[m10,m11,m12,m13], ...
        assertEquals(1f,  data[0],  0.0001f);
        assertEquals(2f,  data[1],  0.0001f);
        assertEquals(3f,  data[2],  0.0001f);
        assertEquals(4f,  data[3],  0.0001f);
        assertEquals(5f,  data[4],  0.0001f);
        assertEquals(6f,  data[5],  0.0001f);
        assertEquals(7f,  data[6],  0.0001f);
        assertEquals(8f,  data[7],  0.0001f);
        assertEquals(9f,  data[8],  0.0001f);
        assertEquals(10f, data[9],  0.0001f);
        assertEquals(11f, data[10], 0.0001f);
        assertEquals(12f, data[11], 0.0001f);
        assertEquals(13f, data[12], 0.0001f);
        assertEquals(14f, data[13], 0.0001f);
        assertEquals(15f, data[14], 0.0001f);
        assertEquals(16f, data[15], 0.0001f);
    }

    // ── 2. Partial write zeroes others ────────────────────────────────────────

    @Test
    public void partialWrite_unwrittenSlotIsZeroed() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("partial", CgBufferFormat.MemoryLayout.STD430)
                .mat4("a")
                .mat4("b")
                .vec4("c")
                .build();
        // total = 16 + 16 + 4 = 36 floats
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);

        Matrix4f someMatrix = new Matrix4f().identity().translate(1, 2, 3);
        w.beginRecord();
        w.mat4("a", someMatrix);
        w.vec4("c", 9f, 8f, 7f, 6f);
        // "b" not written — must remain zero

        float[] data = staging.rawData();
        // "b" starts at offset 16
        for (int i = 16; i < 32; i++) {
            assertEquals("b[" + (i - 16) + "] must be zero", 0f, data[i], 0f);
        }
        // "c" at offset 32
        assertEquals(9f, data[32], 0.0001f);
        assertEquals(8f, data[33], 0.0001f);
        assertEquals(7f, data[34], 0.0001f);
        assertEquals(6f, data[35], 0.0001f);
    }

    // ── 3. Multi-record, different fields per record ──────────────────────────

    @Test
    public void multiRecord_eachRecordIndependentlyZeroed() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("multi", CgBufferFormat.MemoryLayout.STD430)
                .mat4("model")
                .vec4("color")
                .vec4("extra")
                .build();
        // 16 + 4 + 4 = 24 floats per record
        int stride = fmt.getFloatCount(); // 24
        CgStagingBuffer staging = new CgStagingBuffer(stride, 3);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);

        // Record 0: only model
        w.beginRecord();
        w.mat4("model", new Matrix4f().identity());

        // Record 1: only color
        w.beginRecord();
        w.vec4("color", 0.1f, 0.2f, 0.3f, 0.4f);

        // Record 2: all three
        Matrix4f rec2Model = new Matrix4f().scale(2f);
        w.beginRecord();
        w.mat4("model", rec2Model);
        w.vec4("color", 1f, 0f, 0f, 1f);
        w.vec4("extra", 5f, 6f, 7f, 8f);

        float[] data = staging.rawData();

        // Record 0: model identity at [0..15], color+extra zero at [16..23]
        assertEquals(1f, data[0],  0.0001f);   // m00
        assertEquals(0f, data[16], 0f);        // color.x must be zero
        assertEquals(0f, data[20], 0f);        // extra.x must be zero

        // Record 1: model zero at [24..39], color at [40..43], extra zero at [44..47]
        assertEquals(0f, data[24], 0f);        // model.m00 must be zero
        assertEquals(0.1f, data[40], 0.0001f); // color.x
        assertEquals(0f, data[44], 0f);        // extra.x must be zero

        // Record 2: model scale(2) at [48..63], color at [64..67], extra at [68..71]
        assertEquals(2f,  data[48], 0.0001f);  // model.m00 = 2
        assertEquals(1f,  data[64], 0.0001f);  // color.x
        assertEquals(0f,  data[65], 0.0001f);  // color.y
        assertEquals(5f,  data[68], 0.0001f);  // extra.x
        assertEquals(8f,  data[71], 0.0001f);  // extra.w
    }

    // ── 4. Large format — alternating fields ─────────────────────────────────

    @Test
    public void largeFormat_unwrittenFieldsZero_writtenFieldsMatch() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("large", CgBufferFormat.MemoryLayout.STD430)
                .mat4("f0")
                .mat4("f1")
                .vec4("f2")
                .vec4("f3")
                .mat4("f4")
                .vec4("f5")
                .vec4("f6")
                .mat4("f7")
                .vec4("f8")
                .vec4("f9")
                .build();
        // 16+16+4+4+16+4+4+16+4+4 = 88 floats
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);

        w.beginRecord();
        // Write only f0, f2, f4, f6, f8 (skip f1, f3, f5, f7, f9)
        w.mat4("f0", new Matrix4f().identity());
        w.vec4("f2", 1f, 2f, 3f, 4f);
        w.mat4("f4", new Matrix4f().identity());
        w.vec4("f6", 5f, 6f, 7f, 8f);
        w.vec4("f8", 9f, 10f, 11f, 12f);

        float[] data = staging.rawData();

        // f0 at 0: identity — m00=1
        assertEquals(1f, data[0], 0.0001f);
        // f1 at 16: skipped — all zero
        for (int i = 16; i < 32; i++) assertEquals("f1[" + (i-16) + "]", 0f, data[i], 0f);
        // f2 at 32
        assertEquals(1f, data[32], 0.0001f);
        assertEquals(4f, data[35], 0.0001f);
        // f3 at 36: skipped — zero
        for (int i = 36; i < 40; i++) assertEquals("f3[" + (i-36) + "]", 0f, data[i], 0f);
        // f4 at 40: identity — m00=1
        assertEquals(1f, data[40], 0.0001f);
        // f5 at 56: skipped — zero
        for (int i = 56; i < 60; i++) assertEquals("f5[" + (i-56) + "]", 0f, data[i], 0f);
        // f6 at 60
        assertEquals(5f, data[60], 0.0001f);
        assertEquals(8f, data[63], 0.0001f);
        // f7 at 64: skipped — zero
        for (int i = 64; i < 80; i++) assertEquals("f7[" + (i-64) + "]", 0f, data[i], 0f);
        // f8 at 80
        assertEquals(9f,  data[80], 0.0001f);
        assertEquals(12f, data[83], 0.0001f);
        // f9 at 84: skipped — zero
        for (int i = 84; i < 88; i++) assertEquals("f9[" + (i-84) + "]", 0f, data[i], 0f);
    }

    // ── 5. Wrong field name throws ────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void wrongFieldName_throwsIllegalArgument() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("t", CgBufferFormat.MemoryLayout.STD430)
                .mat4("real")
                .build();
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);
        w.beginRecord();
        w.mat4("nonexistent", new Matrix4f()); // must throw — field not in format
    }

    // ── 6. Type mismatch throws ───────────────────────────────────────────────

    @Test(expected = IllegalStateException.class)
    public void typeMismatch_mat4FieldCalledAsVec4_throws() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("t", CgBufferFormat.MemoryLayout.STD430)
                .mat4("bigField")
                .build();
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);
        w.beginRecord();
        w.vec4("bigField", 1f, 2f, 3f, 4f); // bigField is MAT4 — must throw
    }

    @Test(expected = IllegalStateException.class)
    public void typeMismatch_vec4FieldCalledAsMat4_throws() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("t", CgBufferFormat.MemoryLayout.STD430)
                .vec4("smallField")
                .build();
        CgStagingBuffer staging = new CgStagingBuffer(fmt.getFloatCount(), 1);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);
        w.beginRecord();
        w.mat4("smallField", new Matrix4f()); // smallField is VEC4 — must throw
    }

    // ── 7. Multi-record same format — no bleed between records ───────────────

    @Test
    public void fiveRecords_zeroFieldsDoNotBleedAcrossRecords() {
        CgBufferFormat fmt = CgBufferFormat
                .builder("bleed", CgBufferFormat.MemoryLayout.STD430)
                .mat4("model")
                .vec4("color")
                .build();
        int stride = fmt.getFloatCount(); // 20 floats
        CgStagingBuffer staging = new CgStagingBuffer(stride, 5);
        CgBufferWriter w = new CgBufferWriter(staging, fmt);

        // Each of the 5 records writes only "color"; "model" must be zero in every record
        for (int r = 0; r < 5; r++) {
            w.beginRecord();
            w.vec4("color", r + 1f, r + 2f, r + 3f, r + 4f);
        }

        float[] data = staging.rawData();
        for (int r = 0; r < 5; r++) {
            int base = r * stride;
            // model (floats 0-15 of each record) must be zero
            for (int i = 0; i < 16; i++) {
                assertEquals("record " + r + " model[" + i + "]", 0f, data[base + i], 0f);
            }
            // color (floats 16-19 of each record) must match written values
            assertEquals("record " + r + " color.x", r + 1f, data[base + 16], 0.0001f);
            assertEquals("record " + r + " color.y", r + 2f, data[base + 17], 0.0001f);
            assertEquals("record " + r + " color.z", r + 3f, data[base + 18], 0.0001f);
            assertEquals("record " + r + " color.w", r + 4f, data[base + 19], 0.0001f);
        }
    }
}
