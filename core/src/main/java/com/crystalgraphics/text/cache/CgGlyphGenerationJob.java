package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFontData;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

/**
 * Immutable descriptor for one async glyph-generation unit of work.
 *
 * <p>Carries the font's data, glyph key, raster key, MSDF config, and
 * sub-pixel bucket needed by {@link CgWorkerFontContext} to produce a
 * {@link CgGlyphGenerationResult} off the render thread.</p>
 */
final class CgGlyphGenerationJob {

    private final CgFontKey sourceFontKey;
    private final CgFontData fontData;
    private final CgGlyphKey atlasKey;
    private final CgRasterFontKey bitmapRasterKey;
    private final CgMsdfAtlasKey msdfAtlasKey;
    private final CgMsdfAtlasConfig msdfConfig;
    private final int effectiveTargetPx;
    private final int subPixelBucket;

    private CgGlyphGenerationJob(CgFontKey sourceFontKey,
                                 CgFontData fontData,
                                 CgGlyphKey atlasKey,
                                 CgRasterFontKey bitmapRasterKey,
                                 CgMsdfAtlasKey msdfAtlasKey,
                                 CgMsdfAtlasConfig msdfConfig,
                                 int effectiveTargetPx,
                                 int subPixelBucket) {
        if (sourceFontKey == null) {
            throw new IllegalArgumentException("sourceFontKey must not be null");
        }
        if (fontData == null) {
            throw new IllegalArgumentException("fontData must not be null");
        }
        if (atlasKey == null) {
            throw new IllegalArgumentException("atlasKey must not be null");
        }
        this.sourceFontKey = sourceFontKey;
        this.fontData = fontData;
        this.atlasKey = atlasKey;
        this.bitmapRasterKey = bitmapRasterKey;
        this.msdfAtlasKey = msdfAtlasKey;
        this.msdfConfig = msdfConfig;
        this.effectiveTargetPx = effectiveTargetPx;
        this.subPixelBucket = subPixelBucket;
    }

    static CgGlyphGenerationJob bitmap(CgFontKey sourceFontKey,
                                       CgFontData fontData,
                                       CgGlyphKey atlasKey,
                                       CgRasterFontKey bitmapRasterKey,
                                       int effectiveTargetPx,
                                       int subPixelBucket) {
        return new CgGlyphGenerationJob(
                sourceFontKey,
                fontData,
                atlasKey,
                bitmapRasterKey,
                null,
                null,
                effectiveTargetPx,
                subPixelBucket);
    }

    static CgGlyphGenerationJob msdf(CgFontKey sourceFontKey,
                                     CgFontData fontData,
                                     CgGlyphKey atlasKey,
                                     CgMsdfAtlasKey msdfAtlasKey,
                                     CgMsdfAtlasConfig msdfConfig) {
        return new CgGlyphGenerationJob(
                sourceFontKey,
                fontData,
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

    CgFontData getFontData() {
        return fontData;
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

    /**
     * <b>Two jobs are the same job when they would write the same atlas entry</b>, which is what the
     * executor's {@code pendingJobs} map is actually asking: is this already being generated.
     *
     * <p><b>A distance-field job therefore ignores who asked for it.</b> {@code atlasKey} and
     * {@code msdfAtlasKey} are both rewritten to {@code CgMsdfAtlasConfig.atlasScalePx} before a job
     * is built, because every size of a face collapses onto one distance field — that is the whole
     * point of one. So {@code sourceFontKey} and {@code effectiveTargetPx} say only which INSTANCE
     * happened to ask first, and including them made two sizes of one face two unequal jobs producing
     * one identical entry: the dedup missed, and msdfgen ran twice at ~30ms a glyph.
     *
     * <p>That is not hypothetical and it is not rare. {@code FontFamilyCache} caches by
     * {@code (stack, targetPx)}, so every new font size in a stylesheet mints a fresh {@code CgFont},
     * whose {@code warmAscii} re-queues all 95 printable ASCII glyphs to regenerate distance fields
     * that already exist or are already in flight — about three seconds of pointless work, once per
     * size, and visible as text that renders unstyled until it lands. {@code warmAscii}'s own javadoc
     * described this before anything fixed it.
     *
     * <p><b>A bitmap job keeps both</b>, because a raster glyph genuinely is per size: its output
     * differs with {@code effectiveTargetPx} and its sub-pixel bucket, and {@code bitmapRasterKey}
     * carries the pairing that says so.</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CgGlyphGenerationJob)) {
            return false;
        }
        CgGlyphGenerationJob that = (CgGlyphGenerationJob) o;
        if (!atlasKey.equals(that.atlasKey) || isDistanceField() != that.isDistanceField()) {
            return false;
        }
        if (isDistanceField()) {
            return equalsNullable(msdfAtlasKey, that.msdfAtlasKey)
                    && equalsNullable(msdfConfig, that.msdfConfig);
        }
        return effectiveTargetPx == that.effectiveTargetPx
                && subPixelBucket == that.subPixelBucket
                && sourceFontKey.equals(that.sourceFontKey)
                && equalsNullable(bitmapRasterKey, that.bitmapRasterKey);
    }

    @Override
    public int hashCode() {
        int result = atlasKey.hashCode();
        if (isDistanceField()) {
            result = 31 * result + (msdfAtlasKey != null ? msdfAtlasKey.hashCode() : 0);
            result = 31 * result + (msdfConfig != null ? msdfConfig.hashCode() : 0);
            return result;
        }
        result = 31 * result + sourceFontKey.hashCode();
        result = 31 * result + (bitmapRasterKey != null ? bitmapRasterKey.hashCode() : 0);
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
                ", fontData=" + fontData +
                '}';
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
