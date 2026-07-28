package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

/**
 * Carrier for the MSDF generation configuration along the async glyph pipeline.
 *
 * <p><strong>No longer an atlas key, despite the name.</strong> There is now exactly one shared
 * distance-field atlas for every font (see {@code CgFontRegistry}'s atlas fields), so nothing keys
 * an atlas by font identity any more. What survives is this type's second job: carrying the
 * {@link CgMsdfAtlasConfig} from job submission through to result application, which
 * {@code CgGlyphGenerationJob} and {@code CgGlyphGenerationResult} still need.
 *
 * <p>Its {@code baseFontKey} component is consequently vestigial for atlas selection. It is
 * retained because it still participates in job equality, where it is harmless, and removing it
 * touches four files in the async pipeline for no behavioural gain. Renaming this to something like
 * {@code CgMsdfGenerationParams} and dropping the font component is a clean follow-up, not a
 * correctness issue.
 *
 * <p>Unlike {@link CgRasterFontKey}, it deliberately ignores requested render size.</p>
 *
 * <h3>Pipeline Role</h3>
 * <p>CgMsdfAtlasKey groups glyphs that share the same MSDF generation
 * parameters (font identity + {@link CgMsdfAtlasConfig}) into a single
 * atlas family managed by {@link CgFontRegistry}.  The registry uses this
 * key to look up or create the paged atlas backing an MSDF font
 * configuration.</p>
 */
public final class CgMsdfAtlasKey {

    private final CgFontKey baseFontKey;
    private final CgMsdfAtlasConfig config;

    public CgMsdfAtlasKey(CgFontKey baseFontKey, CgMsdfAtlasConfig config) {
        if (baseFontKey == null) {
            throw new IllegalArgumentException("baseFontKey must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.baseFontKey = baseFontKey;
        this.config = config;
    }

    public CgFontKey getBaseFontKey() {
        return baseFontKey;
    }

    public String getFontPath() {
        return baseFontKey.getFontPath();
    }

    public CgFontStyle getStyle() {
        return baseFontKey.getStyle();
    }

   public  CgMsdfAtlasConfig getConfig() {
        return config;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgMsdfAtlasKey)) return false;

        CgMsdfAtlasKey that = (CgMsdfAtlasKey) o;

        return baseFontKey.equals(that.baseFontKey)
                && config.equals(that.config);
    }

    @Override
    public int hashCode() {
        int result = baseFontKey.hashCode();
        result = 31 * result + config.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CgMsdfAtlasKey{" + baseFontKey + ", " + config + '}';
    }
}
