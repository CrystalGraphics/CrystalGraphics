package io.github.somehussar.crystalgraphics.api.font;

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
 * </ul>
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
}
