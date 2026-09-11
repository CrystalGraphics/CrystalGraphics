package com.crystalgraphics.api.font;

import java.util.Locale;

/**
 * CSS's generic font families: a name for "whatever this platform uses for serif text" rather
 * than for one font. {@link CgSystemFonts#generic} resolves one to an installed face.
 *
 * <pre>{@code
 * CgGenericFamily mono = CgGenericFamily.fromCss("monospace");      // MONOSPACE
 * CgSystemFonts fonts = CgSystemFonts.get();
 * CgFont font = fonts.load(fonts.generic(mono, CgFontStyle.REGULAR), CgFontStyle.REGULAR, 14);
 * }</pre>
 */
public enum CgGenericFamily {

    SERIF("serif"),
    SANS_SERIF("sans-serif"),
    MONOSPACE("monospace"),
    CURSIVE("cursive"),
    FANTASY("fantasy"),
    /** The platform's own UI face — Segoe UI, San Francisco, Cantarell. */
    SYSTEM_UI("system-ui"),
    UI_SERIF("ui-serif"),
    UI_SANS_SERIF("ui-sans-serif"),
    UI_MONOSPACE("ui-monospace"),
    UI_ROUNDED("ui-rounded"),
    EMOJI("emoji"),
    MATH("math");

    private final String cssName;

    CgGenericFamily(String cssName) {
        this.cssName = cssName;
    }

    /** The keyword as CSS spells it: {@code sans-serif}, {@code system-ui}. */
    public String cssName() {
        return cssName;
    }

    /** The generic family {@code keyword} names, case-insensitively, or {@code null} for a family name. */
    public static CgGenericFamily fromCss(String keyword) {
        if (keyword == null) {
            return null;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        for (CgGenericFamily generic : values()) {
            if (generic.cssName.equals(normalized)) {
                return generic;
            }
        }
        return null;
    }
}
