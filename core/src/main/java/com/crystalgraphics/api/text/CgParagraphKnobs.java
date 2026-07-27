package com.crystalgraphics.api.text;

/**
 * Paragraph-level layout knobs — the fixed configuration a {@link CgTextLayout.Request}
 * captures at {@code shape()} time and a {@link CgShapedParagraph} retains for every
 * subsequent {@code layout(maxWidth, maxHeight)} re-wrap.
 *
 * <h3>Shape-time vs. layout-time knobs</h3>
 * <p>{@link #direction()} and {@link #tabStopWidth()} affect BiDi analysis and paragraph
 * splitting, so they only make sense applied once, at shaping time. {@link #align()},
 * {@link #maxLines()}, {@link #ellipsisMarker()}, and {@link #lineHeightOverride()} are
 * applied fresh on every {@code layout(maxWidth, maxHeight)} call, since they depend on
 * (or at least may need to be re-evaluated against) the width/height being wrapped to.</p>
 *
 * @param direction         overall paragraph direction; {@link CgTextDirection#AUTO} preserves
 *                          today's {@link java.text.Bidi}-based auto-detection
 * @param tabStopWidth      {@code \t} expansion width in logical pixels; {@code <= 0} disables
 *                          expansion (tabs shape as whatever glyph the font has for U+0009).
 *                          <strong>v1 limitation:</strong> only implemented for the plain-text
 *                          overload ({@link CgTextLayout#of(String, com.crystalgraphics.api.font.CgFontFamily)}),
 *                          not the styled-text one; and tab-stop columns are computed assuming
 *                          the paragraph is not yet line-wrapped (a genuine simplification —
 *                          real column position after a soft-wrap is not tracked)
 * @param align             per-line horizontal alignment within the paragraph's box
 * @param maxLines          maximum visible lines; {@code <= 0} means unbounded
 * @param ellipsisMarker    marker text (e.g. {@code "…"}) to append to the last visible line
 *                          when {@code maxLines} truncates content; {@code null} means no
 *                          ellipsis (truncated content is simply dropped)
 * @param lineHeightOverride when {@code > 0}, replaces every line's computed height uniformly
 *                          instead of the real per-line max-over-metrics computation
 */
public record CgParagraphKnobs(
        CgTextDirection direction,
        float tabStopWidth,
        CgTextAlign align,
        int maxLines,
        String ellipsisMarker,
        float lineHeightOverride
) {
    public static final CgParagraphKnobs DEFAULT =
            new CgParagraphKnobs(CgTextDirection.AUTO, 0f, CgTextAlign.LEFT, 0, null, 0f);

    public CgParagraphKnobs {
        if (direction == null) direction = CgTextDirection.AUTO;
        if (align == null) align = CgTextAlign.LEFT;
    }
}
