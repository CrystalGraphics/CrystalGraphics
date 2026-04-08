package com.crystalgraphics.harfbuzz;

public final class HBGlyphInfo {
    private final int codepoint;
    private final int cluster;

    public HBGlyphInfo(int codepoint, int cluster) {
        this.codepoint = codepoint;
        this.cluster = cluster;
    }

    public int getCodepoint() { return codepoint; }
    public int getCluster() { return cluster; }

    @Override
    public String toString() {
        return "HBGlyphInfo{glyph=" + codepoint + ", cluster=" + cluster + '}';
    }
}
