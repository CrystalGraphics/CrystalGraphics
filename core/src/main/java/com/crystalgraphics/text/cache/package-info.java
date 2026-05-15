/**
 * Font cache and glyph generation — atlas management, rasterization scheduling,
 * and cache-key types.
 *
 * <p>This package owns the <em>supply side</em> of the text pipeline: everything
 * that turns a {@link com.crystalgraphics.api.font.CgGlyphKey}
 * into atlas-resident pixel data, ready for the renderer to consume. Residents:</p>
 * <ul>
 *   <li>{@link com.crystalgraphics.text.cache.CgFontRegistry} —
 *       render-thread cache façade (atlas allocation, glyph lookup/generation)</li>
 *   <li>{@link com.crystalgraphics.text.msdf.CgMsdfGenerator} —
 *       MSDF glyph generation via msdfgen-java</li>
 *   <li>{@link com.crystalgraphics.text.cache.CgGlyphGenerationExecutor} —
 *       background thread pool for async glyph rasterization</li>
 *   <li>{@link com.crystalgraphics.text.cache.CgGlyphGenerationJob} /
 *       {@link com.crystalgraphics.text.cache.CgGlyphGenerationResult} —
 *       work-item types for the async pipeline</li>
 *   <li>{@link com.crystalgraphics.text.cache.CgWorkerFontContext} —
 *       per-worker FreeType face pool for thread-safe rasterization</li>
 *   <li>{@link com.crystalgraphics.text.cache.CgRasterFontKey} /
 *       {@link com.crystalgraphics.text.cache.CgRasterGlyphKey} /
 *       {@link com.crystalgraphics.text.cache.CgMsdfAtlasKey} —
 *       composite cache keys for effective-size-aware atlas buckets</li>
 * </ul>
 *
 * <h3>Boundary with rendering</h3>
 * <p>The renderer ({@link com.crystalgraphics.text.render.CgTextRenderer})
 * calls into {@link com.crystalgraphics.text.cache.CgFontRegistry}
 * to obtain {@link com.crystalgraphics.api.font.CgGlyphPlacement}
 * records. The cache owns atlas textures and generation scheduling; the renderer
 * only consumes the placement data and texture IDs.</p>
 *
 * <h3>Boundary with atlas storage</h3>
 * <p>{@link com.crystalgraphics.text.atlas.CgGlyphAtlas} (parent package)
 * provides the single-page atlas texture. Multi-page allocation is handled by
 * {@link com.crystalgraphics.text.atlas.CgPagedGlyphAtlas}.</p>
 *
 * @see com.crystalgraphics.gl.text.render
 * @see com.crystalgraphics.gl.text.atlas
 */
package com.crystalgraphics.text.cache;
