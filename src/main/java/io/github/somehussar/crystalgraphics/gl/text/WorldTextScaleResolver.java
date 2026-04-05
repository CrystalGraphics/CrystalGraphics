package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.PoseStack;

/**
 * World-space/3D implementation of {@link CgTextScaleResolver}.
 *
 * <p>Unlike {@link OrthographicScaleResolver}, which derives effective size from
 * the PoseStack's cumulative UI scale factor, this resolver returns a fixed MSDF
 * raster tier based on the base target pixel size alone. World-space text does
 * not use PoseStack scale as a UI zoom signal — the PoseStack in 3D represents
 * model-view positioning (entity rotation, billboard transforms), not UI scale.</p>
 *
 * <h3>Quality/LOD via Projected Size</h3>
 * <p>On-screen quality in world text depends on projected pixel coverage, which
 * requires information beyond the PoseStack (projection matrix, viewport, camera
 * distance). This resolver therefore holds an optional projected-size hint set
 * by the caller via {@link #setProjectedSizeHint(float)}. When available, the
 * hint selects a higher-quality MSDF atlas tier; when absent, the base size
 * provides a reasonable default MSDF tier.</p>
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
 */
final class WorldTextScaleResolver implements CgTextScaleResolver {

    /**
     * Default MSDF raster tier multiplier when no projected-size hint is available.
     * World text uses 2x the base size to ensure a reasonable MSDF atlas quality
     * for typical viewing distances.
     */
    static final int DEFAULT_RASTER_MULTIPLIER = 2;

    /**
     * Projected-size hint in screen pixels, set by the caller before each draw.
     * When <= 0, the resolver falls back to the default raster tier.
     */
    private float projectedSizeHint = -1.0f;

    @Override
    public int resolveEffectiveTargetPx(int baseTargetPx,
                                        PoseStack.Pose pose,
                                        int previousEffectiveTargetPx) {
        // In world-text mode, PoseStack scale is model-view positioning,
        // not UI zoom. Effective raster tier is driven by projected-size
        // hint or a default multiplier — never by pose matrix extraction.
        int effectivePx;
        if (projectedSizeHint > 0.0f) {
            // Use projected size for MSDF quality tier selection.
            // Clamp to reasonable range and quantize.
            effectivePx = Math.round(projectedSizeHint);
        } else {
            effectivePx = baseTargetPx * DEFAULT_RASTER_MULTIPLIER;
        }

        effectivePx = Math.max(MIN_EFFECTIVE_PX, Math.min(MAX_EFFECTIVE_PX, effectivePx));

        // Apply hysteresis if a previous value exists
        if (previousEffectiveTargetPx > 0) {
            float raw = projectedSizeHint > 0.0f ? projectedSizeHint
                    : (float) (baseTargetPx * DEFAULT_RASTER_MULTIPLIER);
            if (effectivePx > previousEffectiveTargetPx
                    && raw < previousEffectiveTargetPx + HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
            if (effectivePx < previousEffectiveTargetPx
                    && raw > previousEffectiveTargetPx - HYSTERESIS_BAND) {
                return previousEffectiveTargetPx;
            }
        }

        return effectivePx;
    }

    /**
     * Always returns {@code true}. World text is always MSDF.
     */
    @Override
    public boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf) {
        return true;
    }

    /**
     * Sets the projected on-screen size hint in screen pixels.
     *
     * <p>This is typically computed by {@link ProjectedSizeEstimator} from the
     * MVP matrix, viewport dimensions, and the logical text size. It affects
     * only the MSDF quality/LOD tier — never layout metrics.</p>
     *
     * @param projectedPx estimated on-screen pixel coverage, or {@code <= 0}
     *                    to use the default raster tier
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
     * Clears the projected-size hint, reverting to default raster tier.
     */
    void clearProjectedSizeHint() {
        this.projectedSizeHint = -1.0f;
    }
}
