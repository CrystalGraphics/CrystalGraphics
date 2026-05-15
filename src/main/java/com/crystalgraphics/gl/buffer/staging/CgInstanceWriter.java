package com.crystalgraphics.gl.buffer.staging;

import com.crystalgraphics.api.vertex.CgInstanceFormat;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class CgInstanceWriter {

    private final CgStagingBuffer staging;
    private final CgInstanceFormat layout;

    private int instanceStartCursor;

    public CgInstanceWriter(CgStagingBuffer staging, CgInstanceFormat layout) {
        this.staging = staging;
        this.layout = layout;
    }

    public CgInstanceFormat layout() {
        return layout;
    }

    public CgInstanceWriter putFloat(float v) {
        staging.putFloat(v);
        return this;
    }

    public CgInstanceWriter vec2(float x, float y) {
        staging.putFloat(x);
        staging.putFloat(y);
        return this;
    }

    public CgInstanceWriter vec3(float x, float y, float z) {
        staging.putFloat(x);
        staging.putFloat(y);
        staging.putFloat(z);
        return this;
    }

    public CgInstanceWriter vec4(float x, float y, float z, float w) {
        staging.putFloat(x);
        staging.putFloat(y);
        staging.putFloat(z);
        staging.putFloat(w);
        return this;
    }

    /**
     * Writes a mat3 as three tightly-packed vec3 columns (9 floats / 36 bytes).
     *
     * <p>This matches {@link CgInstanceFormat.Builder#mat3}
     * which allocates three physical {@code vec3} attribute slots (3 × 12 bytes = 36 bytes).
     * Layout written (column-major, 9 floats):</p>
     * <pre>
     *   col0: [m00, m01, m02]
     *   col1: [m10, m11, m12]
     *   col2: [m20, m21, m22]
     * </pre>
     */
    public CgInstanceWriter mat3(Matrix3f m) {
        // Column 0
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02());
        // Column 1
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12());
        // Column 2
        staging.putFloat(m.m20()); staging.putFloat(m.m21()); staging.putFloat(m.m22());
        return this;
    }

    public CgInstanceWriter mat4(Matrix4f m) {
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02()); staging.putFloat(m.m03());
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12()); staging.putFloat(m.m13());
        staging.putFloat(m.m20()); staging.putFloat(m.m21()); staging.putFloat(m.m22()); staging.putFloat(m.m23());
        staging.putFloat(m.m30()); staging.putFloat(m.m31()); staging.putFloat(m.m32()); staging.putFloat(m.m33());
        return this;
    }

    public CgInstanceWriter colorARGB(int argb) {
        return color(argb >> 16 & 0xff, argb >> 8 & 0xff, argb & 0xff, argb >> 24 & 0xff);
    }

    public CgInstanceWriter colorRGBA(int rgba) {
        return color(rgba >> 24 & 0xff, rgba >> 16 & 0xff, rgba >> 8 & 0xff, rgba & 0xff);
    }

    public CgInstanceWriter color(int r, int g, int b, int a) {
        staging.putIntBits(CgColorPacking.packNativeOrder(r, g, b, a));
        return this;
    }

    public CgInstanceWriter beginInstance() {
        instanceStartCursor = staging.rawCursor();
        return this;
    }

    public void endInstance() {
        int writtenFloats = staging.rawCursor() - instanceStartCursor;
        int expectedFloats = layout.getFloatsPerInstance();
        if (writtenFloats != expectedFloats) {
            throw new IllegalStateException(
                "CgInstanceWriter.endInstance(): wrote " + writtenFloats + " floats but layout expects "
                + expectedFloats + " floats (stride=" + layout.getStride() + " bytes) for '"
                + layout.getDebugName() + "'");
        }
        staging.ensureRoomForNextVertex();
    }
}
