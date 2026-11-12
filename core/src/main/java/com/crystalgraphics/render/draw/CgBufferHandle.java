package com.crystalgraphics.render.draw;

/**
 * A buffer a binding snapshot names now and binds by its GL name when the draw executes: a frame graph's buffer,
 * whose storage exists only while the graph resolves it.
 *
 * <pre>{@code
 * int bindings = table.begin()
 *         .storage(CELLS_POINT, cells)      // a CgGraphBuffer a kernel wrote this frame
 *         .end();
 * }</pre>
 */
public interface CgBufferHandle {

    /** Render thread: the GL buffer it reads now, or 0 outside the passes that resolve it. */
    int bufferId();

    /**
     * Render thread: {@link #bufferId()} as a draw's binding resolves it. A frame graph's buffer refuses here, where its
     * undeclared-access check is on, outside the steps that recorded it.
     */
    default int boundId() {
        return bufferId();
    }
}
