package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.PoseStack;
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
