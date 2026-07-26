package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFontMetrics;

import java.util.List;

/**
 * Immutable result of the text layout pipeline.
 *
 * <p>A {@code CgTextLayout} contains the final laid-out text: lines of shaped runs
 * in visual order, total dimensions, the font metrics used for layout, and a
 * {@link CgBakedGlyphs baked flat glyph structure} pre-computed once by the layout
 * engine. This is the output consumed by the text renderer for drawing.</p>
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
 *   <li>{@code baked} — flat per-glyph pen positions/font identity/line metadata;
 *       see {@link CgBakedGlyphs}. Consumers that need to draw or measure every glyph
 *       should read this instead of re-walking {@link #lines}.</li>
 * </ul>
 *
 * <h3>Immutability</h3>
 * <p>This class is immutable (Lombok {@code @Value}). The inner lists should not be
 * modified after construction — defensive copies are recommended if the caller retains
 * mutable references.</p>
 *
 * <h3>Public Text API</h3>
 * <p>This type lives in {@code api/text} because it is a <strong>public domain
 * concept</strong> — the canonical output of text layout that external callers
 * receive and pass to the renderer. Internal pipeline machinery (shaping,
 * line-breaking, layout engine) produces this type but does not own it.</p>
 *
 * @see CgShapedRun
 * @see CgBakedGlyphs
 * @param lines  Lines of shaped runs in visual order. Outer = lines, inner = runs. 
 * @param totalWidth  Width of the widest line in logical layout pixels. 
 * @param totalHeight  Total height of all lines in logical layout pixels. 
 * @param metrics  Font metrics used during layout. 
 * @param baked  Flat, per-glyph baked layout data; see {@link CgBakedGlyphs}. 
 */
public record CgTextLayout(List<List<CgShapedRun>> lines, float totalWidth, float totalHeight, CgFontMetrics metrics, 
                           CgBakedGlyphs baked) {

    /**
     * Full constructor including baked glyph data for render-time consumption.
     *
     * @param lines       lines of shaped runs in visual order
     * @param totalWidth  width of the widest line in logical layout pixels
     * @param totalHeight total height of all lines in logical layout pixels
     * @param metrics     font metrics used during layout
     * @param baked       flat per-glyph baked layout data
     */
    public CgTextLayout {}

    /**
     * Convenience constructor that defaults {@link #baked} to {@link CgBakedGlyphs#EMPTY}.
     *
     * <p>Use this for test fixtures or metrics-only queries that never read baked
     * glyph data.</p>
     */
    public CgTextLayout(List<List<CgShapedRun>> lines, float totalWidth, float totalHeight, CgFontMetrics metrics) {
        this(lines, totalWidth, totalHeight, metrics, CgBakedGlyphs.EMPTY);
    }
}
