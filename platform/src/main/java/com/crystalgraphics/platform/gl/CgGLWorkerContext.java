package com.crystalgraphics.platform.gl;

/**
 * A GL context of its own, kept current on one worker thread: what the GL device's programs are compiled and linked
 * on, so their commands and status queries queue on its context and never on the render thread's. Made by
 * {@code CgGLDriver.createIsolatedContext()}.
 *
 * <pre>{@code
 * CgGLWorkerContext context = driver.createIsolatedContext();   // render thread, its context current; null: none
 * worker = new Thread(() -> {
 *     context.makeCurrent();
 *     try { ... the driver's calls on programs and shaders ... } finally { context.release(); }
 * });
 * // render thread, once the worker has ended:
 * context.destroy();
 * }</pre>
 *
 * <ul>
 *   <li>It shares nothing: a program crosses to the render thread as a program binary.</li>
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
