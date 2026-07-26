package com.crystalgraphics.api.text;

/**
 * Per-line horizontal alignment within a paragraph's box (its {@code maxWidth} if bounded,
 * else the widest line).
 *
 * <p>{@code START}/{@code END} are treated as {@code LEFT}/{@code RIGHT} respectively in
 * this version — direction-aware swapping for RTL paragraphs is not implemented yet.</p>
 *
 * <p>{@code JUSTIFY} is deliberately omitted — text justification is out of scope (see
 * {@code CgBakedGlyphs.justifiable()}'s "Seam A" javadoc); exposing a value that silently
 * does nothing (or throws) would be worse than not offering it.</p>
 */
public enum CgTextAlign {
    LEFT,
    RIGHT,
    CENTER,
    START,
    END
}
