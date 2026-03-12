package com.crystalgraphics.freetype;

/**
 * FreeType glyph loading flags. Combine with bitwise OR and pass to {@link FTFace#loadGlyph(int, int)}.
 * These map directly to FT_LOAD_* constants from FreeType's freetype.h.
 */
public final class FTLoadFlags {
    public static final int FT_LOAD_DEFAULT = 0x0;
    public static final int FT_LOAD_NO_SCALE = 1 << 0;
    public static final int FT_LOAD_NO_HINTING = 1 << 1;
    public static final int FT_LOAD_RENDER = 1 << 2;
    public static final int FT_LOAD_NO_BITMAP = 1 << 3;
    public static final int FT_LOAD_VERTICAL_LAYOUT = 1 << 4;
    public static final int FT_LOAD_FORCE_AUTOHINT = 1 << 5;
    public static final int FT_LOAD_PEDANTIC = 1 << 7;
    public static final int FT_LOAD_NO_AUTOHINT = 1 << 15;
    public static final int FT_LOAD_COLOR = 1 << 20;
    public static final int FT_LOAD_MONOCHROME = 1 << 12;
    public static final int FT_LOAD_LINEAR_DESIGN = 1 << 13;

    private FTLoadFlags() {
    }
}
