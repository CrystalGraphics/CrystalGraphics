package com.crystalgraphics.settings;

/** How much CrystalGraphics draws, from least to most: our own tier, never Minecraft's Graphics option. */
public enum CgQuality {
    LOW, MEDIUM, HIGH, ULTRA;

    /** Whether this tier draws what needs at least {@code minimum}. */
    public boolean atLeast(CgQuality minimum) {
        return ordinal() >= minimum.ordinal();
    }
}
