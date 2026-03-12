package com.crystalgraphics.freetype;

/**
 * Immutable glyph metrics from FreeType.
 * All values are in 26.6 fixed-point format (divide by 64 for pixels) unless noted.
 */
public final class FTGlyphMetrics {

    private final int width;
    private final int height;
    private final int horiBearingX;
    private final int horiBearingY;
    private final int horiAdvance;
    private final int vertBearingX;
    private final int vertBearingY;
    private final int vertAdvance;

    public FTGlyphMetrics(int width, int height,
                          int horiBearingX, int horiBearingY, int horiAdvance,
                          int vertBearingX, int vertBearingY, int vertAdvance) {
        this.width = width;
        this.height = height;
        this.horiBearingX = horiBearingX;
        this.horiBearingY = horiBearingY;
        this.horiAdvance = horiAdvance;
        this.vertBearingX = vertBearingX;
        this.vertBearingY = vertBearingY;
        this.vertAdvance = vertAdvance;
    }

    /** Glyph width in 26.6 fixed-point. */
    public int getWidth() { return width; }

    /** Glyph height in 26.6 fixed-point. */
    public int getHeight() { return height; }

    /** Horizontal bearing X in 26.6 fixed-point. */
    public int getHoriBearingX() { return horiBearingX; }

    /** Horizontal bearing Y in 26.6 fixed-point. */
    public int getHoriBearingY() { return horiBearingY; }

    /** Horizontal advance in 26.6 fixed-point. */
    public int getHoriAdvance() { return horiAdvance; }

    /** Vertical bearing X in 26.6 fixed-point. */
    public int getVertBearingX() { return vertBearingX; }

    /** Vertical bearing Y in 26.6 fixed-point. */
    public int getVertBearingY() { return vertBearingY; }

    /** Vertical advance in 26.6 fixed-point. */
    public int getVertAdvance() { return vertAdvance; }

    /** Width in pixels (rounded from 26.6 fixed-point). */
    public int getWidthPixels() { return (width + 32) >> 6; }

    /** Height in pixels (rounded from 26.6 fixed-point). */
    public int getHeightPixels() { return (height + 32) >> 6; }

    /** Horizontal advance in pixels (rounded from 26.6 fixed-point). */
    public int getHoriAdvancePixels() { return (horiAdvance + 32) >> 6; }

    @Override
    public String toString() {
        return "FTGlyphMetrics{" +
                "width=" + width + ", height=" + height +
                ", horiBearingX=" + horiBearingX + ", horiBearingY=" + horiBearingY +
                ", horiAdvance=" + horiAdvance +
                '}';
    }
}
