package io.github.somehussar.crystalgraphics.text.cache;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;

/**
 * Internal atlas-family key for shared MSDF atlases.
 *
 * <p>Unlike {@link CgRasterFontKey}, this key deliberately ignores requested
 * render size. One atlas family is shared per font/style plus atlas-generation
 * configuration.</p>
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
