package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;

/**
 * Shared orthographic projection matrix construction for harness scenes.
 */
public  class HarnessProjectionUtil {

    /**
     * Creates a column-major orthographic projection matrix.
     *
     * @param left   left clipping plane
     * @param right  right clipping plane
     * @param bottom bottom clipping plane
     * @param top    top clipping plane
     * @param near   near clipping plane
     * @param far    far clipping plane
     * @return a FloatBuffer containing the 4x4 column-major matrix, ready for GL upload
     */
    public static FloatBuffer ortho(float left, float right, float bottom, float top,
                             float near, float far) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        float dx = right - left;
        float dy = top - bottom;
        float dz = far - near;
        buf.put(2.0f / dx); buf.put(0);          buf.put(0);           buf.put(0);
        buf.put(0);          buf.put(2.0f / dy);  buf.put(0);           buf.put(0);
        buf.put(0);          buf.put(0);          buf.put(-2.0f / dz);  buf.put(0);
        buf.put(-(right + left) / dx);
        buf.put(-(top + bottom) / dy);
        buf.put(-(far + near) / dz);
        buf.put(1.0f);
        buf.flip();
        return buf;
    }

    /**
     * Screen-space ortho: left=0, right=width, bottom=height, top=0, near=-1, far=1.
     */
    public static FloatBuffer screenOrtho(int width, int height) {
        return ortho(0, width, height, 0, -1, 1);
    }

    private HarnessProjectionUtil() { }
}
