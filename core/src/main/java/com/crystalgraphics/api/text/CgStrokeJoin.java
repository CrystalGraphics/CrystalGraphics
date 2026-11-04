package com.crystalgraphics.api.text;

/**
 * How a text stroke turns a corner of the glyph: rounded, as Unreal's font outlines are by default
 * ({@code FFontOutlineSettings::bMiteredCorners} false), or sharp, as {@code -webkit-text-stroke} draws.
 *
 * <pre>{@code
 * renderer.draw().text("Ready").font(font).stroke(0.1f, 0xFF000000).submit();                      // ROUND
 * renderer.draw().text("Ready").font(font).stroke(0.1f, 0xFF000000).strokeJoin(CgStrokeJoin.MITRE).submit();
 * }</pre>
 *
 * <p>Only the outer edge of the stroke is joined: a {@link CgStrokeAlign#CENTER} or {@link CgStrokeAlign#INSET}
 * stroke's inner edge follows the glyph as drawn.</p>
 */
public enum CgStrokeJoin {
    /** Corners rounded by the stroke's width. The default. */
    ROUND,
    /** Corners kept sharp. */
    MITRE
}
