/**
 * Text rendering pipeline — draw-time orchestration, batching, and GL submission.
 *
 * <p>This package owns the draw-side of the text pipeline: everything from
 * receiving a {@link com.crystalgraphics.api.text.CgTextLayout}
 * through to the final {@code glDrawElements} calls. Residents:</p>
 * <ul>
 *   <li>{@link com.crystalgraphics.text.render.CgTextRenderer} —
 *       top-level render façade (layout → placements → sort → layer submit → draw)</li>
 *   <li>{@link com.crystalgraphics.text.render.context.CgTextRenderContext} —
 *       projection and scale-resolver state, for both 2D UI and 3D world text
 *       (no separate world-space context class; see its class javadoc)</li>
 *   <li>{@link com.crystalgraphics.text.render.context.CgTextScaleResolver} —
 *       effective raster tier resolution (orthographic, world-space)</li>
 *   <li>{@link com.crystalgraphics.text.render.context.ProjectedSizeEstimator} —
 *       MVP-based screen pixel coverage estimation for world-text raster tier</li>
 *   <li>{@link com.crystalgraphics.text.render.CgResolvedGlyphs} —
 *       resolves one draw's layout into flat per-glyph atlas placements</li>
 * </ul>
 *
 * <h3>Batch Infrastructure</h3>
 * <p>{@link com.crystalgraphics.text.render.CgTextRenderer} owns a private
 * {@code CgBatchRenderer} directly — no caller-provided layer or buffer source is
 * required. {@code beginBatch()}/{@code endBatch()} optionally batch multiple draws
 * together; {@code draw()} otherwise auto-wraps itself standalone. There is a single
 * {@code draw()} entry point for both 2D UI text and 3D world-space text — which
 * {@code CgTextScaleResolver} strategy the {@code CgTextRenderContext} argument holds
 * (built via {@code orthographic(...)} vs {@code world(...)}) determines which
 * behavior applies; there is no separate world-space context class. GL state
 * (shader bind/unbind, texture bind/unbind,
 * {@code CgRenderState} apply/clear) is managed directly by the renderer on
 * batch-key transitions.
 *
 * <p>No per-renderer GPU object ownership exists in this package — the owned
 * batch renderer's VAO/VBO/IBO still come from
 * {@link com.crystalgraphics.gl.vertex.CgVertexArrayRegistry}
 * and {@link com.crystalgraphics.gl.buffer.CgQuadIndexBuffer}; only CPU-side
 * staging is renderer-owned.</p>
 *
 * <h3>Boundary with cache/generation</h3>
 * <p>The renderer calls into
 * {@link com.crystalgraphics.text.cache.CgFontRegistry} (in the
 * {@code gl.text.cache} package) for glyph cache resolution. The registry owns
 * atlas allocation, generation scheduling, and cache key types — the renderer
 * only consumes the resulting
 * {@link com.crystalgraphics.api.font.CgGlyphPlacement} records.</p>
 *
 * @see com.crystalgraphics.text.cache.CgFontRegistry
 * @see com.crystalgraphics.gl.text.cache
 * @see com.crystalgraphics.gl.text.atlas
 */
package com.crystalgraphics.text.render;
