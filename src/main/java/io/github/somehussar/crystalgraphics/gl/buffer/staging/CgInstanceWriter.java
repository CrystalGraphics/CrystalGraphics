package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class CgInstanceWriter {

    private static final boolean DEBUG = true;

    private final CgStagingBuffer staging;
    private final CgInstanceLayout layout;

    private int instanceStartCursor;

    public CgInstanceWriter(CgStagingBuffer staging, CgInstanceLayout layout) {
        this.staging = staging;
        this.layout = layout;
    }

    public CgInstanceLayout layout() {
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

    public CgInstanceWriter mat3(Matrix3f m) {
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02());
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12());
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
        staging.putColorPacked(CgColorPacking.packNativeOrder(r, g, b, a));
        return this;
    }

    public CgInstanceWriter beginInstance() {
        if (DEBUG) instanceStartCursor = staging.rawCursor();
        return this;
    }

    public void endInstance() {
        if (DEBUG) {
            int writtenFloats = staging.rawCursor() - instanceStartCursor;
            int expectedFloats = layout.getFloatsPerInstance();
            if (writtenFloats != expectedFloats) {
                throw new IllegalStateException(
                    "CgInstanceWriter.endInstance(): wrote " + writtenFloats + " floats but layout expects "
                    + expectedFloats + " floats (stride=" + layout.getStride() + " bytes) for '"
                    + layout.getDebugName() + "'");
            }
        }
        staging.ensureRoomForNextVertex();
    }
}
