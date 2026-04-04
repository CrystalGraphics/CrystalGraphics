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

    private final String fontPath;
    private final io.github.somehussar.crystalgraphics.api.font.CgFontStyle style;
    private final int effectiveTargetPx;

    CgRasterFontKey(CgFontKey baseFontKey, int effectiveTargetPx) {
        this.fontPath = baseFontKey.getFontPath();
        this.style = baseFontKey.getStyle();
        this.effectiveTargetPx = effectiveTargetPx;
    }

    String getFontPath() {
        return fontPath;
    }

    io.github.somehussar.crystalgraphics.api.font.CgFontStyle getStyle() {
        return style;
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
                && fontPath.equals(that.fontPath)
                && style == that.style;
    }

    @Override
    public int hashCode() {
        int result = fontPath.hashCode();
        result = 31 * result + style.hashCode();
        result = 31 * result + effectiveTargetPx;
        return result;
    }

    @Override
    public String toString() {
        return "CgRasterFontKey{" + fontPath + ", " + style + ", effective=" + effectiveTargetPx + "px}";
    }
}
