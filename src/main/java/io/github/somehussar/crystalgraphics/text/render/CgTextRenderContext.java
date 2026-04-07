package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the projection and model-view state needed by the text renderer,
 * replacing the per-draw {@code FloatBuffer projectionMatrix} parameter.
 *
 * <p>A render context holds two concerns:</p>
 * <ol>
 *   <li><strong>Projection matrix</strong> — the orthographic (or future perspective)
 *       projection that maps world/screen coordinates to clip space. For 2D UI text,
 *       this is the standard orthographic matrix. The context owns this matrix and
 *       callers set it once (or update it on resize), rather than passing a raw
 *       {@code FloatBuffer} to every {@code draw()} call.</li>
 *   <li><strong>Scale resolver</strong> — the strategy for deriving effective physical
 *       glyph size from a {@link PoseStack} transform. For orthographic/UI rendering,
 *       this uses the cumulative scale from the top pose matrix. For world-space
 *       text, {@link PerspectiveScaleResolver} uses a fixed raster multiplier or a
 *       projected-size hint instead of pose scale.</li>
 * </ol>
 *
 * <h3>Design Rationale</h3>
 * <p>Previous API required every {@code CgTextRenderer.draw()} call to pass a
 * {@code FloatBuffer projectionMatrix}. This is ergonomically poor: the projection
 * rarely changes (only on window resize or projection switch), yet callers had to
 * manage and pass it every frame. This context object captures that state once.
 * The {@link PoseStack} — which <em>does</em> change per draw — is still passed
 * directly to the draw call.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used on the render thread.</p>
 *
 * @see CgTextRenderer
 * @see CgTextScaleResolver
 */
public class CgTextRenderContext {

    private final FloatBuffer projectionBuffer;
    private final CgTextScaleResolver scaleResolver;
    private final Map<CgFontKey, Integer> previousEffectiveTargetPx = new HashMap<CgFontKey, Integer>();
    private final Map<CgFontKey, Boolean> previousMsdf = new HashMap<CgFontKey, Boolean>();

    /**
     * Creates a render context with the given projection matrix and scale resolver.
     *
     * @param projection    the 4×4 projection matrix in column-major order
     * @param scaleResolver the strategy for resolving effective physical glyph size
     *                      from pose transforms
     */
    public CgTextRenderContext(FloatBuffer projection, CgTextScaleResolver scaleResolver) {
        if (projection == null) {
            throw new IllegalArgumentException("projection must not be null");
        }
        if (scaleResolver == null) {
            throw new IllegalArgumentException("scaleResolver must not be null");
        }
        this.projectionBuffer = projection;
        this.scaleResolver = scaleResolver;
    }

    /**
     * Creates a render context with the given projection matrix and the default
     * orthographic scale resolver.
     *
     * <p>This is the common case for 2D UI text rendering.</p>
     *
     * @param projection the 4×4 projection matrix in column-major order
     */
    public CgTextRenderContext(FloatBuffer projection) {
        this(projection, CgTextScaleResolver.ORTHOGRAPHIC);
    }

    /**
     * Creates an orthographic render context for screen-aligned 2D text.
     *
     * <p>Builds the standard top-left-origin orthographic projection:
     * left=0, right=width, top=0, bottom=height, near=-1, far=1.</p>
     *
     * @param width  viewport width in pixels
     * @param height viewport height in pixels
     * @return a new render context ready for 2D text rendering
     */
    public static CgTextRenderContext orthographic(int width, int height) {
        FloatBuffer buf = BufferUtils.createFloatBuffer(16);
        populateOrthoMatrix(buf, width, height);
        return new CgTextRenderContext(buf, CgTextScaleResolver.ORTHOGRAPHIC);
    }

    /**
     * Updates the projection matrix for a viewport resize.
     *
     * <p>Re-populates the existing buffer with a new orthographic projection.
     * This avoids allocating a new {@code FloatBuffer} on every resize.</p>
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void updateOrtho(int width, int height) {
        populateOrthoMatrix(projectionBuffer, width, height);
    }

    /**
     * Returns the current projection matrix buffer.
     *
     * <p>The buffer is positioned at 0 with 16 floats remaining. Callers
     * must not modify the buffer's position or content.</p>
     *
     * @return the projection matrix buffer (column-major, 16 floats)
     */
    public FloatBuffer getProjectionBuffer() {
        return projectionBuffer;
    }

    /**
     * Returns the scale resolver used by this context.
     *
     * @return the scale resolver strategy
     */
    public CgTextScaleResolver getScaleResolver() {
        return scaleResolver;
    }

    int getPreviousEffectiveTargetPx(CgFontKey fontKey) {
        Integer previous = previousEffectiveTargetPx.get(fontKey);
        return previous != null ? previous.intValue() : -1;
    }

    void setPreviousEffectiveTargetPx(CgFontKey fontKey, int effectiveTargetPx) {
        previousEffectiveTargetPx.put(fontKey, effectiveTargetPx);
    }

    boolean wasMsdf(CgFontKey fontKey) {
        Boolean previous = previousMsdf.get(fontKey);
        return previous != null && previous.booleanValue();
    }

    void setWasMsdf(CgFontKey fontKey, boolean msdf) {
        previousMsdf.put(fontKey, msdf);
    }

    /**
     * Clears per-font draw-history state used only for raster hysteresis.
     *
     * <p>This should be used when the caller wants draw-local behavior instead of
     * allowing effective-size or backend decisions from one text run to influence
     * the next run rendered through the same context.</p>
     */
    public void clearHistory() {
        previousEffectiveTargetPx.clear();
        previousMsdf.clear();
    }

    boolean isScaledUiRaster(CgFontKey fontKey, int effectiveTargetPx) {
        return !isWorldText() && effectiveTargetPx != fontKey.getTargetPx();
    }

    /**
     * Returns whether this context is configured for world-space text.
     * The base implementation returns {@code false}; {@link CgWorldTextRenderContext}
     * overrides to return {@code true}.
     */
    public boolean isWorldText() {
        return false;
    }

    /**
     * Populates a FloatBuffer with a standard orthographic projection matrix.
     *
     * <p>Convention: top-left origin (top=0, bottom=height), near=-1, far=1.
     * Column-major order for direct upload via {@code glUniformMatrix4fv}.</p>
     */
    static void populateOrthoMatrix(FloatBuffer buffer, int width, int height) {
        buffer.clear();
        float left = 0.0f;
        float right = width;
        float bottom = height;
        float top = 0.0f;
        float near = -1.0f;
        float far = 1.0f;

        float sx = 2.0f / (right - left);
        float sy = 2.0f / (top - bottom);
        float sz = -2.0f / (far - near);
        float tx = -(right + left) / (right - left);
        float ty = -(top + bottom) / (top - bottom);
        float tz = -(far + near) / (far - near);

        buffer.put(sx).put(0.0f).put(0.0f).put(0.0f);  // col 0
        buffer.put(0.0f).put(sy).put(0.0f).put(0.0f);  // col 1
        buffer.put(0.0f).put(0.0f).put(sz).put(0.0f);  // col 2
        buffer.put(tx).put(ty).put(tz).put(1.0f);       // col 3
        buffer.flip();
    }
}
