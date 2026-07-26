package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFontKey;

/**
 * A single baked underline/strikethrough/overline rectangle, in the same layout-origin-relative
 * coordinate space as {@link CgBakedGlyphs#penX()}/{@link CgBakedGlyphs#penY()} — one per
 * contiguous {@link CgShapedRun}'s {@link CgShapedRun#getDecorations()} — one per
 * simultaneously-active decoration, so an underlined-and-struck-through run produces two
 * of these, not one.
 *
 * <h3>Formulas</h3>
 * <p>Thickness and vertical offset follow Blink's fallback formulas
 * ({@code text_decoration_offset.cc}/{@code font_metrics.h}) — the same ones browsers fall
 * back to when a font's own {@code post} table underline metrics aren't consulted:</p>
 * <ul>
 *   <li>{@code thickness = fontSizePx / 10}</li>
 *   <li>underline {@code y = baseline + max(1, ceil(thickness / 2))} (below baseline)</li>
 *   <li>strikethrough {@code y = baseline - xHeight / 2} (centered on the visual middle of
 *       lowercase letters, not a fraction of the full ascent — that overshoots into cap-height/
 *       ascender territory for fonts with generous ascent headroom)</li>
 *   <li>overline {@code y = baseline - ascent + thickness / 2} (at the top of the em box)</li>
 * </ul>
 *
 * @param x0        left edge, layout-origin-relative
 * @param x1        right edge, layout-origin-relative
 * @param y         vertical center of the decoration line, layout-origin-relative
 *                  (baseline + downward offset, matching {@link CgBakedGlyphs#penY()}'s
 *                  down-positive convention)
 * @param thickness line thickness in pixels
 * @param argbColor resolved color for this segment (run override, or the draw's default)
 * @param fontKey   the run's font — lets the renderer sample this segment's flat fill from
 *                  the exact atlas/page this run's own glyphs are already resident on
 */
public record CgTextDecorationRect(float x0, float x1, float y, float thickness, int argbColor, CgFontKey fontKey) {

    /** Shared empty array for layouts with no decorated runs. */
    public static final CgTextDecorationRect[] NONE = new CgTextDecorationRect[0];
}
