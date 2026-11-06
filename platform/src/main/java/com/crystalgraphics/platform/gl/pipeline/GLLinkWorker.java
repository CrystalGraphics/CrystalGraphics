package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Compiles and links the GL device's programs on a thread of its own, in a context sharing nothing, and hands each over
 * as a program binary the render thread loads in about a millisecond: the engine's link worker (Dolphin's shader
 * compiler thread; Unreal compiles GL programs off its RHI thread, `OpenGLShaders.cpp:2743`), moved into the device.
 * Sharing nothing, because NVIDIA holds the render thread's GL calls while a context sharing its objects compiles.
 *
 * <pre>{@code
 * device.linkWorker();          // render thread, before the first pipeline; no-op where the driver gives no context
 * CgGraphicsPipeline p = device.createPipeline(desc);   // returns at once; the program links on the worker
 * pass.setPipeline(p);          // the first draw waits for it, if it has not landed
 * }</pre>
 *
 * <ul>
 *   <li>Only where the driver lists binary formats: a program crosses as a binary.</li>
 *   <li>{@link #stop} on the render thread, which then destroys the context.</li>
 * </ul>
 */
public final class GLLinkWorker {

    private final LinkedBlockingQueue<GLProgramBuild> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean stopping;

    /** @param gl the driver the worker calls, with {@code context} current on its thread */
    public GLLinkWorker(CgGLDriver gl, CgGLWorkerContext context) {
        thread = new Thread(() -> {
            context.makeCurrent();
            try {
                while (!stopping) {
                    GLProgramBuild job;
                    try {
                        job = queue.take();
                    } catch (InterruptedException e) {
                        continue;
                    }
                    job.run(gl);
                }
            } finally {
                context.release();
            }
        }, "crystalgraphics-gl-link");
        thread.setDaemon(true);
        thread.start();
    }

    public void submit(GLProgramBuild job) {
        queue.add(job);
    }

    /** Ends the thread once its current job is done; jobs not started fail to their waiters. */
    public void stop() {
        stopping = true;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (GLProgramBuild job; (job = queue.poll()) != null; ) job.cancel();
    }
}
