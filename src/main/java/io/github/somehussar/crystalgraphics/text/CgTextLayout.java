package io.github.somehussar.crystalgraphics.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontMetrics;
import lombok.Value;

import java.util.List;

/**
 * Immutable result of the text layout pipeline.
 *
 * <p>A {@code CgTextLayout} contains the final laid-out text: lines of shaped runs
 * in visual order, total dimensions, and the font metrics used for layout. This is
 * the output consumed by the text renderer for drawing.</p>
 *
 * <h3>Structure</h3>
 * <ul>
 *   <li>{@code lines} — outer list = lines (top to bottom), inner list = runs in visual
 *       order (left to right after BiDi reordering). Each {@link CgShapedRun} contains
 *       glyph IDs, advances, and offsets in pixels.</li>
 *   <li>{@code totalWidth} — width of the widest line in pixels.</li>
 *   <li>{@code totalHeight} — sum of all line heights in pixels.</li>
 *   <li>{@code metrics} — the font metrics used during layout (ascender, descender,
 *       line height).</li>
 * </ul>
 *
 * <h3>Immutability</h3>
 * <p>This class is immutable (Lombok {@code @Value}). The inner lists should not be
 * modified after construction — defensive copies are recommended if the caller retains
 * mutable references.</p>
 *
 * @see CgTextShaper
 * @see CgLineBreaker
 * @see CgShapedRun
 */
@Value
public class CgTextLayout {

    /** Lines of shaped runs in visual order. Outer = lines, inner = runs. */
    List<List<CgShapedRun>> lines;

    /** Width of the widest line in logical layout pixels. */
    float totalWidth;

    /** Total height of all lines in logical layout pixels. */
    float totalHeight;

    /** Font metrics used during layout. */
    CgFontMetrics metrics;
}
