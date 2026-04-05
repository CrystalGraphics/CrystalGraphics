package io.github.somehussar.crystalgraphics.gl.text.msdf;

/**
 * Immutable configuration for a shared MSDF atlas family.
 *
 * <p>This separates atlas-generation concerns from requested render size.
 * A single MSDF atlas family is generated at one fixed atlas scale and can
 * then be reused across many output sizes by the runtime shader.</p>
 */
public final class CgMsdfAtlasConfig {

    public static final int DEFAULT_ATLAS_SCALE_PX = 48;
    public static final float DEFAULT_PX_RANGE = 4.0f;
    public static final int DEFAULT_PAGE_SIZE = 512;
    public static final int DEFAULT_SPACING_PX = 1;
    public static final float DEFAULT_MITER_LIMIT = 1.0f;
    public static final boolean DEFAULT_ALIGN_ORIGIN_X = false;
    public static final boolean DEFAULT_ALIGN_ORIGIN_Y = true;

    private final int atlasScalePx;
    private final float pxRange;
    private final int pageSize;
    private final int spacingPx;
    private final float miterLimit;
    private final boolean alignOriginX;
    private final boolean alignOriginY;

    public CgMsdfAtlasConfig(int atlasScalePx,
                             float pxRange,
                             int pageSize,
                             int spacingPx,
                             float miterLimit,
                             boolean alignOriginX,
                             boolean alignOriginY) {
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
        this.atlasScalePx = atlasScalePx;
        this.pxRange = pxRange;
        this.pageSize = pageSize;
        this.spacingPx = spacingPx;
        this.miterLimit = miterLimit;
        this.alignOriginX = alignOriginX;
        this.alignOriginY = alignOriginY;
    }

    public static CgMsdfAtlasConfig defaultConfig() {
        return new CgMsdfAtlasConfig(
                DEFAULT_ATLAS_SCALE_PX,
                DEFAULT_PX_RANGE,
                DEFAULT_PAGE_SIZE,
                DEFAULT_SPACING_PX,
                DEFAULT_MITER_LIMIT,
                DEFAULT_ALIGN_ORIGIN_X,
                DEFAULT_ALIGN_ORIGIN_Y);
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
                DEFAULT_ALIGN_ORIGIN_Y);
    }

    public CgMsdfAtlasConfig withPageSize(int newPageSize) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                pxRange,
                newPageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY);
    }

    public CgMsdfAtlasConfig withAtlasScalePx(int newAtlasScalePx) {
        return new CgMsdfAtlasConfig(
                newAtlasScalePx,
                pxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY);
    }

    public CgMsdfAtlasConfig withPxRange(float newPxRange) {
        return new CgMsdfAtlasConfig(
                atlasScalePx,
                newPxRange,
                pageSize,
                spacingPx,
                miterLimit,
                alignOriginX,
                alignOriginY);
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
                && alignOriginY == that.alignOriginY;
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
                '}';
    }
}
