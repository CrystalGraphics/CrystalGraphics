package io.github.somehussar.crystalgraphics.gl.render;

/**
 * Base class for batch renderers, providing shared lifecycle boilerplate.
 *
 * <p>Eliminates the duplicate {@code begun} field and identical
 * {@link #begin()}/{@link #end()}/{@link #isDirty()}/{@link #delete()} bodies
 * that previously existed in both {@link CgBatchRenderer} and
 * {@link CgInstancedBatchRenderer}.</p>
 *
 * <p>Subclasses implement {@link #onBegin()} to reset their staging buffers
 * and {@link #hasPendingWork()} to report dirtiness. The {@link #flush()}
 * method is intentionally left abstract in the subclass (not forced here)
 * because its signature differs between renderers.</p>
 */
public abstract class AbstractBatchRenderer implements IBatchRenderer {

    /** True between a successful {@link #begin()} and {@link #end()} call. */
    protected boolean begun;

    @Override
    public final void begin() {
        if (begun) throw new IllegalStateException(getClass().getSimpleName() + " already begun");
        begun = true;
        onBegin();
    }

    /**
     * Called immediately after {@code begun} is set to {@code true}.
     * Subclasses should reset their staging buffers here.
     */
    protected abstract void onBegin();

    @Override
    public final void end() {
        begun = false;
    }

    @Override
    public final boolean isDirty() {
        return begun && hasPendingWork();
    }

    /**
     * Returns {@code true} if there is vertex or instance data staged
     * but not yet flushed to the GPU.
     */
    protected abstract boolean hasPendingWork();

    @Override
    public void delete() {
        // Default: CPU staging only; GPU resources owned by registries.
        // Subclasses may override to release CPU resources.
    }
}
