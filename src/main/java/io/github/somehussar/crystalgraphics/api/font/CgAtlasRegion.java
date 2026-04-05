package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable region describing a glyph's location within a texture atlas.
 *
 * <p>Contains both pixel coordinates (for sub-image upload reference) and
 * normalised UV coordinates (for vertex shader texture sampling). It also stores
 * glyph metrics captured at <strong>rasterization time</strong>.</p>

 * <h3>Metric Space Contract (Physical Raster Space Only)</h3>
 * <p>The {@code width}, {@code height}, {@code bearingX}, and {@code bearingY}
 * values in this region are always expressed in the <strong>physical raster
 * space</strong> of the atlas entry that produced them. They are <em>not</em>
 * canonical logical layout metrics and must never be used directly as glyph
 * advances or layout spacing.</p>
 *
 * <p>When the glyph was rasterized at an effective size different from the base
 * font size (i.e., {@code effectiveTargetPx != baseTargetPx}), renderers must
 * normalize these values back into logical space before combining them with
 * logical pen positions. The normalization formula is:
 * {@code scaleFactor = baseTargetPx / (float) effectiveTargetPx}, applied to
 * bearingX, bearingY, width, and height. This normalization happens at the
 * renderer/composite boundary ({@code CgTextRenderer.appendQuads}), not in
 * the registry or atlas.</p>
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

    /** Width of the glyph region in physical raster pixels. */
    int width;

    /** Height of the glyph region in physical raster pixels. */
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
     * Horizontal bearing from pen origin in physical raster pixels.
     * Derived from FreeType 26.6 fixed-point ({@code horiBearingX / 64.0f}).
     */
    float bearingX;

    /**
     * Vertical bearing from baseline in physical raster pixels
     * (positive = above baseline).
     * Derived from FreeType 26.6 fixed-point ({@code horiBearingY / 64.0f}).
     */
    float bearingY;

    /**
     * Glyph outline width in logical (base-size) units.
     * Derived from FreeType 26.6 fixed-point ({@code metrics.width / 64.0f})
     * at the base font size, not the effective raster size.
     *
     * <p>When the glyph is rasterized at an effective size different from
     * the base {@code targetPx}, the registry queries metrics at the base
     * size and stores them here. This avoids the hinting-rounding drift
     * that would occur if effective-size metrics were scaled back to
     * logical space via {@code basePx / effectivePx}. Renderers should
     * use this directly for quad placement without any scale factor.</p>
     */
    float metricsWidth;

    /**
     * Glyph outline height in logical (base-size) units.
     * Derived from FreeType 26.6 fixed-point ({@code metrics.height / 64.0f})
     * at the base font size, not the effective raster size.
     *
     * <p>When the glyph is rasterized at an effective size different from
     * the base {@code targetPx}, the registry queries metrics at the base
     * size and stores them here. This avoids the hinting-rounding drift
     * that would occur if effective-size metrics were scaled back to
     * logical space via {@code basePx / effectivePx}. Renderers should
     * use this directly for quad placement without any scale factor.</p>
     */
    float metricsHeight;
}
