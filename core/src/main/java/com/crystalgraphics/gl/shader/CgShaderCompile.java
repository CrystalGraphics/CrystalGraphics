package com.crystalgraphics.gl.shader;

import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * A shader compile under way: on a job worker where the installed {@link CgGlslCompiler} compiles on any thread, else
 * run by the first thread that asks for it (the native compiler, which needs the device's context).
 *
 * <pre>{@code
 * CgShaderCompile<CgGlslCompiler.Explicit> c = CgShaderCompile.start(() -> compiler.compileExplicit(vs, fs, attribs, "stone"));
 * // later frames:
 * if (c.isDone()) explicit = c.get();   // throws as the compile threw
 * }</pre>
 *
 * <ul>
 *   <li>{@link #get} runs a compile no worker has taken yet, and waits for one a worker is running.</li>
 *   <li>{@link #isDone} is true for a compile no worker will run: {@link #get} then compiles on the caller.</li>
 * </ul>
 */
public final class CgShaderCompile<T> {

    private static final int COMPILE = CgTrace.name("shader.compile");

    private final FutureTask<T> task;
    private final boolean queued;

    private CgShaderCompile(FutureTask<T> task, boolean queued) {
        this.task = task;
        this.queued = queued;
    }

    /** Starts {@code compile} on a worker where the installed compiler allows it; otherwise it waits for {@link #get}. */
    public static <T> CgShaderCompile<T> start(Callable<T> compile) {
        FutureTask<T> task = new FutureTask<>(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE)) {
                return compile.call();
            }
        });
        boolean queued = CgGlslCompiler.installed() && CgGlslCompiler.get().compilesOnAnyThread();
        if (queued) CgJob.executor(CgJobLane.LATENCY).execute(task);
        return new CgShaderCompile<>(task, queued);
    }

    /** Whether {@link #get} would return without waiting for a worker. */
    public boolean isDone() {
        return !queued || task.isDone();
    }

    /** The compile's result, compiling here if no worker took it. Rethrows what the compile threw. */
    public T get() {
        task.run();
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a shader compile", e);
        }
    }
}
