package io.github.somehussar.crystalgraphics.text.render;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

/**
 * Render context for 3D world-space text.
 *
 * <p>This is the world-text counterpart of {@link CgTextRenderContext}. It wraps
 * a perspective (or orthographic) projection matrix, the viewport dimensions,
 * and a {@link PerspectiveScaleResolver} that enforces always-MSDF rendering.</p>
 *
 * <h3>Contract Differences from CgTextRenderContext</h3>
 * <ul>
 *   <li><strong>Always MSDF</strong>: The scale resolver always returns
 *       {@code shouldUseMsdf() == true}. No bitmap fallback.</li>
 *   <li><strong>Projection-aware raster tier</strong>: Callers can update the
 *       projected-size hint via {@link #updateProjectedSize(Matrix4f, Matrix4f, int)}
 *       to adapt the MSDF atlas raster tier to viewing distance. When set, the
 *       hint directly determines the effective raster size used for glyph cache
 *       lookups (replacing the default 2x multiplier). Layout metrics are
 *       unaffected — only the physical rasterization tier changes.</li>
 *   <li><strong>Depth test enabled</strong>: World text should render with
 *       depth testing on (unlike 2D UI text which disables it).</li>
 *   <li><strong>No orthographic factory</strong>: Use
 *       {@link CgTextRenderContext#orthographic(int, int)} for 2D UI text.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used on the render thread.</p>
 *
 * @see CgTextRenderContext
 * @see PerspectiveScaleResolver
 * @see ProjectedSizeEstimator
 */
public class CgWorldTextRenderContext extends CgTextRenderContext {

    private final PerspectiveScaleResolver worldResolver;
    private int viewportWidth;
    private int viewportHeight;

    /**
     * Creates a world-text render context with the given projection matrix
     * and viewport dimensions.
     *
     * @param projection     4x4 projection matrix in column-major order
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    public CgWorldTextRenderContext(FloatBuffer projection,
                                    int viewportWidth,
                                    int viewportHeight) {
        super(projection, new PerspectiveScaleResolver());
        this.worldResolver = (PerspectiveScaleResolver) getScaleResolver();
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /**
     * Factory method for creating a world-text render context from a JOML
     * projection matrix.
     *
     * @param projection     the projection matrix (typically perspective)
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @return a new world-text render context
     */
    public static CgWorldTextRenderContext create(Matrix4f projection,
                                                   int viewportWidth,
                                                   int viewportHeight) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        projection.get(buf);
        buf.rewind();
        return new CgWorldTextRenderContext(buf, viewportWidth, viewportHeight);
    }

    /**
     * Updates the projected-size hint for MSDF raster tier selection.
     *
     * <p>Call this before each world-text draw to provide the resolver with
     * an estimate of how large the text appears on screen. The hint directly
     * determines the effective raster size used for glyph cache lookups,
     * replacing the default 2x multiplier. Layout metrics are unaffected.</p>
     *
     * @param modelView      the model-view matrix positioning the text in world space
     * @param projection     the current projection matrix
     * @param baseTargetPx   the base font target pixel size
     */
    public void updateProjectedSize(Matrix4f modelView,
                                     Matrix4f projection,
                                     int baseTargetPx) {
        float projectedPx = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, viewportWidth, viewportHeight, baseTargetPx);
        worldResolver.setProjectedSizeHint(projectedPx);
    }

    /**
     * Updates the projection matrix and viewport dimensions.
     *
     * @param projection     new projection matrix
     * @param viewportWidth  new viewport width
     * @param viewportHeight new viewport height
     */
    public void updateProjection(Matrix4f projection, int viewportWidth, int viewportHeight) {
        FloatBuffer buf = getProjectionBuffer();
        buf.clear();
        projection.get(buf);
        buf.rewind();
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /**
     * Clears the projected-size hint, reverting to the default MSDF raster tier.
     */
    public void clearProjectedSizeHint() {
        worldResolver.clearProjectedSizeHint();
    }

    /**
     * Returns the viewport width.
     */
    public int getViewportWidth() {
        return viewportWidth;
    }

    /**
     * Returns the viewport height.
     */
    public int getViewportHeight() {
        return viewportHeight;
    }

    /**
     * Returns whether this context is configured for world-space text.
     * Always {@code true} for this class.
     */
    public boolean isWorldText() {
        return true;
    }
}
