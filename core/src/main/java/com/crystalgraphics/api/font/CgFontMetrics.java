package com.crystalgraphics.api.font;

import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * Immutable font-level metrics for a registered font at a specific pixel size.
 *
 * <p>All values are in pixels, derived from the font's OS/2 and hhea tables
 * after scaling to the target pixel size. These metrics are constant for a
 * given {@link CgFontKey} and are used by the text layout engine to compute
 * line heights and baseline positioning.</p>
 *
 * <h3>Metric definitions</h3>
 * <ul>
 *   <li>{@code ascender} — distance from baseline to top of tallest glyph (positive)</li>
 *   <li>{@code descender} — distance from baseline to bottom of lowest glyph (positive value,
 *       representing a downward extent)</li>
 *   <li>{@code lineGap} — extra inter-line spacing recommended by the font</li>
 *   <li>{@code lineHeight} — total line advance: {@code ascender + descender + lineGap}</li>
 *   <li>{@code xHeight} — height of lowercase 'x' (useful for vertical centering)</li>
 *   <li>{@code capHeight} — height of uppercase 'H' (useful for cap-aligned layout)</li>
 *   <li>{@code underlineOffset}, {@code underlineThickness} — the underline the font asks for, from its
 *       {@code post} table: the line's CENTRE below the baseline (positive is down) and its thickness</li>
 *   <li>{@code strikeoutOffset}, {@code strikeoutThickness} — the same for a line-through, from {@code OS/2};
 *       the offset is negative, since a strikeout sits above the baseline</li>
 * </ul>
 *
 * <p>A face that declares no decoration metrics has {@code 0} thicknesses, and a caller falls back to its own
 * formula — which is why the six-argument constructor, for a face or a test that states only line metrics, leaves
 * them so.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * CgFontMetrics m = new CgFontMetrics(11.0f, 3.0f, 1.0f, 15.0f, 7.0f, 10.0f);
 * float baseline = y + m.getAscender();  // position baseline below top of text box
 * </pre>
 *
 * @see CgFontKey
 */
@Value
@AllArgsConstructor
public class CgFontMetrics {

    /** Pixels above baseline (positive). */
    float ascender;

    /** Pixels below baseline (positive value representing downward extent). */
    float descender;

    /** Extra line spacing recommended by the font. */
    float lineGap;

    /** Total line height: {@code ascender + descender + lineGap}. */
    float lineHeight;

    /** Height of lowercase 'x'. */
    float xHeight;

    /** Height of uppercase 'H'. */
    float capHeight;

    /** Centre of the font's own underline, in pixels below the baseline; meaningful only with a thickness. */
    float underlineOffset;

    /** The font's own underline thickness in pixels, or {@code 0} when it declares none. */
    float underlineThickness;

    /** Centre of the font's own strikeout, in pixels below the baseline (negative: it sits above). */
    float strikeoutOffset;

    /** The font's own strikeout thickness in pixels, or {@code 0} when it declares none. */
    float strikeoutThickness;

    /** Line metrics alone: no decoration metrics, so a caller uses its own formulas for those. */
    public CgFontMetrics(float ascender, float descender, float lineGap, float lineHeight, float xHeight,
                         float capHeight) {
        this(ascender, descender, lineGap, lineHeight, xHeight, capHeight, 0f, 0f, 0f, 0f);
    }

    /** Whether the font states its own underline. */
    public boolean hasUnderline() {
        return underlineThickness > 0f;
    }

    /** Whether the font states its own strikeout. */
    public boolean hasStrikeout() {
        return strikeoutThickness > 0f;
    }
}
