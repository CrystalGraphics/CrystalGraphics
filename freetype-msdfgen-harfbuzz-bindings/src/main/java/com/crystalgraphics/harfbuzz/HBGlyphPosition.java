package com.crystalgraphics.harfbuzz;

public final class HBGlyphPosition {
    private final int xAdvance;
    private final int yAdvance;
    private final int xOffset;
    private final int yOffset;

    public HBGlyphPosition(int xAdvance, int yAdvance, int xOffset, int yOffset) {
        this.xAdvance = xAdvance;
        this.yAdvance = yAdvance;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
    }

    public int getXAdvance() { return xAdvance; }
    public int getYAdvance() { return yAdvance; }
    public int getXOffset() { return xOffset; }
    public int getYOffset() { return yOffset; }

    @Override
    public String toString() {
        return "HBGlyphPosition{xAdv=" + xAdvance + ", yAdv=" + yAdvance +
                ", xOff=" + xOffset + ", yOff=" + yOffset + '}';
    }
}
