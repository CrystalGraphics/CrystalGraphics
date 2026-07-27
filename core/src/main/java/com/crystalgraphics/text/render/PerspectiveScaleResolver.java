package com.crystalgraphics.text.render;

import com.crystalgraphics.api.PoseStack;
import org.joml.Matrix4f;

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
 * <h3>Fixed raster tier — the projected-size hint deliberately does NOT drive it</h3>
 * <p>{@link #resolveEffectiveTargetPx} always returns a fixed
 * {@link #DEFAULT_RASTER_MULTIPLIER}x multiple of the base target size, independent of the
 * camera. The projected-size hint is still recorded by {@link #updateProjectedSize} (and
 * readable via {@link #getProjectedSizeHint()}) but is intentionally not consulted here.
 *
 * <p>This class used to feed the hint straight into the raster tier, on the stated rationale
 * that "distant text uses a smaller atlas tier while close-up text uses a larger tier."
 * That rationale does not survive contact with how MSDF atlases are actually keyed:
 * {@code CgFontRegistry#toMsdfAtlasGlyphKey} rewrites the font key's target size to
 * {@code CgMsdfAtlasConfig#atlasScalePx()} and forces the sub-pixel bucket to 0, so
 * <strong>the MSDF atlas ignores {@code effectiveTargetPx} entirely</strong>. Distance-field
 * glyphs are resolution-independent by construction; there is no per-distance MSDF tier for
 * the hint to select. Empirically confirmed by an atlas dump: one MSDF family, seven bitmap
 * families, same 735 glyphs in each.</p>
 *
 * <p>So for world text the hint only ever reached three things, all of them warmup-only
 * artifacts, and all of them harmful when it moves:</p>
 * <ol>
 *   <li><strong>The bitmap fallback atlas size.</strong> {@code CgFontRegistry} keys bitmap
 *       atlas families by {@code CgRasterFontKey(fontKey, effectiveTargetPx)}, so a
 *       continuously camera-varying value mints a whole new atlas family — and re-rasterizes
 *       every glyph into it — each time it lands on a new integer. A single camera dolly
 *       produced 7 families / 333 pages of 1024x1024 (~333 MB) holding the same 735 glyphs,
 *       versus 24 pages for the one MSDF family that actually gets rendered.</li>
 *   <li><strong>{@code CgGlyphPlacementCache} staleness.</strong> {@code Entry#matches}
 *       requires an exact {@code effectiveTargetPx} match whenever {@code distanceField} is
 *       false — i.e. throughout MSDF warmup — so a drifting value turns every frame into a
 *       full re-resolve of the entire layout (measured: 250-850 ms/frame for ~1750 glyphs).</li>
 *   <li>{@code CgResolvedGlyphs#logicalMetricScale} for bitmap-tier glyphs, which stays
 *       self-consistent precisely because the value is now fixed.</li>
 * </ol>
 *
 * <p>Pinning the tier costs nothing visually: the bitmap tier is a transient placeholder shown
 * for a couple of seconds until each glyph's real MSDF result lands, after which world text is
 * 100% distance-field forever (see {@link #shouldUseMsdf}). It buys exactly one bitmap atlas
 * family per base font size — the minimum possible — at a small raster size that packs
 * hundreds of glyphs per page instead of ~11.</p>
 *
 * <p>Reintroduce hint-driven tiering only if MSDF atlases ever become genuinely size-tiered;
 * until then it is pure cost. Note the hysteresis/deadband machinery this class used to need
 * is likewise gone: a constant cannot churn.</p>
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
 * @see CgTextRenderContext#updateProjectedSize
 */
final class PerspectiveScaleResolver implements CgTextScaleResolver {

    /**
     * The world-text raster tier, as a multiple of the base target size. Applied
     * unconditionally — see the class javadoc for why this is fixed rather than derived from
     * the projected-size hint. 2x keeps the transient bitmap-fallback placeholder legible
     * without inflating atlas cell size (a 24px font rasterizes at 48px, packing hundreds of
     * glyphs per 1024x1024 page instead of ~11 at camera-derived sizes in the 200-256px range).
     */
    static final int DEFAULT_RASTER_MULTIPLIER = 2;

    /**
     * Most recent projected on-screen size in pixels from {@link #updateProjectedSize}, or
     * {@code <= 0} if never set. Recorded for diagnostics only — deliberately NOT consulted by
     * {@link #resolveEffectiveTargetPx}; see the class javadoc.
     */
    private float projectedSizeHint = -1.0f;

    /**
     * Returns a fixed {@link #DEFAULT_RASTER_MULTIPLIER}x tier, ignoring both the camera-derived
     * projected-size hint and {@code previousEffectiveTargetPx}.
     *
     * <p>Constant per base font size by design — see the class javadoc. Because the result never
     * varies frame to frame, the quantization deadband/hysteresis this method used to apply is
     * unnecessary (there is nothing left to churn), and {@code previousEffectiveTargetPx} is
     * unused. {@code pose} is likewise irrelevant: a world-space PoseStack encodes model-view
     * placement, not UI zoom (see the class javadoc's "Layout Invariance").</p>
     */
    @Override
    public int resolveEffectiveTargetPx(int baseTargetPx,
                                        PoseStack.Pose pose,
                                        int previousEffectiveTargetPx) {
        int tier = baseTargetPx * DEFAULT_RASTER_MULTIPLIER;
        return Math.max(MIN_EFFECTIVE_PX, Math.min(MAX_EFFECTIVE_PX, tier));
    }

    /**
     * Always returns {@code true}. World text is always MSDF.
     */
    @Override
    public boolean shouldUseMsdf(int effectiveTargetPx, boolean previouslyMsdf) {
        return true;
    }

    /**
     * Always returns {@code true} — this resolver is only ever used for world-space
     * text (see {@link CgTextRenderContext#world}).
     */
    @Override
    public boolean isWorldText() {
        return true;
    }

    /**
     * Records the projected on-screen size via {@link ProjectedSizeEstimator}.
     *
     * <p><strong>Does not affect raster tier selection</strong> — {@link #resolveEffectiveTargetPx}
     * deliberately ignores this value (see the class javadoc). Kept because it is the natural
     * place to compute the measurement, it is a genuinely useful diagnostic, and it is what a
     * future genuinely-size-tiered distance-field atlas would consult. Callers may keep calling
     * it once per frame; doing so is cheap and has no effect on atlas identity or caching.</p>
     */
    @Override
    public void updateProjectedSize(Matrix4f modelView, Matrix4f projection,
                                     int viewportWidth, int viewportHeight, int baseTargetPx) {
        this.projectedSizeHint = ProjectedSizeEstimator.estimateScreenPx(
                modelView, projection, viewportWidth, viewportHeight, baseTargetPx);
    }

    /**
     * Returns the most recently recorded projected size — diagnostics only; it does not
     * influence {@link #resolveEffectiveTargetPx}.
     *
     * @return the projected size in screen pixels, or {@code <= 0} if unset
     */
    float getProjectedSizeHint() {
        return projectedSizeHint;
    }

    /**
     * Clears the recorded projected size. Has no effect on the raster tier, which is fixed
     * regardless (see {@link #resolveEffectiveTargetPx}).
     */
    @Override
    public void clearProjectedSizeHint() {
        this.projectedSizeHint = -1.0f;
    }
}
