package io.github.somehussar.crystalgraphics.gl.text.msdf;

import com.msdfgen.MsdfConstants;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;

/**
 * Immutable configuration for a shared MSDF atlas family.
 *
 * <p>This separates atlas-generation concerns from requested render size.
 * A single MSDF atlas family is generated at one fixed atlas scale and can
 * then be reused across many output sizes by the runtime shader.</p>
 */
public final class CgMsdfAtlasConfig {

    public static final int DEFAULT_ATLAS_SCALE_PX = 64;
    public static final float DEFAULT_PX_RANGE = 6.0f;
    public static final int DEFAULT_PAGE_SIZE = 512;
    public static final int DEFAULT_SPACING_PX = 1;
    public static final float DEFAULT_MITER_LIMIT = 2.0f;
    public static final boolean DEFAULT_ALIGN_ORIGIN_X = false;
    public static final boolean DEFAULT_ALIGN_ORIGIN_Y = true;
    public static final boolean DEFAULT_OVERLAP_SUPPORT = true;
    public static final int DEFAULT_ERROR_CORRECTION_MODE = MsdfConstants.ERROR_CORRECTION_EDGE_PRIORITY;
    public static final int DEFAULT_DISTANCE_CHECK_MODE = MsdfConstants.DISTANCE_CHECK_AT_EDGE;
    public static final double DEFAULT_MIN_DEVIATION_RATIO = MsdfConstants.DEFAULT_MIN_DEVIATION_RATIO;
    public static final double DEFAULT_MIN_IMPROVE_RATIO = MsdfConstants.DEFAULT_MIN_IMPROVE_RATIO;
    public static final CgMsdfEdgeColoringMode DEFAULT_EDGE_COLORING_MODE = CgMsdfEdgeColoringMode.INK_TRAP;
    public static final double DEFAULT_EDGE_COLORING_ANGLE_THRESHOLD = 3.0d;
    public static final boolean DEFAULT_MTSDF = true;

    private final int atlasScalePx;
    private final float pxRange;
    private final int pageSize;
    private final int spacingPx;
    private final float miterLimit;
    private final boolean alignOriginX;
    private final boolean alignOriginY;
    private final boolean overlapSupport;
    private final int errorCorrectionMode;
    private final int distanceCheckMode;
    private final double minDeviationRatio;
    private final double minImproveRatio;
    private final CgMsdfEdgeColoringMode edgeColoringMode;
    private final double edgeColoringAngleThreshold;
    private final boolean mtsdf;

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
                             double edgeColoringAngleThreshold,
                             boolean mtsdf) {
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
            throw new IllegalArgumentException("edgeColoringAngleThreshold must be > 0, got " + edgeColoringAngleThreshold);
        }
        this.atlasScalePx = atlasScalePx;
        this.pxRange = pxRange;
        this.pageSize = pageSize;
        this.spacingPx = spacingPx;
        this.miterLimit = miterLimit;
        this.alignOriginX = alignOriginX;
        this.alignOriginY = alignOriginY;
        this.overlapSupport = overlapSupport;
        this.errorCorrectionMode = errorCorrectionMode;
        this.distanceCheckMode = distanceCheckMode;
        this.minDeviationRatio = minDeviationRatio;
        this.minImproveRatio = minImproveRatio;
        this.edgeColoringMode = edgeColoringMode;
        this.edgeColoringAngleThreshold = edgeColoringAngleThreshold;
        this.mtsdf = mtsdf;
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

    public int getAtlasScalePx() {
        return atlasScalePx;
    }

    public float getPxRange() {
        return pxRange;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getSpacingPx() {
        return spacingPx;
    }

    public float getMiterLimit() {
        return miterLimit;
    }

    public boolean isAlignOriginX() {
        return alignOriginX;
    }

    public boolean isAlignOriginY() {
        return alignOriginY;
    }

    public boolean isOverlapSupport() {
        return overlapSupport;
    }

    public int getErrorCorrectionMode() {
        return errorCorrectionMode;
    }

    public int getDistanceCheckMode() {
        return distanceCheckMode;
    }

    public double getMinDeviationRatio() {
        return minDeviationRatio;
    }

    public double getMinImproveRatio() {
        return minImproveRatio;
    }

    public CgMsdfEdgeColoringMode getEdgeColoringMode() {
        return edgeColoringMode;
    }

    public double getEdgeColoringAngleThreshold() {
        return edgeColoringAngleThreshold;
    }

    public boolean isMtsdf() {
        return mtsdf;
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
