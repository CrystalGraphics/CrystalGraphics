/**
 * Text rendering pipeline — draw-time orchestration, batching, and GL submission.
 *
 * <p>This package owns the draw-side of the text pipeline: everything from
 * receiving a {@link com.crystalgraphics.api.text.CgTextLayout}
 * through to the final {@code glDrawElements} calls. Residents:</p>
 * <ul>
 *   <li>{@link com.crystalgraphics.text.render.CgTextRenderer} —
 *       top-level render façade (layout → placements → sort → layer submit → draw)</li>
 *   <li>{@link com.crystalgraphics.text.render.CgTextRenderContext} /
 *       {@link com.crystalgraphics.text.render.CgWorldTextRenderContext} —
 *       projection and scale-resolver state</li>
 *   <li>{@link com.crystalgraphics.text.render.CgTextScaleResolver} —
 *       effective raster tier resolution (orthographic, world-space)</li>
 *   <li>{@link com.crystalgraphics.text.render.ProjectedSizeEstimator} —
 *       MVP-based screen pixel coverage estimation for world-text raster tier</li>
 *   <li>{@link com.crystalgraphics.text.render.CgDrawBatchKey} —
 *       GL-state grouping key for sorted quad submission</li>
 * </ul>
 *
 * <h3>Batch Infrastructure</h3>
 * <p>{@link com.crystalgraphics.text.render.CgTextRenderer} owns a private
 * {@code CgBatchRenderer} directly — no caller-provided layer or buffer source is
 * required. {@code beginFrame()}/{@code endFrame()} optionally batch multiple draws
 * per frame together; {@code draw()}/{@code drawWorld()} otherwise auto-wrap
 * themselves standalone. GL state (shader bind/unbind, texture bind/unbind,
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
