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
 * <h3>Stable World Raster Tier</h3>
 * <p>World-space text now uses a stable distance-field raster tier derived from
 * the base font size, not from per-frame projected-size estimates. In practice,
 * pose-dependent tier churn caused the same glyph to be reconstructed from
 * different MTSDF atlases at different camera positions, which made dense
 * intersections appear inconsistent between otherwise similar captures.</p>
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

    @Override
    public int resolveEffectiveTargetPx(int baseTargetPx,
                                        PoseStack.Pose pose,
                                        int previousEffectiveTargetPx) {
        int effectivePx = baseTargetPx * DEFAULT_RASTER_MULTIPLIER;
        effectivePx = Math.max(MIN_EFFECTIVE_PX, Math.min(MAX_EFFECTIVE_PX, effectivePx));
        return effectivePx;
    }

    /**
     * Always returns {@code true}. World text is always MSDF.
     */
    @Override
    public boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf) {
        return true;
    }

    void setProjectedSizeHint(float projectedPx) {
    }

    /**
     * Returns the current projected-size hint.
     *
     * @return the projected size in screen pixels, or {@code <= 0} if unset
     */
    float getProjectedSizeHint() {
        return -1.0f;
    }

    /**
     * Clears the projected-size hint, reverting to default raster tier.
     */
    void clearProjectedSizeHint() {
    }
}
