package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;

/**
 * Internal atlas-family key for shared MSDF atlases.
 *
 * <p>Unlike {@link CgRasterFontKey}, this key deliberately ignores requested
 * render size. One atlas family is shared per font/style plus atlas-generation
 * configuration.</p>
 */
final class CgMsdfAtlasKey {

    private final CgFontKey baseFontKey;
    private final CgMsdfAtlasConfig config;

    CgMsdfAtlasKey(CgFontKey baseFontKey, CgMsdfAtlasConfig config) {
        if (baseFontKey == null) {
            throw new IllegalArgumentException("baseFontKey must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.baseFontKey = baseFontKey;
        this.config = config;
    }

    CgFontKey getBaseFontKey() {
        return baseFontKey;
    }

    String getFontPath() {
        return baseFontKey.getFontPath();
    }

    CgFontStyle getStyle() {
        return baseFontKey.getStyle();
    }

    CgMsdfAtlasConfig getConfig() {
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
