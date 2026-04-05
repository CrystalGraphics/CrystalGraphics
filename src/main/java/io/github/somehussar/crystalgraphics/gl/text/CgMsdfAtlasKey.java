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

    private final String fontPath;
    private final CgFontStyle style;
    private final CgMsdfAtlasConfig config;

    CgMsdfAtlasKey(CgFontKey baseFontKey, CgMsdfAtlasConfig config) {
        this(baseFontKey.getFontPath(), baseFontKey.getStyle(), config);
    }

    CgMsdfAtlasKey(String fontPath, CgFontStyle style, CgMsdfAtlasConfig config) {
        if (fontPath == null) {
            throw new IllegalArgumentException("fontPath must not be null");
        }
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.fontPath = fontPath;
        this.style = style;
        this.config = config;
    }

    String getFontPath() {
        return fontPath;
    }

    CgFontStyle getStyle() {
        return style;
    }

    CgMsdfAtlasConfig getConfig() {
        return config;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgMsdfAtlasKey)) return false;

        CgMsdfAtlasKey that = (CgMsdfAtlasKey) o;

        return fontPath.equals(that.fontPath)
                && style == that.style
                && config.equals(that.config);
    }

    @Override
    public int hashCode() {
        int result = fontPath.hashCode();
        result = 31 * result + style.hashCode();
        result = 31 * result + config.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CgMsdfAtlasKey{" + fontPath + ", " + style + ", " + config + '}';
    }
}
