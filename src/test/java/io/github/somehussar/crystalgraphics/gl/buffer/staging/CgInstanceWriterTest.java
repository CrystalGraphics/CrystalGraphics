package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import com.crystalgraphics.api.vertex.CgInstanceFormat;
import com.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import org.joml.Matrix4f;
import org.junit.Test;

import static org.junit.Assert.*;

public class CgInstanceWriterTest {

    private CgInstanceWriter writerFor(CgInstanceFormat layout) {
        CgStagingBuffer staging = new CgStagingBuffer(layout.getFloatsPerInstance(), 4);
        return new CgInstanceWriter(staging, layout);
    }

    private CgInstanceWriter writerFor(CgInstanceFormat layout, CgStagingBuffer staging) {
        return new CgInstanceWriter(staging, layout);
    }

    @Test
    public void testTransformColorCustomOneInstanceIs84Bytes() {
        CgStagingBuffer staging = new CgStagingBuffer(CgInstanceFormat.TRANSFORM_COLOR_CUSTOM.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, CgInstanceFormat.TRANSFORM_COLOR_CUSTOM);

        w.beginInstance();
        w.mat4(new Matrix4f());
        w.color(255, 128, 64, 255);
        w.vec4(1f, 2f, 3f, 4f);
        w.endInstance();

        assertEquals("One TRANSFORM_COLOR_CUSTOM instance must be exactly 84 bytes",
                84, staging.rawCursor() * Float.BYTES);
    }

    @Test
    public void testTransformColorCustomTwoInstancesIs168Bytes() {
        CgStagingBuffer staging = new CgStagingBuffer(CgInstanceFormat.TRANSFORM_COLOR_CUSTOM.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, CgInstanceFormat.TRANSFORM_COLOR_CUSTOM);

        for (int i = 0; i < 2; i++) {
            w.beginInstance();
            w.mat4(new Matrix4f());
            w.color(255, 128, 64, 255);
            w.vec4(1f, 2f, 3f, 4f);
            w.endInstance();
        }

        assertEquals("Two TRANSFORM_COLOR_CUSTOM instances must be 168 bytes",
                168, staging.rawCursor() * Float.BYTES);
    }

    @Test
    public void testColorPackedAsRGBAInFloatBits() {
        CgInstanceFormat colorOnly = CgInstanceFormat.builder("color-only").color4UB("a_color").build();
        CgStagingBuffer staging = new CgStagingBuffer(colorOnly.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, colorOnly);

        w.beginInstance();
        w.color(1, 2, 3, 4);
        w.endInstance();

        int bits = Float.floatToRawIntBits(staging.rawData()[0]);
        assertEquals("R must be 1",  1, bits & 0xFF);
        assertEquals("G must be 2",  2, (bits >> 8)  & 0xFF);
        assertEquals("B must be 3",  3, (bits >> 16) & 0xFF);
        assertEquals("A must be 4",  4, (bits >> 24) & 0xFF);
    }

    @Test
    public void testColorRGBAPacked() {
        CgInstanceFormat colorOnly = CgInstanceFormat.builder("color-only").color4UB("a_color").build();
        CgStagingBuffer staging = new CgStagingBuffer(colorOnly.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, colorOnly);

        w.beginInstance();
        w.colorRGBA(0x01020304);
        w.endInstance();

        int bits = Float.floatToRawIntBits(staging.rawData()[0]);
        assertEquals("R must be 0x01", 0x01, bits & 0xFF);
        assertEquals("G must be 0x02", 0x02, (bits >> 8)  & 0xFF);
        assertEquals("B must be 0x03", 0x03, (bits >> 16) & 0xFF);
        assertEquals("A must be 0x04", 0x04, (bits >> 24) & 0xFF);
    }

    @Test
    public void testMat4IdentityIs64Bytes() {
        CgInstanceFormat mat4Layout = CgInstanceFormat.builder("mat4-only").mat4("a_model").build();
        CgStagingBuffer staging = new CgStagingBuffer(mat4Layout.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, mat4Layout);

        w.beginInstance();
        w.mat4(new Matrix4f());
        w.endInstance();

        assertEquals("mat4-only layout must be 64 bytes", 64, staging.rawCursor() * Float.BYTES);
    }

    @Test(expected = IllegalStateException.class)
    public void testEndInstanceThrowsOnWrongFloatCount() {
        CgInstanceFormat layout = CgInstanceFormat.builder("vec4-only").vec4("a_x").build();
        CgStagingBuffer staging = new CgStagingBuffer(layout.getFloatsPerInstance(), 4);
        CgInstanceWriter w = new CgInstanceWriter(staging, layout);

        w.beginInstance();
        w.vec4(1f, 2f, 3f, 4f);
        w.putFloat(5f);
        w.endInstance();
    }
}
