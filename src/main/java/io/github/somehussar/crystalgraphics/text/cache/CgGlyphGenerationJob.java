package io.github.somehussar.crystalgraphics.text.cache;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

import java.util.Arrays;

/**
 * Immutable descriptor for one async glyph-generation unit of work.
 *
 * <p>Carries the font bytes, glyph key, raster key, MSDF config, and
 * sub-pixel bucket needed by {@link CgWorkerFontContext} to produce a
 * {@link CgGlyphGenerationResult} off the render thread.</p>
 */
final class CgGlyphGenerationJob {

    private final CgFontKey sourceFontKey;
    private final byte[] fontBytes;
    private final CgGlyphKey atlasKey;
    private final CgRasterFontKey bitmapRasterKey;
    private final CgMsdfAtlasKey msdfAtlasKey;
    private final CgMsdfAtlasConfig msdfConfig;
    private final int effectiveTargetPx;
    private final int subPixelBucket;

    private CgGlyphGenerationJob(CgFontKey sourceFontKey,
                                 byte[] fontBytes,
                                 CgGlyphKey atlasKey,
                                 CgRasterFontKey bitmapRasterKey,
                                 CgMsdfAtlasKey msdfAtlasKey,
                                 CgMsdfAtlasConfig msdfConfig,
                                 int effectiveTargetPx,
                                 int subPixelBucket) {
        if (sourceFontKey == null) {
            throw new IllegalArgumentException("sourceFontKey must not be null");
        }
        if (fontBytes == null || fontBytes.length == 0) {
            throw new IllegalArgumentException("fontBytes must not be null or empty");
        }
        if (atlasKey == null) {
            throw new IllegalArgumentException("atlasKey must not be null");
        }
        this.sourceFontKey = sourceFontKey;
        this.fontBytes = fontBytes;
        this.atlasKey = atlasKey;
        this.bitmapRasterKey = bitmapRasterKey;
        this.msdfAtlasKey = msdfAtlasKey;
        this.msdfConfig = msdfConfig;
        this.effectiveTargetPx = effectiveTargetPx;
        this.subPixelBucket = subPixelBucket;
    }

    static CgGlyphGenerationJob bitmap(CgFontKey sourceFontKey,
                                       byte[] fontBytes,
                                       CgGlyphKey atlasKey,
                                       CgRasterFontKey bitmapRasterKey,
                                       int effectiveTargetPx,
                                       int subPixelBucket) {
        return new CgGlyphGenerationJob(
                sourceFontKey,
                fontBytes,
                atlasKey,
                bitmapRasterKey,
                null,
                null,
                effectiveTargetPx,
                subPixelBucket);
    }

    static CgGlyphGenerationJob msdf(CgFontKey sourceFontKey,
                                     byte[] fontBytes,
                                     CgGlyphKey atlasKey,
                                     CgMsdfAtlasKey msdfAtlasKey,
                                     CgMsdfAtlasConfig msdfConfig) {
        return new CgGlyphGenerationJob(
                sourceFontKey,
                fontBytes,
                atlasKey,
                null,
                msdfAtlasKey,
                msdfConfig,
                0,
                0);
    }

    CgFontKey getSourceFontKey() {
        return sourceFontKey;
    }

    byte[] getFontBytes() {
        return fontBytes;
    }

    CgGlyphKey getAtlasKey() {
        return atlasKey;
    }

    CgRasterFontKey getBitmapRasterKey() {
        return bitmapRasterKey;
    }

    CgMsdfAtlasKey getMsdfAtlasKey() {
        return msdfAtlasKey;
    }

    CgMsdfAtlasConfig getMsdfConfig() {
        return msdfConfig;
    }

    int getEffectiveTargetPx() {
        return effectiveTargetPx;
    }

    int getSubPixelBucket() {
        return subPixelBucket;
    }

    boolean isMsdf() {
        return getAtlasType() == CgGlyphAtlas.Type.MSDF;
    }

    boolean isDistanceField() {
        return getAtlasType() != CgGlyphAtlas.Type.BITMAP;
    }

    CgGlyphAtlas.Type getAtlasType() {
        if (msdfConfig != null) {
            return msdfConfig.resolveAtlasType();
        }
        return CgGlyphAtlas.Type.BITMAP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CgGlyphGenerationJob)) {
            return false;
        }
        CgGlyphGenerationJob that = (CgGlyphGenerationJob) o;
        return effectiveTargetPx == that.effectiveTargetPx
                && subPixelBucket == that.subPixelBucket
                && sourceFontKey.equals(that.sourceFontKey)
                && atlasKey.equals(that.atlasKey)
                && equalsNullable(bitmapRasterKey, that.bitmapRasterKey)
                && equalsNullable(msdfAtlasKey, that.msdfAtlasKey)
                && equalsNullable(msdfConfig, that.msdfConfig);
    }

    @Override
    public int hashCode() {
        int result = sourceFontKey.hashCode();
        result = 31 * result + atlasKey.hashCode();
        result = 31 * result + (bitmapRasterKey != null ? bitmapRasterKey.hashCode() : 0);
        result = 31 * result + (msdfAtlasKey != null ? msdfAtlasKey.hashCode() : 0);
        result = 31 * result + (msdfConfig != null ? msdfConfig.hashCode() : 0);
        result = 31 * result + effectiveTargetPx;
        result = 31 * result + subPixelBucket;
        return result;
    }

    @Override
    public String toString() {
        return "CgGlyphGenerationJob{" +
                "sourceFontKey=" + sourceFontKey +
                ", atlasKey=" + atlasKey +
                ", bitmapRasterKey=" + bitmapRasterKey +
                ", msdfAtlasKey=" + msdfAtlasKey +
                ", effectiveTargetPx=" + effectiveTargetPx +
                ", subPixelBucket=" + subPixelBucket +
                ", fontBytes=" + Arrays.hashCode(fontBytes) +
                '}';
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
