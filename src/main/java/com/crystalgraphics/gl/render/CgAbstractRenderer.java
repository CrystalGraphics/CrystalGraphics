package com.crystalgraphics.gl.render;

/**
 * Base class for batch renderers, providing shared lifecycle boilerplate.
 *
 * <p>Eliminates the duplicate {@code begun} field and identical
 * {@link #begin()}/{@link #end()}/{@link #isDirty()}/{@link #delete()} bodies
 * across all batch renderer subclasses ({@link CgInstanceRenderer},
 * {@link CgQuadInstanceRenderer}, etc.).</p>
 *
 * <p>Subclasses implement {@link #onBegin()} to reset their staging buffers
 * and {@link #hasPendingWork()} to report dirtiness. The {@link #flush()}
 * method is intentionally left abstract in the subclass (not forced here)
 * because its signature differs between renderers.</p>
 */
public abstract class CgAbstractRenderer {

    /** True between a successful {@link #begin()} and {@link #end()} call. */
    protected boolean begun;

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

    public final void end() {
        onEnd();
        begun = false;
    }

    /**
     * Called at the start of {@link #end()}, before {@code begun} is set to {@code false}.
     * Subclasses may override to flush remaining data or release per-frame GL bindings.
     * Default implementation is a no-op.
     */
    protected void onEnd() {}

    public final boolean isDirty() {
        return begun && hasPendingWork();
    }

    /**
     * Returns {@code true} if there is vertex or instance data staged
     * but not yet flushed to the GPU.
     */
    protected abstract boolean hasPendingWork();

    public void delete() {
        // Default: CPU staging only; GPU resources owned by registries.
        // Subclasses may override to release CPU resources.
    }

    /** Uploads staged data and issues a draw call. State-blind. */
    public abstract void flush();
}
