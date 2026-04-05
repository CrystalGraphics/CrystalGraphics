package io.github.somehussar.crystalgraphics.harness.util;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import java.nio.FloatBuffer;

/**
 * Shared projection matrix construction and perspective constants for harness scenes.
 *
 * <p>Provides orthographic projection helpers and the canonical perspective projection
 * constants used by all 3D interactive scenes and the {@code InteractiveSceneRunner}.
 * All scenes that need a perspective projection should reference these constants
 * rather than declaring their own copies.</p>
 */
public class HarnessProjectionUtil {

    // ── Shared perspective projection constants ──
    // Used by InteractiveSceneRunner, InteractiveWorldTextScene, Camera3DValidationScene,
    // RenderValidationScene, and any future 3D scene that needs a standard
    // perspective projection matching the floor/HUD/pause pipeline.

    /** Default field of view in degrees for 3D harness scenes. */
    public static final float FOV_DEGREES = 60.0f;

    /** Near clipping plane distance for 3D harness scenes. */
    public static final float NEAR_PLANE = 0.1f;

    /** Far clipping plane distance for 3D harness scenes. */
    public static final float FAR_PLANE = 1000.0f;

    /**
     * Builds a perspective projection matrix using the shared harness constants.
     *
     * @param aspectRatio viewport width / height
     * @return a new perspective projection matrix
     */
    public static Matrix4f perspective(float aspectRatio) {
        return new Matrix4f().perspective(
                (float) Math.toRadians(FOV_DEGREES), aspectRatio, NEAR_PLANE, FAR_PLANE);
    }

    /**
     * Builds a perspective projection matrix using the shared harness constants
     * for the given viewport dimensions.
     *
     * @param width  viewport width in pixels
     * @param height viewport height in pixels
     * @return a new perspective projection matrix
     */
    public static Matrix4f perspective(int width, int height) {
        return perspective((float) width / (float) height);
    }

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
