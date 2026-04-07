/**
 * Public text API — domain types for the text layout pipeline.
 *
 * <p>This package contains the <strong>public-facing</strong> data types that
 * callers interact with when using CrystalGraphics text rendering:</p>
 * <ul>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.text.CgTextLayout} —
 *       immutable layout result (lines of shaped runs + metrics)</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.text.CgTextConstraints} —
 *       layout bounds (max width / max height)</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.text.CgShapedRun} —
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
 * <p>Runtime transport structures (e.g., {@code CgGlyphPlacement},
 * {@code CgAtlasRegion}) remain in {@code api/font} or closer to
 * their runtime consumers in {@code gl/text/}.</p>
 *
 * <h3>Known API-boundary leaks</h3>
 * <p>Two groups of fields currently expose internal pipeline state through these
 * public types. They are acknowledged as temporary and documented in-place:</p>
 * <ul>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.text.CgTextLayout#getResolvedFontsByKey()
 *       CgTextLayout.resolvedFontsByKey} — heavyweight {@code CgFont} handles needed
 *       by the renderer at draw time; should migrate to a render-context once one exists.</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.api.text.CgShapedRun#getSourceText()
 *       CgShapedRun.sourceText} /
 *       {@link io.github.somehussar.crystalgraphics.api.text.CgShapedRun#getSourceStart()
 *       sourceStart} /
 *       {@link io.github.somehussar.crystalgraphics.api.text.CgShapedRun#getSourceEnd()
 *       sourceEnd} — original input text retained for intra-run re-shaping;
 *       should migrate to an internal wrapper or re-shaping context.</li>
 * </ul>
 *
 * @see io.github.somehussar.crystalgraphics.api.font
 */
package io.github.somehussar.crystalgraphics.api.text;
