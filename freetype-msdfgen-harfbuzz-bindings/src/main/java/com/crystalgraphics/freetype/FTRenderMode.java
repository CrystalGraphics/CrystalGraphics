package com.crystalgraphics.freetype;

/**
 * FreeType render mode constants for glyph rasterization.
 * Passed to {@link FTFace#renderGlyph(int)}.
 */
public final class FTRenderMode {
    /** Normal anti-aliased rendering (8-bit grayscale). */
    public static final int FT_RENDER_MODE_NORMAL = 0;
    /** Monochrome (1-bit) rendering. */
    public static final int FT_RENDER_MODE_MONO = 2;
    /** LCD sub-pixel rendering (horizontal RGB). */
    public static final int FT_RENDER_MODE_LCD = 3;
    /** LCD sub-pixel rendering (vertical RGB). */
    public static final int FT_RENDER_MODE_LCD_V = 4;

    private FTRenderMode() {
    }
}
