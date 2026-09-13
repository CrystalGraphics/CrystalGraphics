package com.crystalgraphics.text.render.context;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.text.render.CgTextRenderer;
import org.joml.Matrix4f;
/**
 * Strategy interface for resolving the effective physical glyph raster size
 * from a {@link PoseStack} transform and a base target pixel size.
 *
 * <h3>Three-Space Model: Logical → Physical Raster Mapping</h3>
 * <p>This resolver bridges the logical layout space and the physical raster
 * space. It converts a base target pixel size (logical identity) and a
 * draw-time PoseStack transform into an effective physical raster size that
 * determines which atlas bucket serves glyphs. The resolver never modifies
 * logical layout metrics — it only selects the physical rasterization tier.</p>
 * <ol>
 *   <li><strong>Logical layout pixels</strong> — the coordinate space used by
 *       {@code CgTextLayout} for width, height, line breaking, and glyph advances.
 *       These never change based on draw-time transforms.</li>
 *   <li><strong>Base target pixels</strong> ({@code CgFontKey.targetPx}) — the font
 *       size requested at font load time. Determines the base rasterization size
 *       and is part of the font/layout cache identity.</li>
 *   <li><strong>Effective target pixels</strong> — the actual raster size used for
 *       glyph rendering at draw time, derived from {@code baseTargetPx × poseScale}.
 *       This is resolved by a {@code CgTextScaleResolver} and determines which
 *       atlas/cache bucket serves the glyphs.</li>
 * </ol>
 *
 * <h3>Orthographic vs. World-Space</h3>
 * <p>For orthographic/UI text, the effective size is derived from the cumulative
 * scale in the pose matrix using {@code max(|sx|, |sy|)} (browser-aligned
 * conservative rule). For world-space/3D text, {@link PerspectiveScaleResolver}
 * uses a fixed multiplier of the base size (or a caller-supplied projected-size
 * hint from {@link ProjectedSizeEstimator}) since the PoseStack encodes model-view
 * positioning rather than UI zoom.</p>
 *
 * <h3>Implementation Contract</h3>
 * <p>Implementations must:</p>
 * <ul>
 *   <li>Return an integer effective target pixel size (quantized)</li>
 *   <li>Clamp to the range [{@link #MIN_EFFECTIVE_PX}, {@link #MAX_EFFECTIVE_PX}]</li>
 *   <li>Use the provided previous effective size to apply hysteresis without
 *       storing global/shared mutable state</li>
 *   <li>Be deterministic for the same input sequence</li>
 * </ul>
 *
 * @see CgTextRenderContext
 * @see CgTextRenderer
 */
public interface CgTextScaleResolver {

    /** Minimum effective raster pixel size. */
    int MIN_EFFECTIVE_PX = 1;

    /** Maximum effective raster pixel size. */
    int MAX_EFFECTIVE_PX = 256;

    /**
     * Effective size threshold: enter MSDF rendering at or above this stabilized size.
     * Combined with {@link #MSDF_EXIT_THRESHOLD}, this creates a hysteresis band
     * around the bitmap/MSDF boundary.
     *
     * <p><b>33 is inherited from a pairing that no longer exists, and it is NOT
     * {@code CgMsdfAtlasConfig.minAntialiasablePx()}.</b> The two answer different questions and are
     * easy to conflate: that one is the size below which the field cannot resolve its own edge at
     * all, this one is where the field starts looking BETTER than a bitmap rasterised at the target
     * size. They happened to coincide when the range was 6, whose floor is 32 — which is where
     * 33/31 came from. The range is 12 and 24 now, with floors of 15 and 7, so the two have come
     * apart by a factor of nearly five.
     *
     * <p>Deliberately not lowered to follow them. Below here a bitmap is rasterised AT the target
     * size while the field is one 80px raster minified, and which of those looks better at 14px is a
     * question about sharpness rather than about capability — it wants a measurement against a
     * reference raster, and an atlas-bytes count, since the bitmap tier stores one entry per
     * (glyph, size, sub-pixel bucket) where the field stores one per glyph. If it ever moves it
     * should become per BAND like the floor it is no longer tied to, since the field's capability is
     * a property of the face now.
     *
     * <p>What must stay true is only that {@link #MSDF_EXIT_THRESHOLD} never falls below a band's
     * floor: the engine would then hold the field tier at a size the field cannot antialias, which
     * degrades silently. @see CgFontBandingTest
     */
    int MSDF_ENTER_THRESHOLD = 33;

