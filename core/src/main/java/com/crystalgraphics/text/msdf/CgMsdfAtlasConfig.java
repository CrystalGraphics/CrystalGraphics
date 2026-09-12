package com.crystalgraphics.text.msdf;

import com.crystalgraphics.msdfgen.MSDFConstants;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;

/**
 * Immutable configuration for a shared MSDF atlas family.
 *
 * <p>This separates atlas-generation concerns from requested render size.
 * A single MSDF atlas family is generated at one fixed atlas scale and can
 * then be reused across many output sizes by the runtime shader.</p>
 */
public record CgMsdfAtlasConfig(int atlasScalePx, float pxRange, int pageSize, int spacingPx, float miterLimit,
                                boolean alignOriginX, boolean alignOriginY, boolean overlapSupport,
                                int errorCorrectionMode, int distanceCheckMode, double minDeviationRatio,
                                double minImproveRatio, CgMsdfEdgeColoringMode edgeColoringMode,
                                double edgeColoringAngleThreshold, boolean mtsdf) {

    /**
     * Atlas generation scale in px/em for every distance-field atlas.
     *
     * <p><strong>Do not lower this to 64 for CJK — it was tried and visibly fails.</strong>
     * 64 (with {@link #DEFAULT_PX_RANGE} left at 6) produced obvious reconstruction artifacts on
     * dense kanji when rendered, confirmed visually against an 80px dump of the same font
     * (M+ 1p). The arithmetic that suggested it would be fine — dense kanji strokes are around
     * 1/40 em, so ~2.0px at 80 and ~1.6px at 64, both nominally above the ~1 texel MSDF floor —
     * is not sufficient in practice. Treat 80 as the empirically established floor for dense CJK
     * at this pxRange.
     *
     * <p>A second attempt at 64 with the range corrected to a scale-proportional 4.8 texels
     * (6.25% of em — <em>tighter</em> than 80 runs at) improved the worst glyphs, confirming
     * range interference was one real cause, but artifacts persisted on others. That residue is
     * a genuine resolution floor rather than a range problem: at 64 one texel is 1.56% of em and
     * the densest kanji strokes sit near 1/50 em, i.e. ~1.28 texels — below what median-of-3
     * reconstruction holds at <em>any</em> range. At 80 the same stroke is ~1.6 texels, which
     * survives. Two independent failure modes, one fixed, one fundamental.
     *
     * <p>Consequence for memory: a smaller cell is simply not available for dense CJK. The win
     * has to come from not imposing CJK's requirement on every font — Latin stems are far
     * thicker relative to em and have no comparable floor — which means deriving this per font
     * from glyph complexity (p95 of {@code MSDFShape.getEdgeCount()}) rather than one global
     * constant.
     */
    public static final int DEFAULT_ATLAS_SCALE_PX = 80;

    /**
     * Distance range in <strong>atlas texels</strong>, so its effect scales inversely with
     * {@link #DEFAULT_ATLAS_SCALE_PX} — the two must always be considered together.
     *
     * <p><strong>12 since the stroke reach was measured.</strong> The range is what a text stroke
     * has to fit inside: an outline can only be as wide as the field carries real distance, which is
     * {@code (pxRange - 1) / 2} texels less one for the bilinear footprint. At 6 that ceiling was
     * 1.5 of 80 texels, 0.019 em -- 1.2px on 64px text, so an authored 2px outline and an authored
     * 8px one drew identically. At 12 it is 4.5 texels, 0.056 em, 3.6px on 64px text.
     *
     * <p>The bill is cell AREA, paid by every glyph whether or not anything strokes it: a {@code g}
     * goes 43x71 to 52x83 texels, 1.41x, so a page holds proportionally fewer glyphs and each costs
     * proportionally more to generate against the per-frame budget.
     *
     * <p>Interference was the risk and it was measured, not assumed: across nine dense kanji in M+ 1p
     * -- the font the 64px regressions were confirmed on -- ranges up to 16 close no counter and move
     * ink by at most 2.7% against a 240px reference, which is the same 2.7% pxRange 6 shows, i.e. the
     * 80px raster rather than the range. @see CgMsdfRangeInterferenceTest
     *
     * <p>msdf-atlas-gen's own default is 2, so this remains generous either way.
     *
     * <p>Range interference is nonetheless a real, visible failure mode and the reason this knob
     * cannot be raised freely: when the range exceeds the gap between two edges their fields
     * overlap, the median reconstruction picks wrong values between them, and the gap fills in —
     * bloated strokes, shrunken counters. That is what the 64px experiments demonstrated. The
     * opposing pressure is antialiasing headroom: pxRange sets how many screen pixels the edge
     * transition spans, so too small eventually reads as hard, aliased edges on very large text.
     * Dense scripts push this down, large on-screen text pushes it up.
     *
     * <p><strong>Any change here invalidates the atlas scale.</strong> The two constants were
     * validated as a pair; moving one without re-checking the other against dense CJK is how the
     * 64px regressions happened.
     */
    public static final float DEFAULT_PX_RANGE = 12f;
    public static final int DEFAULT_PAGE_SIZE = 1024;
    public static final int DEFAULT_SPACING_PX = 1;
    public static final float DEFAULT_MITER_LIMIT = 2.0f;
    public static final boolean DEFAULT_ALIGN_ORIGIN_X = false;
    public static final boolean DEFAULT_ALIGN_ORIGIN_Y = true;
    public static final boolean DEFAULT_OVERLAP_SUPPORT = true;
    public static final int DEFAULT_ERROR_CORRECTION_MODE = MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY;
    public static final int DEFAULT_DISTANCE_CHECK_MODE = MSDFConstants.DISTANCE_CHECK_AT_EDGE;
    public static final double DEFAULT_MIN_DEVIATION_RATIO = MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO;
    public static final double DEFAULT_MIN_IMPROVE_RATIO = MSDFConstants.DEFAULT_MIN_IMPROVE_RATIO;
    public static final CgMsdfEdgeColoringMode DEFAULT_EDGE_COLORING_MODE = CgMsdfEdgeColoringMode.INK_TRAP;
    public static final double DEFAULT_EDGE_COLORING_ANGLE_THRESHOLD = 3.0d;
    public static final boolean DEFAULT_MTSDF = true;

    public CgMsdfAtlasConfig(int atlasScalePx,
                             float pxRange,
                             int pageSize,
                             int spacingPx,
                             float miterLimit,
                             boolean alignOriginX,
                             boolean alignOriginY,
                             boolean overlapSupport,
                             int errorCorrectionMode,
                             int distanceCheckMode,
                             double minDeviationRatio,
                             double minImproveRatio,
                             CgMsdfEdgeColoringMode edgeColoringMode,
                             double edgeColoringAngleThreshold) {
        this(atlasScalePx, pxRange, pageSize, spacingPx, miterLimit,
                alignOriginX, alignOriginY, overlapSupport,
                errorCorrectionMode, distanceCheckMode,
                minDeviationRatio, minImproveRatio,
                edgeColoringMode, edgeColoringAngleThreshold,
                DEFAULT_MTSDF);
    }

    public CgMsdfAtlasConfig {
        if (atlasScalePx <= 0) {
            throw new IllegalArgumentException("atlasScalePx must be > 0, got " + atlasScalePx);
        }
        if (pxRange <= 0.0f) {
            throw new IllegalArgumentException("pxRange must be > 0, got " + pxRange);
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be > 0, got " + pageSize);
        }
        if (spacingPx < 0) {
            throw new IllegalArgumentException("spacingPx must be >= 0, got " + spacingPx);
        }
        if (miterLimit < 0.0f) {
            throw new IllegalArgumentException("miterLimit must be >= 0, got " + miterLimit);
        }
        if (minDeviationRatio <= 0.0d) {
            throw new IllegalArgumentException("minDeviationRatio must be > 0, got " + minDeviationRatio);
        }
        if (minImproveRatio <= 0.0d) {
            throw new IllegalArgumentException("minImproveRatio must be > 0, got " + minImproveRatio);
        }
        if (edgeColoringMode == null) {
            throw new IllegalArgumentException("edgeColoringMode must not be null");
        }
        if (edgeColoringAngleThreshold <= 0.0d) {
            throw new IllegalArgumentException(
                    "edgeColoringAngleThreshold must be > 0, got " + edgeColoringAngleThreshold);
        }
    }

    /**
     * The smallest raster size, in px/em, at which this pairing can still antialias.
     *
     * <p>msdfgen states the rule about the quantity its own shader computes: "screenPxRange() must
     * never be lower than 1. If it is lower than 2, there is a high probability that the
     * anti-aliasing will fail." That quantity is {@code storedRange * effectivePx / atlasScalePx},
     * and the field carries {@code pxRange - 1} after the layout reserves its texel — so the rule
     * inverts to a size:</p>
     *
     * <pre>{@code
     * effectivePx >= 2 * atlasScalePx / (pxRange - 1)     // 15px at the shipping 80 / 12
     * }</pre>
     *
     * <p>Which is why a glyph smaller than this is drawn from the bitmap tier: not because a
     * distance field is unavailable, but because below here it cannot resolve its own edge. Anything
     * that wants to reach for the field tier early — a stroke, which the bitmap tier cannot draw at
     * all — stops here.</p>
     */
    public int minAntialiasablePx() {
        return (int) Math.ceil(2.0 * atlasScalePx / Math.max(pxRange - 1.0, 1.0));
    }

    public static CgMsdfAtlasConfig defaultConfig() {
        return new CgMsdfAtlasConfig(
                DEFAULT_ATLAS_SCALE_PX,
                DEFAULT_PX_RANGE,
                DEFAULT_PAGE_SIZE,
                DEFAULT_SPACING_PX,
                DEFAULT_MITER_LIMIT,
                DEFAULT_ALIGN_ORIGIN_X,
                DEFAULT_ALIGN_ORIGIN_Y,
                DEFAULT_OVERLAP_SUPPORT,
                DEFAULT_ERROR_CORRECTION_MODE,
                DEFAULT_DISTANCE_CHECK_MODE,
                DEFAULT_MIN_DEVIATION_RATIO,
                DEFAULT_MIN_IMPROVE_RATIO,
                DEFAULT_EDGE_COLORING_MODE,
                DEFAULT_EDGE_COLORING_ANGLE_THRESHOLD);
    }

    public static CgMsdfAtlasConfig forHarnessParity(int atlasScalePx, Integer forcedPageSize) {
        int resolvedPageSize = forcedPageSize != null
                ? forcedPageSize.intValue()
                : DEFAULT_PAGE_SIZE;
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                DEFAULT_PX_RANGE,
                resolvedPageSize,
                DEFAULT_SPACING_PX,
                DEFAULT_MITER_LIMIT,
                DEFAULT_ALIGN_ORIGIN_X,
                DEFAULT_ALIGN_ORIGIN_Y,
                DEFAULT_OVERLAP_SUPPORT,
                DEFAULT_ERROR_CORRECTION_MODE,
                DEFAULT_DISTANCE_CHECK_MODE,
                DEFAULT_MIN_DEVIATION_RATIO,
                DEFAULT_MIN_IMPROVE_RATIO,
                DEFAULT_EDGE_COLORING_MODE,
                DEFAULT_EDGE_COLORING_ANGLE_THRESHOLD);
    }

    public CgMsdfAtlasConfig withPageSize(int newPageSize) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                newPageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withAtlasScalePx(int newAtlasScalePx) {
        return new CgMsdfAtlasConfig(
                newAtlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withPxRange(float newPxRange) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                newPxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withSpacingPx(int newSpacingPx) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                newSpacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withMiterLimit(float newMiterLimit) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                newMiterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withAlignOriginX(boolean newAlignOriginX) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                newAlignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withAlignOriginY(boolean newAlignOriginY) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                newAlignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withOverlapSupport(boolean newOverlapSupport) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                newOverlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withErrorCorrection(int newErrorCorrectionMode,
                                                 int newDistanceCheckMode,
                                                 double newMinDeviationRatio,
                                                 double newMinImproveRatio) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                newErrorCorrectionMode,
                newDistanceCheckMode,
                newMinDeviationRatio,
                newMinImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold);
    }

    public CgMsdfAtlasConfig withEdgeColoring(CgMsdfEdgeColoringMode newEdgeColoringMode,
                                              double newAngleThreshold) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                newEdgeColoringMode,
                newAngleThreshold);
    }

    public CgGlyphAtlas.Type resolveAtlasType() {
        return mtsdf ? CgGlyphAtlas.Type.MTSDF : CgGlyphAtlas.Type.MSDF;
    }

    public CgMsdfAtlasConfig withMtsdf(boolean newMtsdf) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY,
                overlapSupport,
                errorCorrectionMode,
                distanceCheckMode,
                minDeviationRatio,
                minImproveRatio,
                edgeColoringMode,
                edgeColoringAngleThreshold,
                newMtsdf);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgMsdfAtlasConfig)) return false;

        CgMsdfAtlasConfig that = (CgMsdfAtlasConfig) o;

        return atlasScalePx == that.atlasScalePx
                && Float.compare(that.pxRange, pxRange) == 0
                && pageSize == that.pageSize
                && spacingPx == that.spacingPx
                && Float.compare(that.miterLimit, miterLimit) == 0
                && alignOriginX == that.alignOriginX
                && alignOriginY == that.alignOriginY
                && overlapSupport == that.overlapSupport
                && errorCorrectionMode == that.errorCorrectionMode
                && distanceCheckMode == that.distanceCheckMode
                && Double.compare(that.minDeviationRatio, minDeviationRatio) == 0
                && Double.compare(that.minImproveRatio, minImproveRatio) == 0
                && Double.compare(that.edgeColoringAngleThreshold, edgeColoringAngleThreshold) == 0
                && edgeColoringMode == that.edgeColoringMode
                && mtsdf == that.mtsdf;
    }

    @Override
    public int hashCode() {
        int result = atlasScalePx;
        result = 31 * result + Float.floatToIntBits(pxRange);
        result = 31 * result + pageSize;
        result = 31 * result + spacingPx;
        result = 31 * result + Float.floatToIntBits(miterLimit);
        result = 31 * result + (alignOriginX ? 1 : 0);
        result = 31 * result + (alignOriginY ? 1 : 0);
        result = 31 * result + (overlapSupport ? 1 : 0);
        result = 31 * result + errorCorrectionMode;
        result = 31 * result + distanceCheckMode;
        long temp = Double.doubleToLongBits(minDeviationRatio);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(minImproveRatio);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + edgeColoringMode.hashCode();
        temp = Double.doubleToLongBits(edgeColoringAngleThreshold);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + (mtsdf ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "CgMsdfAtlasConfig{" +
                "atlasScalePx=" + atlasScalePx +
                ", pxRange=" + pxRange +
                ", pageSize=" + pageSize +
                ", spacingPx=" + spacingPx +
                ", miterLimit=" + miterLimit +
                ", alignOriginX=" + alignOriginX +
                ", alignOriginY=" + alignOriginY +
                ", overlapSupport=" + overlapSupport +
                ", errorCorrectionMode=" + errorCorrectionMode +
                ", distanceCheckMode=" + distanceCheckMode +
                ", minDeviationRatio=" + minDeviationRatio +
                ", minImproveRatio=" + minImproveRatio +
                ", edgeColoringMode=" + edgeColoringMode +
                ", edgeColoringAngleThreshold=" + edgeColoringAngleThreshold +
                ", mtsdf=" + mtsdf +
                '}';
    }
}
