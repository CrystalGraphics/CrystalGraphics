package com.crystalgraphics.platform.gl;

/**
 * A GL context sharing the render thread's objects, kept current on one worker thread: what programs are compiled and
 * linked on, so their commands and status queries queue on its context and never on the render thread's. Made by
 * {@link CgGL#createWorkerContext()}.
 *
 * <pre>{@code
 * CgGLWorkerContext context = CgGL.createWorkerContext();   // render thread, its context current; null: none
 * worker = new Thread(() -> {
 *     context.makeCurrent();
 *     try { ... CgGL calls on programs and shaders ... } finally { context.release(); }
 * });
 * // render thread, once the worker has ended:
 * context.destroy();
 * }</pre>
 *
 * <ul>
 *   <li>Only objects a share group shares cross it: programs, shaders, buffers, textures. Never bind a VAO or a
 *       framebuffer on it.</li>
 *   <li>An object it changed is complete for the render thread only after {@code glFinish} on it.</li>
 *   <li>{@link #destroy} on the render thread, after {@link #release} on the worker: GLFW destroys windows on the main
 *       thread alone.</li>
 * </ul>
 */
public interface CgGLWorkerContext {

    /** Makes it current on the calling thread. Once, on the worker that keeps it. */
    void makeCurrent();

    /** Makes no context current on the calling thread. On the worker, as it ends. */
    void release();

    /** Frees it. On the render thread, once the worker released it. */
    void destroy();
}
