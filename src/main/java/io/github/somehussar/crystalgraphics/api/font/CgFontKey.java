package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable key identifying a specific font registration.
 *
 * <p>A {@code CgFontKey} uniquely identifies a font by its path (absolute filesystem
 * path or resource ID), style variant, and target pixel size. Two keys with identical
 * fields are considered equal, making this type safe for use as a {@link java.util.Map}
 * key or {@link java.util.Set} element.</p>
 *
 * <p>The {@code targetPx} field specifies the render size in pixels (e.g., 12, 32, 48).
 * Different pixel sizes produce separate atlas buckets since glyph bitmaps are
 * size-dependent.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * CgFontKey key = new CgFontKey("/fonts/NotoSans-Regular.ttf", CgFontStyle.REGULAR, 12);
 * CgFontKey same = new CgFontKey("/fonts/NotoSans-Regular.ttf", CgFontStyle.REGULAR, 12);
 * assert key.equals(same);  // true — value equality
 * </pre>
 *
 * @see CgFontStyle
 * @see CgGlyphKey
 */
@Value
public class CgFontKey {

    /** Absolute path or resource ID of the font file. */
    String fontPath;

    /** Style variant (regular, bold, italic, bold-italic). */
    CgFontStyle style;

    /** Target render size in pixels (e.g., 12, 32, 48). */
    int targetPx;
}
