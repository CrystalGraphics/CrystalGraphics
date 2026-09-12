package com.crystalgraphics.api.text;

/**
 * Where a text stroke sits relative to the glyph's own outline.
 *
 * <p>The distinction is not cosmetic. A stroke has width, and the outline is a line with no
 * thickness, so something has to decide which side of it the width is spent on — and the answer
 * changes how heavy the letterform reads at the same nominal width.</p>
 *
 * <pre>{@code
 * CENTER  half outside, half inside   the glyph gets THINNER as the stroke grows
 * OUTSET  all outside                 the glyph keeps its weight, the text grows
 * INSET   all inside                  the glyph keeps its footprint, the counters close
 * }</pre>
 *
 * <p>{@link #CENTER} is what {@code -webkit-text-stroke} does and therefore what the web is used
 * to; it is also the reason web authors reach for {@code paint-order: stroke fill}, which hides
 * the inner half behind the fill and approximates {@link #OUTSET} without saying so. This engine
 * defaults to {@link #OUTSET} instead, since it can simply offer the thing they were approximating.
 */
public enum CgStrokeAlign {
    /** Half the width outside the outline, half inside. {@code -webkit-text-stroke}'s behaviour. */
    CENTER,
    /** The whole width outside the outline. The glyph's own weight is untouched. */
    OUTSET,
    /** The whole width inside the outline. The glyph's footprint is untouched. */
    INSET
}
