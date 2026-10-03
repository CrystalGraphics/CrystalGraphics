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
}
