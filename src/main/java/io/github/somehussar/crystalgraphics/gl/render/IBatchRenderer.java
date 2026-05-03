package io.github.somehussar.crystalgraphics.gl.render;

/**
 * Common contract for all batch renderers managed by {@link CgRenderLayer}.
 *
 * <p>Extracting this interface eliminates the need for per-renderer layer classes.
 * {@link CgRenderLayer} accepts any {@code IBatchRenderer}, so
 * {@link CgInstancedBatchRenderer} (and any future renderer) can be wrapped by
 * {@code CgRenderLayer} directly without creating a new layer class.</p>
 *
 * <p>Implementations: {@link CgBatchRenderer}, {@link CgInstancedBatchRenderer}.</p>
 */
public interface IBatchRenderer {
    /** Opens the recording phase. Resets staging. */
    void begin();
    /** Uploads staged data and issues a draw call. State-blind. */
    void flush();
    /** Closes the recording phase. */
    void end();
    /** Returns true if there is unstaged data pending a flush. */
    boolean isDirty();
    /** Releases CPU-side resources. GPU resources owned by registries. */
    void delete();
}
