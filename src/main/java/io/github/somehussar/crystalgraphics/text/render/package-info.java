/**
 * Text rendering pipeline — draw-time orchestration, batching, and GL submission.
 *
 * <p>This package owns the draw-side of the text pipeline: everything from
 * receiving a {@link io.github.somehussar.crystalgraphics.api.text.CgTextLayout}
 * through to the final {@code glDrawElements} calls. Residents:</p>
 * <ul>
 *   <li>{@link io.github.somehussar.crystalgraphics.text.render.CgTextRenderer} —
 *       top-level render façade (layout → placements → sort → batch → draw)</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.text.render.CgTextRenderContext} /
 *       {@link io.github.somehussar.crystalgraphics.text.render.CgWorldTextRenderContext} —
 *       projection and scale-resolver state</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.text.render.CgTextScaleResolver} —
 *       effective raster tier resolution (orthographic, world-space)</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.text.render.ProjectedSizeEstimator} —
 *       MVP-based screen pixel coverage estimation for world-text raster tier</li>
 *   <li>{@link io.github.somehussar.crystalgraphics.text.render.CgDrawBatchKey} —
 *       GL-state grouping key for sorted quad submission</li>
 * </ul>
 *
 * <h3>Batch Infrastructure</h3>
 * <p>The renderer submits quads through the shared
 * {@link io.github.somehussar.crystalgraphics.gl.batch.CgQuadBatcher} infrastructure.
 * The batch handles GPU buffer management, VAO/VBO ownership, and automatic
 * flushing on shader/texture state changes. No per-renderer GL object ownership
 * exists in this package — all GPU resources are managed by
 * {@link io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry} and
 * {@link io.github.somehussar.crystalgraphics.gl.buffer.CgQuadIndexBuffer}.</p>
 *
 * <h3>Boundary with cache/generation</h3>
 * <p>The renderer calls into
 * {@link io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry} (in the
 * {@code gl.text.cache} package) for glyph cache resolution. The registry owns
 * atlas allocation, generation scheduling, and cache key types — the renderer
 * only consumes the resulting
 * {@link io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement} records.</p>
 *
 * @see io.github.somehussar.crystalgraphics.text.cache.CgFontRegistry
 * @see io.github.somehussar.crystalgraphics.gl.text.cache
 * @see io.github.somehussar.crystalgraphics.gl.text.atlas
 */
package io.github.somehussar.crystalgraphics.text.render;
