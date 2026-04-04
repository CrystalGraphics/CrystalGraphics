package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable region describing a glyph's location within a texture atlas.
 *
 * <p>Contains both pixel coordinates (for sub-image upload reference) and
 * normalised UV coordinates (for vertex shader texture sampling). Also stores
 * the glyph placement metrics (bearing) captured at rasterization time so the
 * renderer can position quads relative to the pen origin and baseline without
 * a second metrics lookup.</p>
 *
 * <p>Instances are created by {@code CgGlyphAtlas.getOrAllocate()} and should
 * not be constructed directly by external callers.</p>
 *
 * @see CgGlyphKey
 */
@Value
public class CgAtlasRegion {

    /** X position of the region's top-left corner in the atlas (pixels). */
    int atlasX;

    /** Y position of the region's top-left corner in the atlas (pixels). */
    int atlasY;

    /** Width of the glyph region in pixels. */
    int width;

    /** Height of the glyph region in pixels. */
    int height;

    /** Normalised U coordinate of the left edge [0, 1]. */
    float u0;

    /** Normalised V coordinate of the top edge [0, 1]. */
    float v0;

    /** Normalised U coordinate of the right edge [0, 1]. */
    float u1;

    /** Normalised V coordinate of the bottom edge [0, 1]. */
    float v1;

    /** The glyph key this region was allocated for. */
    CgGlyphKey key;

    /**
     * Horizontal bearing from pen origin (pixels).
     * Derived from FreeType 26.6 fixed-point ({@code horiBearingX / 64.0f}).
     */
    float bearingX;

    /**
     * Vertical bearing from baseline (pixels, positive = above baseline).
     * Derived from FreeType 26.6 fixed-point ({@code horiBearingY / 64.0f}).
     */
    float bearingY;
}
