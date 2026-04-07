package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.PoseStack;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import lombok.Getter;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the projection and model-view state needed by the text renderer,
 * replacing the per-draw raw projection buffer parameter.
 *
 * <p>A render context holds two concerns:</p>
 * <ol>
 *   <li><strong>Projection matrix</strong> — the orthographic (or future perspective)
 *       projection that maps world/screen coordinates to clip space. For 2D UI text,
 *       this is the standard orthographic matrix. The context owns this matrix and
 *       callers set it once (or update it on resize), rather than passing a raw
 *       matrix payload to every {@code draw()} call.</li>
 *   <li><strong>Scale resolver</strong> — the strategy for deriving effective physical
 *       glyph size from a {@link PoseStack} transform. For orthographic/UI rendering,
 *       this uses the cumulative scale from the top pose matrix. For world-space
 *       text, {@link PerspectiveScaleResolver} uses a fixed raster multiplier or a
 *       projected-size hint instead of pose scale.</li>
 * </ol>
 *
 * <h3>Design Rationale</h3>
 * <p>Previous API required every {@code CgTextRenderer.draw()} call to pass a
 * projection matrix payload. This is ergonomically poor: the projection
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

    /**
     * -- GETTER --
     *  Returns the current projection matrix.
     *  <p>The returned matrix is owned by this context. Callers should treat it as
     *  read-only and use 
     *  or subclass-specific update
     *  methods to change it.</p>
     *
     * @return the projection matrix
     */
    @Getter
    protected final Matrix4f projection;
    private final CgTextScaleResolver scaleResolver;
    private final Map<CgFontKey, Integer> previousEffectiveTargetPx = new HashMap<>();
    private final Map<CgFontKey, Boolean> previousMsdf = new HashMap<>();

    /**
     * Creates a render context with the given projection matrix and scale resolver.
     *
     * @param projection    the projection matrix
     * @param scaleResolver the strategy for resolving effective physical glyph size
     *                      from pose transforms
     */
    public CgTextRenderContext(Matrix4f projection, CgTextScaleResolver scaleResolver) {
        if (projection == null) throw new IllegalArgumentException("projection must not be null");
        if (scaleResolver == null) throw new IllegalArgumentException("scaleResolver must not be null");
        
        this.projection = new Matrix4f(projection);
        this.scaleResolver = scaleResolver;
    }

    /**
     * Creates a render context with the given projection matrix and the default
     * orthographic scale resolver.
     *
     * <p>This is the common case for 2D UI text rendering.</p>
     *
     * @param projection the projection matrix
     */
    public CgTextRenderContext(Matrix4f projection) {
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
        return new CgTextRenderContext(populateOrthographic(new Matrix4f(), width, height), CgTextScaleResolver.ORTHOGRAPHIC);
    }

    /**
     * Updates the projection matrix for a viewport resize.
     *
     * <p>Re-populates the existing matrix with a new orthographic projection.
     * This avoids allocating a new matrix on every resize.</p>
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void updateOrtho(int width, int height) {
        populateOrthographic(projection, width, height);
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
     * Populates a matrix with a standard orthographic projection.
     *
     * <p>Convention: top-left origin (top=0, bottom=height), near=-1, far=1.
     * Delegates to JOML's orthographic matrix construction so the renderer no longer
     * maintains a hand-written matrix layout here.</p>
     */
    static Matrix4f populateOrthographic(Matrix4f matrix, int width, int height) {
        return matrix.setOrtho(0.0f, width, height, 0.0f, -1.0f, 1.0f);
    }
}
