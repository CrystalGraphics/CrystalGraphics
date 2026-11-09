/**
 * Text as a canvas item: {@code canvas.text()} returns a {@link com.crystalgraphics.text.render.CgTextDraw}, and its
 * {@code submit()} has the canvas's {@link com.crystalgraphics.text.render.CgTextBatch} resolve the layout's glyphs
 * from the font registry's atlases, plan its shadows and land its quads on the canvas in paint order.
 *
 * <ul>
 *   <li>{@link com.crystalgraphics.text.render.CgTextRaster} — the size glyphs are rastered at, and bitmap or
 *       distance field</li>
 *   <li>{@link com.crystalgraphics.text.render.CgResolvedGlyphs} — a draw's layout as flat per-glyph placements</li>
 *   <li>{@link com.crystalgraphics.text.render.CgTextShadowPlan} — what each glyph paints in each shadow</li>
 *   <li>{@link com.crystalgraphics.text.render.CgTextMaterials} — {@code text.shader}'s fixed instances</li>
 *   <li>{@link com.crystalgraphics.text.render.CgTextCapture} — a world label's records kept on the GPU</li>
 * </ul>
 *
 * <p>No GPU object is owned here: quads go into the canvas's stream. Glyph supply, atlases and cache keys are
 * {@link com.crystalgraphics.text.cache.CgFontRegistry}'s.</p>
 */
package com.crystalgraphics.text.render;
