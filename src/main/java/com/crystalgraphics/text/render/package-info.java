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
 * <p>All text submission goes through
 * {@link com.crystalgraphics.gl.render.CgDynamicTextureRenderLayer}
 * from the batching/layer architecture. GL state management is delegated to
 * the layer's {@code CgRenderState}. Text layer factories are provided by
 * {@link com.crystalgraphics.text.render.CgTextLayers}.</p>
 *
 * <p>No per-renderer GL object ownership exists in this package — all GPU resources
 * are managed by {@link com.crystalgraphics.gl.vertex.CgVertexArrayRegistry}
 * and {@link com.crystalgraphics.gl.buffer.CgQuadIndexBuffer}.</p>
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
 * @see io.github.somehussar.crystalgraphics.gl.text.cache
 * @see io.github.somehussar.crystalgraphics.gl.text.atlas
 */
package com.crystalgraphics.text.render;
