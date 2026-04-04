package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable per-glyph metrics for a single rasterized glyph.
 *
 * <p>All values are in pixels, derived from FreeType's {@code FT_GlyphSlot}
 * metrics after loading and rasterizing at the target pixel size. These metrics
 * are used to position glyph quads relative to the pen origin and baseline.</p>
 *
 * <h3>Metric definitions</h3>
 * <ul>
 *   <li>{@code advanceX} — horizontal advance from pen origin to the next glyph's
 *       pen origin (includes inter-glyph spacing)</li>
 *   <li>{@code bearingX} — horizontal distance from the pen origin to the left edge
 *       of the glyph bitmap</li>
 *   <li>{@code bearingY} — vertical distance from the baseline to the top edge of
 *       the glyph bitmap (positive = above baseline)</li>
 *   <li>{@code width} — width of the glyph bitmap in pixels</li>
 *   <li>{@code height} — height of the glyph bitmap in pixels</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 * CgGlyphMetrics gm = new CgGlyphMetrics(8.0f, 1.0f, 10.0f, 7.0f, 11.0f);
 * float glyphX = penX + gm.getBearingX();
 * float glyphY = penY - gm.getBearingY();  // baseline-relative positioning
 * </pre>
 *
 * @see CgGlyphKey
 */
@Value
public class CgGlyphMetrics {

    /** Horizontal advance in pixels. */
    float advanceX;

    /** Horizontal bearing from pen origin in pixels. */
    float bearingX;

    /** Vertical bearing from baseline in pixels (positive = above baseline). */
    float bearingY;

    /** Glyph bitmap width in pixels. */
    float width;

    /** Glyph bitmap height in pixels. */
    float height;
}
