package com.crystalgraphics.text.render;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.font.CgFontKey;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the projection and model-view state needed by the text renderer,
 * replacing the per-draw raw projection buffer parameter.
 *
 * <p>A render context holds three concerns:</p>
 * <ol>
 *   <li><strong>Projection matrix</strong> — the orthographic (2D UI) or perspective
 *       (3D world) projection that maps world/screen coordinates to clip space. The
 *       context owns this matrix and callers set it once (or update it on resize),
 *       rather than passing a raw matrix payload to every {@code draw()} call.</li>
 *   <li><strong>Scale resolver</strong> — the strategy for deriving effective physical
 *       glyph size from a {@link PoseStack} transform. For orthographic/UI rendering,
 *       this uses the cumulative scale from the top pose matrix. For world-space
 *       text, {@link PerspectiveScaleResolver} uses a fixed raster multiplier or a
 *       projected-size hint instead of pose scale.</li>
 *   <li><strong>Viewport dimensions</strong> — used by world-space text's
 *       {@link #updateProjectedSize} to estimate on-screen glyph coverage; unused
 *       (and harmless) for 2D UI text.</li>
 * </ol>
 *
 * <h3>One class, not a world-space subclass</h3>
 * <p>There is no separate world-space context class. Whether this context is
 * "world text" is fully answered by which {@link CgTextScaleResolver} strategy is
 * active — {@link #isWorldText()}/{@link #updateProjectedSize}/
 * {@link #clearProjectedSizeHint()} all delegate to {@link #scaleResolver}, which
 * already has to know the difference (an {@link OrthographicScaleResolver} derives
 * scale from pose transforms; a {@link PerspectiveScaleResolver} does not). A
 * parallel subclass duplicating that distinction added a maintenance seam without
 * adding a real capability — see {@link #world(Matrix4f, int, int)} for the 3D
 * factory instead of a separate class/constructor pair.</p>
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
     *  read-only and use {@link #updateOrtho}/{@link #updateProjection}
     *  to change it.</p>
     *
     * @return the projection matrix
     */
    @Getter @Setter
    @Accessors(fluent = true, chain = true)
    protected Matrix4f projection;
    /**
     * -- GETTER --
     *  Returns the scale resolver used by this context.
     *
     * @return the scale resolver strategy
     */
    @Getter
    private final CgTextScaleResolver scaleResolver;
    private final Map<CgFontKey, RasterHistory> history = new HashMap<>();

    /** Viewport dimensions — only meaningful for world-space text; harmless if unused. */
    @Getter
    private int viewportWidth;
    @Getter
    private int viewportHeight;

    /**
     * Per-font draw-history state used only for raster-tier hysteresis — the
     * previously stabilized effective pixel size and whether that draw used MSDF.
     * These two values are always read and written together per font, so they live
     * in one map entry rather than two parallel maps.
     */ 
    record RasterHistory(int effectiveTargetPx, boolean wasMsdf) {
    }

    /**
     * Creates a render context with the given projection matrix, scale resolver,
     * and viewport dimensions. Private — construct via {@link #orthographic} or
     * {@link #world}.
     *
     * @param projection     the projection matrix
     * @param scaleResolver  the strategy for resolving effective physical glyph size
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    private CgTextRenderContext(Matrix4f projection, CgTextScaleResolver scaleResolver,
                                 int viewportWidth, int viewportHeight) {
        if (projection == null) throw new IllegalArgumentException("projection must not be null");
        if (scaleResolver == null) throw new IllegalArgumentException("scaleResolver must not be null");

        this.projection = new Matrix4f(projection);
        this.scaleResolver = scaleResolver;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
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
        return new CgTextRenderContext(populateOrthographic(new Matrix4f(), width, height),
                CgTextScaleResolver.ORTHOGRAPHIC, width, height);
    }

    /**
     * Creates a world-space render context for 3D text, using a fresh
     * {@link PerspectiveScaleResolver} (always-MSDF, projection-aware raster tier).
     *
     * <p>Unlike {@link #orthographic}, this does not build the projection matrix for
     * you — {@code projection} must be <strong>the scene's actual camera projection
     * matrix</strong>, not a text-specific approximation. FOV and near/far clip
     * planes are scene-camera properties, not text properties, and world text must
     * share the exact same projection as everything else rendered in the same 3D
     * pass. Passing a different (even reasonable-looking) projection here will not
     * fail loudly — it will silently misalign world text with the rest of the scene
     * (wrong apparent depth/size relative to nearby geometry).</p>
     *
     * @param projection     the scene's actual camera projection matrix (typically
     *                       perspective) — reuse it, don't reconstruct it
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @return a new render context ready for world-space text rendering
     */
    public static CgTextRenderContext world(Matrix4f projection, int viewportWidth, int viewportHeight) {
        return new CgTextRenderContext(projection, new PerspectiveScaleResolver(), viewportWidth, viewportHeight);
    }

    /**
     * Updates context resolution with respective update ortho/perspective calls
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public void update(int width, int height) {
        if (isWorldText()) updateProjection(projection, width, height);
        else updateOrtho(width, height);
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
        if (this.viewportWidth == width && this.viewportHeight == height) return;
        
        populateOrthographic(projection, width, height);
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * Updates the projection matrix and viewport dimensions directly — for
     * world-space text, where the projection is typically perspective rather than
     * orthographic and so cannot be rebuilt by {@link #updateOrtho}.
     *
     * <p>Same requirement as {@link #world}: pass the scene's actual current camera
     * projection, not a reconstructed approximation, or world text will silently
     * misalign with the rest of the scene.</p>
     *
     * @param projection     the scene's current camera projection matrix
     * @param width  new viewport width
     * @param height new viewport height
     */
    public void updateProjection(Matrix4f projection, int width, int height) {
        if (this.viewportWidth == width && this.viewportHeight == height && this.projection.equals(projection)) return;

        this.projection.set(projection);
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /** Returns the last-stabilized history for this font, or {@code null} if none yet. */
    RasterHistory getHistory(CgFontKey fontKey) {
        return history.get(fontKey);
    }

    void setHistory(CgFontKey fontKey, int effectiveTargetPx, boolean wasMsdf) {
        history.put(fontKey, new RasterHistory(effectiveTargetPx, wasMsdf));
    }

    /**
     * Clears per-font draw-history state used only for raster hysteresis.
     *
     * <p>This should be used when the caller wants draw-local behavior instead of
     * allowing effective-size or backend decisions from one text run to influence
     * the next run rendered through the same context.</p>
     */
    public void clearHistory() {
        history.clear();
    }

    boolean isScaledUiRaster(CgFontKey fontKey, int effectiveTargetPx) {
        return !isWorldText() && effectiveTargetPx != fontKey.getTargetPx();
    }

    /**
     * Returns whether this context is configured for world-space text.
     *
     * <p>Delegates to {@link CgTextScaleResolver#isWorldText()} — see the class
     * javadoc's "One class, not a world-space subclass" section.</p>
     */
    public boolean isWorldText() {
        return scaleResolver.isWorldText();
    }

    /**
     * Updates the projected-size hint for world-space raster tier selection.
     *
     * <p>Call this before each world-text draw to provide the resolver with
     * an estimate of how large the text appears on screen. Delegates to
     * {@link CgTextScaleResolver#updateProjectedSize} — a no-op for resolvers
     * that don't use a projected-size hint (e.g. 2D UI text).</p>
     *
     * @param modelView    the model-view matrix positioning the text in world space
     * @param projection   the current projection matrix
     * @param baseTargetPx the base font target pixel size
     */
    public void updateProjectedSize(Matrix4f modelView, Matrix4f projection, int baseTargetPx) {
        scaleResolver.updateProjectedSize(modelView, projection, viewportWidth, viewportHeight, baseTargetPx);
    }

    /**
     * Clears the projected-size hint, reverting to the resolver's default
     * tier-selection behavior. Delegates to {@link CgTextScaleResolver#clearProjectedSizeHint()}.
     */
    public void clearProjectedSizeHint() {
        scaleResolver.clearProjectedSizeHint();
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
