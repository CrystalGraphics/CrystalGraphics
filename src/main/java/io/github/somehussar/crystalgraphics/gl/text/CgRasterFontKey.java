package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;

/**
 * Internal cache discriminator for effective-size-aware glyph/atlas lookup.
 *
 * <p>A {@code CgRasterFontKey} combines the base font identity (path + style)
 * with the <strong>effective</strong> target pixel size resolved at draw time
 * from the pose transform. This allows the same logical font to produce
 * multiple atlas buckets at different raster sizes when rendered under
 * different transforms (e.g., zoomed UI, scaled overlay).</p>
 *
 * <p>This is an <strong>internal</strong> type — it is not part of the public
 * API. The public {@link CgFontKey} remains unchanged and continues to represent
 * the base font identity with the original {@code targetPx}.</p>
 *
 * <h3>Cache Identity</h3>
 * <p>Equality and hash code are based on {@code fontPath}, {@code style}, and
 * {@code effectiveTargetPx}. The {@code effectiveTargetPx} is always an integer
 * (quantized by the scale resolver) to prevent float-keyed cache pollution.</p>
 *
 * @see CgRasterGlyphKey
 * @see CgTextScaleResolver
 */
final class CgRasterFontKey {

    private final CgFontKey baseFontKey;
    private final int effectiveTargetPx;

    CgRasterFontKey(CgFontKey baseFontKey, int effectiveTargetPx) {
        if (baseFontKey == null) {
            throw new IllegalArgumentException("baseFontKey must not be null");
        }
        this.baseFontKey = baseFontKey;
        this.effectiveTargetPx = effectiveTargetPx;
    }

    CgFontKey getBaseFontKey() {
        return baseFontKey;
    }

    String getFontPath() {
        return baseFontKey.getFontPath();
    }

    io.github.somehussar.crystalgraphics.api.font.CgFontStyle getStyle() {
        return baseFontKey.getStyle();
    }

    int getEffectiveTargetPx() {
        return effectiveTargetPx;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgRasterFontKey)) return false;
        CgRasterFontKey that = (CgRasterFontKey) o;
        return effectiveTargetPx == that.effectiveTargetPx
                && baseFontKey.equals(that.baseFontKey);
    }

    @Override
    public int hashCode() {
        int result = baseFontKey.hashCode();
        result = 31 * result + effectiveTargetPx;
        return result;
    }

    @Override
    public String toString() {
        return "CgRasterFontKey{" + baseFontKey + ", effective=" + effectiveTargetPx + "px}";
    }
}
