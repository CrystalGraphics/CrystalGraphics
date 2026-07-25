package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;

/**
 * Flat, per-glyph baked layout data for a single {@link CgTextLayout} — pen positions,
 * resolved font/glyph identity, and per-line metadata, computed once by the layout
 * engine's bake step instead of being re-derived by every consumer that walks
 * {@link CgTextLayout#lines()}'s line/run tree.
 *
 * <h3>Coordinate space</h3>
 * <p>{@link #penX}/{@link #penY} are relative to the layout's own {@code (0,0)}
 * origin — callers translate by their own draw position, they do not recompute it.
 * Both already include the glyph's own HarfBuzz offset ({@code CgShapedRun.offsetsX}/
 * {@code offsetsY}) added on top of the pen cursor.</p>
 *
 * <h3>{@code offsetX} — kept separate from {@code penX}</h3>
 * <p>The raw per-glyph horizontal offset (the same value already folded into
 * {@link #penX}), kept as its own array because render-time sub-pixel bucket selection
 * needs the glyph's local offset in isolation, not the fully-accumulated pen position —
 * matching the pre-baking behavior this replaces.</p>
 *
 * <h3>{@code justifiable} — Seam A, unconsumed</h3>
 * <p>{@code true} for a glyph immediately following a UAX#14 word/space boundary,
 * excluding the last such position on its line — a valid justification expansion
 * point. Nothing reads this array yet; it exists so a future justification pass
 * (see {@code docs/font/DIAGNOSIS.md} E3) doesn't need to re-run
 * {@link java.text.BreakIterator} over already-baked text to reconstruct it.</p>
 *
 * <h3>{@code lineHeight} / {@code lineStart} — per-line indexing</h3>
 * <p>{@code lineStart[lineIndex]} is the glyph index where that line's glyphs begin;
 * {@code lineStart[lineCount]} is a sentinel equal to {@code glyphCount}, so a line's
 * glyph range is always {@code [lineStart[i], lineStart[i + 1])}. {@code lineHeight[lineIndex]}
 * is that line's height. Both arrays have one entry per line (plus the sentinel on
 * {@code lineStart}) even though every line shares the same height today — real
 * per-line metrics land later without changing this shape.</p>
 */
public record CgBakedGlyphs(
        int glyphCount,
        CgFontKey[] fontKeys,
        CgFont[] fonts,
        int[] glyphIds,
        float[] penX,
        float[] penY,
        float[] offsetX,
        boolean[] justifiable,
        float[] lineHeight,
        int[] lineStart
) {

    /** Shared empty instance for zero-glyph layouts (e.g. an empty-string layout). */
    public static final CgBakedGlyphs EMPTY = new CgBakedGlyphs(
            0, new CgFontKey[0], new CgFont[0], new int[0],
            new float[0], new float[0], new float[0], new boolean[0],
            new float[0], new int[]{0});
}