    /**
     * Effective size threshold: return to bitmap rendering at or below this stabilized size.
     * Combined with {@link #MSDF_ENTER_THRESHOLD}, this creates a hysteresis band
     * that prevents oscillation at the boundary.
     */
    int MSDF_EXIT_THRESHOLD = 31;

    /**
     * Hysteresis deadband for effective size quantization.
     * Switching from size N to N+1 requires {@code rawEffective >= N + HYSTERESIS_BAND},
     * and switching from N to N-1 requires {@code rawEffective <= N - HYSTERESIS_BAND}.
     */
    float HYSTERESIS_BAND = 0.75f;

    /**
     * Resolves the effective physical target pixel size for glyph rasterization.
     *
     * @param baseTargetPx the base target pixel size from {@code CgFontKey.targetPx}
     * @param pose         the current top-of-stack pose, providing cumulative transforms
     * @param previousEffectiveTargetPx the previously stabilized effective size for
     *                                  this font/context pair, or {@code -1} if none
     * @return the stabilized integer effective target pixel size, clamped to
     *         [{@link #MIN_EFFECTIVE_PX}, {@link #MAX_EFFECTIVE_PX}]
     */
    int resolveEffectiveTargetPx(int baseTargetPx,
                                 PoseStack.Pose pose,
                                 int previousEffectiveTargetPx);

    /**
     * Determines whether MSDF rendering should be used for the given stabilized
     * effective size, applying backend hysteresis.
     *
     * <p>Enter MSDF at {@code effectiveTargetPx >= MSDF_ENTER_THRESHOLD} (33).
     * Return to bitmap at {@code effectiveTargetPx <= MSDF_EXIT_THRESHOLD} (31).
     * Between 31 and 33, retain the previous backend choice.</p>
     *
     * @param effectiveTargetPx the stabilized effective target pixel size
     * @param previouslyMsdf    whether the previous frame used MSDF for this font
     * @return {@code true} if MSDF should be used
     */
    boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf);

    /**
     * Whether this resolver is configured for world-space text.
     *
     * <p>{@link CgTextRenderContext#isWorldText()} delegates here — the "is this
     * world text" question is fully answered by which resolver strategy is active,
     * so there is no separate world-space context subclass. Default {@code false};
     * {@link PerspectiveScaleResolver} overrides to {@code true}.</p>
     */
    default boolean isWorldText() {
        return false;
    }

    /**
     * Updates the projected-size hint used for raster-tier selection, from the
     * given model-view/projection matrices and viewport dimensions.
     *
     * <p>No-op by default. {@link PerspectiveScaleResolver} overrides this to
     * compute the hint via {@link ProjectedSizeEstimator} and store it for the next
     * {@link #resolveEffectiveTargetPx} call. Resolvers that don't use a
     * projected-size hint (like {@link OrthographicScaleResolver}) simply ignore
     * this call.</p>
     *
     * @param modelView      the model-view matrix positioning the text in world space
     * @param projection     the current projection matrix
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     * @param baseTargetPx   the base font target pixel size
     */
    default void updateProjectedSize(Matrix4f modelView, Matrix4f projection,
                                      int viewportWidth, int viewportHeight, int baseTargetPx) {
    }

    /**
     * Clears any projected-size hint set via {@link #updateProjectedSize}, reverting
     * to this resolver's default tier-selection behavior. No-op by default.
     */
    default void clearProjectedSizeHint() {
    }

    // ── Shipped implementations ─────────────────────────────────────────

    /**
     * Default orthographic/UI resolver.
     *
     * <p>Derives effective size from cumulative pose scale using
     * {@code baseTargetPx * max(|sx|, |sy|)} with quantization and hysteresis.
     * This is the correct resolver for all 2D screen-space text.</p>
     *
     * <p>World-space/perspective text uses {@link PerspectiveScaleResolver}, which
     * ignores PoseStack scale and instead uses a fixed raster multiplier or a
     * caller-supplied projected-size hint from {@link ProjectedSizeEstimator}.</p>
     */
    CgTextScaleResolver ORTHOGRAPHIC = new OrthographicScaleResolver();
}
