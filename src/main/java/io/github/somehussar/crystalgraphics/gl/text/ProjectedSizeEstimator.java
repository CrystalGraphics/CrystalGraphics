package io.github.somehussar.crystalgraphics.gl.text;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Estimates the on-screen pixel coverage of world-space text for MSDF
 * quality/LOD decisions.
 *
 * <p>This utility projects a unit-length vector through the combined
 * model-view-projection (MVP) matrix and viewport to estimate how many screen
 * pixels one logical text unit will occupy. The result drives MSDF atlas tier
 * selection — not layout metrics.</p>
 *
 * <h3>Usage</h3>
 * <pre>
 * float projectedPx = ProjectedSizeEstimator.estimateScreenPx(
 *         modelView, projection, viewportWidth, viewportHeight, baseTargetPx);
 * </pre>
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Assumes a center-of-text reference point at the origin of the model-view
 *       matrix. Off-center text will have slightly different projected sizes at
 *       edges, which is acceptable for LOD tier selection.</li>
 *   <li>Does not account for screen-space clipping — the estimated size may
 *       exceed the viewport for very close text.</li>
 * </ul>
 *
 * @see WorldTextScaleResolver
 */
public final class ProjectedSizeEstimator {

    private ProjectedSizeEstimator() {
        // utility class
    }

    /**
     * Estimates how many screen pixels one logical text unit occupies after
     * MVP projection.
     *
     * <p>The method projects two points — the text origin and a point offset
     * by {@code baseTargetPx} logical units along X — through the MVP and
     * viewport transform, then measures the screen-space distance between them.
     * This gives a physically meaningful projected pixel coverage for the
     * base font size.</p>
     *
     * @param modelView      the model-view matrix positioning the text in world space
     * @param projection     the projection matrix (perspective or orthographic)
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @param baseTargetPx   the base font target pixel size from CgFontKey.targetPx
     * @return estimated screen pixel coverage for the base font size,
     *         or {@code -1.0f} if the text is behind the camera or degenerate
     */
    public static float estimateScreenPx(Matrix4f modelView,
                                         Matrix4f projection,
                                         int viewportWidth,
                                         int viewportHeight,
                                         int baseTargetPx) {
        // Combine into MVP
        Matrix4f mvp = new Matrix4f(projection).mul(modelView);

        // Project the text origin
        Vector4f origin = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
        mvp.transform(origin);

        // Behind camera check
        if (origin.w <= 0.0f) {
            return -1.0f;
        }

        // Project a point offset by baseTargetPx along the X axis in model space
        Vector4f offset = new Vector4f((float) baseTargetPx, 0.0f, 0.0f, 1.0f);
        mvp.transform(offset);

        if (offset.w <= 0.0f) {
            return -1.0f;
        }

        // Perspective divide → NDC
        float ox = origin.x / origin.w;
        float oy = origin.y / origin.w;
        float px = offset.x / offset.w;
        float py = offset.y / offset.w;

        // NDC → screen pixels
        float screenOx = (ox * 0.5f + 0.5f) * viewportWidth;
        float screenOy = (oy * 0.5f + 0.5f) * viewportHeight;
        float screenPx = (px * 0.5f + 0.5f) * viewportWidth;
        float screenPy = (py * 0.5f + 0.5f) * viewportHeight;

        // Screen-space distance = projected pixel coverage of baseTargetPx logical units
        float dx = screenPx - screenOx;
        float dy = screenPy - screenOy;
        float screenDistance = (float) Math.sqrt(dx * dx + dy * dy);

        return screenDistance > 0.0f ? screenDistance : -1.0f;
    }
}
