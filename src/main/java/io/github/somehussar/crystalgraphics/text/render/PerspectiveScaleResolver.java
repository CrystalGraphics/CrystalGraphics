package io.github.somehussar.crystalgraphics.text.render;

import io.github.somehussar.crystalgraphics.api.PoseStack;

/**
 * World-space/3D implementation of {@link CgTextScaleResolver}.
 *
 * <p>Unlike {@link OrthographicScaleResolver}, which derives effective size from
 * the PoseStack's cumulative UI scale factor, this resolver computes the MSDF
 * raster tier from either a caller-supplied projected-size hint or a fixed
 * multiplier of the base target pixel size. World-space text does not use
 * PoseStack scale as a UI zoom signal — the PoseStack in 3D represents
 * model-view positioning (entity rotation, billboard transforms), not UI scale.</p>
 *
 * <h3>Projected-Size Hint</h3>
 * <p>Callers can supply a projected-size hint via {@link #setProjectedSizeHint(float)}
 * (typically computed by {@link ProjectedSizeEstimator}) to adapt the MSDF atlas
 * raster tier to the text's apparent on-screen size. When set, the hint directly
 * replaces the default multiplier for raster tier selection, ensuring that distant
 * text uses a smaller atlas tier (saving GPU memory and generation work) while
 * close-up text uses a larger tier (preserving crispness). When no hint is set,
 * or after {@link #clearProjectedSizeHint()}, the resolver falls back to a stable
 * {@link #DEFAULT_RASTER_MULTIPLIER}x tier.</p>
 *
 * <h3>Hysteresis</h3>
 * <p>When a projected-size hint is active, the resolver applies the same
 * quantization-deadband hysteresis as {@link OrthographicScaleResolver} to prevent
 * raster tier churn when the camera oscillates near a quantization boundary. The
 * {@code previousEffectiveTargetPx} fed back by the render context provides the
 * stable baseline for the deadband.</p>
 *
 * <h3>Always-MSDF Guarantee</h3>
 * <p>{@link #shouldUseMsdf(int, boolean)} always returns {@code true}. World
 * text never falls back to bitmap rendering.</p>
 *
 * <h3>Layout Invariance</h3>
 * <p>Neither the projected-size hint nor any camera-dependent factor alters
 * shaping, advances, kerning, or line metrics. Those remain in logical space
 * as defined by the base {@code CgFontKey.targetPx}.</p>
 *
 * @see CgTextScaleResolver
 * @see ProjectedSizeEstimator
 * @see CgWorldTextRenderContext#updateProjectedSize
 */
final class PerspectiveScaleResolver implements CgTextScaleResolver {

    /**
     * Default MSDF raster tier multiplier when no projected-size hint is available.
     * World text uses 2x the base size to ensure a reasonable MSDF atlas quality
     * for typical viewing distances.
     */
    static final int DEFAULT_RASTER_MULTIPLIER = 2;

    /**
     * The current projected-size hint in screen pixels, or {@code <= 0} if unset.
     * When positive, this replaces the default raster multiplier for tier selection.
     */
    private float projectedSizeHint = -1.0f;

    @Override
    public int resolveEffectiveTargetPx(int baseTargetPx,
                                        PoseStack.Pose pose,
                                        int previousEffectiveTargetPx) {
        // When a projected-size hint is available, use it directly as the
        // effective raster size instead of the fixed multiplier. This allows
        // the atlas tier to adapt to viewing distance: distant text gets a
        // smaller tier, close-up text gets a larger tier.
        float rawEffective;
        if (projectedSizeHint > 0.0f) {
            rawEffective = projectedSizeHint;
        } else {
            rawEffective = (float) (baseTargetPx * DEFAULT_RASTER_MULTIPLIER);
        }

        int quantized = Math.round(rawEffective);
        quantized = Math.max(MIN_EFFECTIVE_PX, Math.min(MAX_EFFECTIVE_PX, quantized));

        // Apply hysteresis from the caller-provided previous value to prevent
        // raster tier churn when the camera oscillates near a quantization boundary.
        if (previousEffectiveTargetPx > 0) {
            if (quantized > previousEffectiveTargetPx
                    && rawEffective < previousEffectiveTargetPx + HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
            if (quantized < previousEffectiveTargetPx
                    && rawEffective > previousEffectiveTargetPx - HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
        }

        return quantized;
    }

    /**
     * Always returns {@code true}. World text is always MSDF.
     */
    @Override
    public boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf) {
        return true;
    }

    /**
     * Sets the projected-size hint for MSDF raster tier selection.
     *
     * <p>When set to a positive value, {@link #resolveEffectiveTargetPx} uses this
     * as the raw effective pixel size instead of applying the default multiplier.
     * Call this once per frame before drawing world text to adapt the raster tier
     * to the text's apparent on-screen size.</p>
     *
     * @param projectedPx estimated screen pixel coverage, or {@code <= 0} to clear
     */
    void setProjectedSizeHint(float projectedPx) {
        this.projectedSizeHint = projectedPx;
    }

    /**
     * Returns the current projected-size hint.
     *
     * @return the projected size in screen pixels, or {@code <= 0} if unset
     */
    float getProjectedSizeHint() {
        return projectedSizeHint;
    }

    /**
     * Clears the projected-size hint, reverting to the default
     * {@link #DEFAULT_RASTER_MULTIPLIER}x raster tier.
     */
    void clearProjectedSizeHint() {
        this.projectedSizeHint = -1.0f;
    }
}
