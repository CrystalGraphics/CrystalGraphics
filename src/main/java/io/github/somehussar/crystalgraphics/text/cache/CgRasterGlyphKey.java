package io.github.somehussar.crystalgraphics.text.cache;

import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.text.render.CgTextScaleResolver;

/**
 * Internal cache discriminator for effective-size-aware individual glyph lookup.
 *
 * <p>A {@code CgRasterGlyphKey} combines a {@link CgRasterFontKey} (which
 * includes the effective raster size) with the glyph ID, MSDF flag, and
 * sub-pixel bucket. This key is used internally by the registry and renderer
 * to look up glyphs at the correct effective size without polluting the
 * public {@link CgGlyphKey} with draw-time state.</p>
 *
 * <p>Sub-pixel bucket rules use the effective target pixel size rather than
 * the base target pixel size, since the effective size determines whether
 * sub-pixel positioning is perceptible.</p>
 *
 * <h3>Pipeline Role</h3>
 * <p>CgRasterGlyphKey is the per-glyph lookup key in the cache pipeline.
 * {@link CgFontRegistry} uses it to check whether a glyph at a specific
 * effective size and sub-pixel offset has already been rasterized and
 * uploaded to an atlas.</p>
 *
 * @see CgRasterFontKey
 * @see CgTextScaleResolver
 */
final class CgRasterGlyphKey {

    private final CgRasterFontKey rasterFontKey;
    private final int glyphId;
    private final boolean msdf;
    private final int subPixelBucket;

    CgRasterGlyphKey(CgRasterFontKey rasterFontKey, int glyphId, boolean msdf, int subPixelBucket) {
        this.rasterFontKey = rasterFontKey;
        this.glyphId = glyphId;
        this.msdf = msdf;
        this.subPixelBucket = subPixelBucket;
    }

    CgRasterFontKey getRasterFontKey() {
        return rasterFontKey;
    }

    int getGlyphId() {
        return glyphId;
    }

    boolean isMsdf() {
        return msdf;
    }

    int getSubPixelBucket() {
        return subPixelBucket;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgRasterGlyphKey)) return false;
        CgRasterGlyphKey that = (CgRasterGlyphKey) o;
        return glyphId == that.glyphId
                && msdf == that.msdf
                && subPixelBucket == that.subPixelBucket
                && rasterFontKey.equals(that.rasterFontKey);
    }

    @Override
    public int hashCode() {
        int result = rasterFontKey.hashCode();
        result = 31 * result + glyphId;
        result = 31 * result + (msdf ? 1 : 0);
        result = 31 * result + subPixelBucket;
        return result;
    }

    @Override
    public String toString() {
        return "CgRasterGlyphKey{" + rasterFontKey + ", glyph=" + glyphId
                + ", msdf=" + msdf + ", bucket=" + subPixelBucket + "}";
    }
}
