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
 * <h3>{@code argbColor} — override color, {@code 0} = inherit the draw's default</h3>
 * <p>Copied from each glyph's originating {@code CgShapedRun.getArgbColor()} (itself
 * copied from the {@code CgStyleSpan} that produced the run — see phases 8/9). {@code 0}
 * means the glyph has no per-span color override and should render with whatever default
 * color the draw call specifies; a renderer resolves the effective color as
 * {@code argbColor[i] != 0 ? argbColor[i] : draw.rgba}.</p>
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
 *
 * <h3>{@code decorations} — baked underline/strikethrough rectangles</h3>
 * <p>One {@link CgTextDecorationRect} per contiguous {@link CgShapedRun} whose
 * active {@link CgTextDecoration} on {@link CgShapedRun#getDecorations()} — one rect per
 * simultaneously-active decoration on a run, not just one per run — see
 * {@link CgTextDecorationRect} for the formulas. Empty for layouts with no decorated
 * spans (the common case).</p>
 *
 * <h3>{@code syntheticBold}/{@code syntheticItalic} — per-glyph faux-style flags</h3>
 * <p>Copied from each glyph's originating {@link CgShapedRun#isSyntheticBold()}/
 * {@link CgShapedRun#isSyntheticItalic()} — {@code true} when that run's requested style had
 * no distinct face and needs a rasterizer-side embolden/shear instead. Read by
 * {@code CgResolvedGlyphs} when building each glyph's {@code CgGlyphKey}, so synthesized and
 * plain-regular glyphs never collide in the atlas.</p>
 */
public record CgBakedGlyphs(
        int glyphCount,
        CgFontKey[] fontKeys,
        CgFont[] fonts,
        int[] glyphIds,
        float[] penX,
        float[] penY,
        float[] offsetX,
        int[] argbColor,
        boolean[] justifiable,
        float[] lineHeight,
        int[] lineStart,
        CgTextDecorationRect[] decorations,
        boolean[] syntheticBold,
        boolean[] syntheticItalic
) {

    /** Shared empty instance for zero-glyph layouts (e.g. an empty-string layout). */
    public static final CgBakedGlyphs EMPTY = new CgBakedGlyphs(
            0, new CgFontKey[0], new CgFont[0], new int[0],
            new float[0], new float[0], new float[0], new int[0], new boolean[0],
            new float[0], new int[]{0}, CgTextDecorationRect.NONE, new boolean[0], new boolean[0]);
}
