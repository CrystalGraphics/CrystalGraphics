package io.github.somehussar.crystalgraphics.text.cache;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.text.render.CgTextRenderer;
import io.github.somehussar.crystalgraphics.text.render.CgTextScaleResolver;

/**
 * Cache-internal discriminator for effective-size-aware glyph/atlas lookup.
 *
 * <p>A {@code CgRasterFontKey} combines the base font identity (path + style)
 * with the <strong>effective</strong> target pixel size resolved at draw time
 * from the pose transform. This allows the same logical font to produce
 * multiple atlas buckets at different raster sizes when rendered under
 * different transforms (e.g., zoomed UI, scaled overlay).</p>
 *
 * <h3>Visibility Contract</h3>
 * <p>The class is {@code public} because the renderer
 * ({@link CgTextRenderer})
 * needs to reference it for atlas-key construction.  However, the constructor
 * and most accessors are package-private — only {@link CgFontRegistry} should
 * create instances.  External code should treat this type as <em>opaque</em>:
 * it can be stored, passed, and compared for equality, but should not be
 * introspected beyond {@link #getBaseFontKey()}.</p>
 *
 * <h3>Cache Identity</h3>
 * <p>Equality and hash code are based on {@code baseFontKey} and
 * {@code effectiveTargetPx}. The {@code effectiveTargetPx} is always an integer
 * (quantized by the scale resolver) to prevent float-keyed cache pollution.</p>
 *
 * <h3>Pipeline Role</h3>
 * <p>CgRasterFontKey is the atlas-bucket discriminator in the cache pipeline.
 * {@link CgFontRegistry} uses it to index per-size bitmap and MSDF atlases,
 * and the renderer uses it to resolve the correct atlas for the current
 * effective draw size.</p>
 *
 * @see CgRasterGlyphKey
 * @see CgTextScaleResolver
 */
public final class CgRasterFontKey {

    private final CgFontKey baseFontKey;
    private final int effectiveTargetPx;

    CgRasterFontKey(CgFontKey baseFontKey, int effectiveTargetPx) {
        if (baseFontKey == null) {
            throw new IllegalArgumentException("baseFontKey must not be null");
        }
        this.baseFontKey = baseFontKey;
        this.effectiveTargetPx = effectiveTargetPx;
    }

    /**
     * Returns the base font identity (path + style + original targetPx).
     *
     * <p>This is the only accessor exposed outside the cache package.
     * External consumers should not need the effective pixel size directly.</p>
     */
    public CgFontKey getBaseFontKey() {
        return baseFontKey;
    }

    // ── Package-private accessors (cache-internal use only) ────────────

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
