/**
 * Public text API — domain types for the text layout pipeline.
 *
 * <p>This package contains the <strong>public-facing</strong> data types that
 * callers interact with when using CrystalGraphics text rendering:</p>
 * <ul>
 *   <li>{@link com.crystalgraphics.api.text.CgTextLayout} —
 *       immutable layout result (lines of shaped runs + metrics + baked glyphs)</li>
 *   <li>{@link com.crystalgraphics.api.text.CgBakedGlyphs} —
 *       flat, per-glyph pen positions/font identity/line metadata for a layout</li>
 *   <li>{@link com.crystalgraphics.api.text.CgShapedRun} —
 *       shaped glyph data for a single directional run</li>
 * </ul>
 *
 * <h3>Boundary rule</h3>
 * <p>Types in this package are <strong>public domain concepts</strong> that
 * external callers receive from or pass to the layout/render pipeline.
 * Internal pipeline machinery (shaping, line-breaking, atlas, generation)
 * lives in {@code text/} and {@code gl/text/} — those packages <em>produce</em>
 * these types but do not own them.</p>
 *
 * <p>Runtime transport structures (e.g., {@code CgGlyphPlacement})
 * remain in {@code api/font} or closer to their runtime consumers
 * in {@code gl/text/}.</p>
 *
 * <h3>Resolved API-boundary leaks</h3>
 * <p>Two internal-state leaks previously documented here have been removed:</p>
 * <ul>
 *   <li>{@code CgTextLayout.resolvedFontsByKey} — removed. Every
 *       {@link com.crystalgraphics.api.text.CgShapedRun} now carries its own resolved
 *       {@code CgFont} directly ({@link com.crystalgraphics.api.text.CgShapedRun#getResolvedFont()}),
 *       and {@link com.crystalgraphics.api.text.CgBakedGlyphs} carries one per baked glyph —
 *       no separate font-by-key map is needed.</li>
 *   <li>{@code CgShapedRun.sourceText} — removed. The paragraph text a run was shaped
 *       from is now supplied externally, once per paragraph, via
 *       {@code text.layout.CgReshapeContext} rather than copied onto every run.
 *       {@code CgShapedRun} still carries {@code sourceStart}/{@code sourceEnd} (its
 *       position within that external text), needed for intra-run re-shaping.</li>
 * </ul>
 *
 * @see com.crystalgraphics.api.font
 */
package com.crystalgraphics.api.text;
