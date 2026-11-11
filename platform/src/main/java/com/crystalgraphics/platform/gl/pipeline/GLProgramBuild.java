package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * One program's build on the {@link GLLinkWorker}: its kept binary where there is one, else translated, compiled and
 * linked on the worker's context, its binary kept; then loaded on the render thread at the first ask. A draw asking
 * for one the worker has not started links it itself, so it waits for its own link and not the queue before it
 * (Unreal's on-demand compile ahead of the background precompile).
 */
public final class GLProgramBuild implements GLProgramCache.Build {

    private final GLShaderTranslator translator;
    private final GLProgramBinaries binaries;
    private final GLBindingPoints points;
    private final String label;
    private final GLShaderModule[] modules;
    private final CountDownLatch done = new CountDownLatch(1);
    /** Taken by whichever runs it: the worker, or a draw that asked first. */
    private final AtomicBoolean claimed = new AtomicBoolean();
    private volatile byte[] binary;
    private volatile RuntimeException error;

    public GLProgramBuild(GLShaderTranslator translator, GLProgramBinaries binaries, GLBindingPoints points, String label,
                          GLShaderModule... modules) {
        this.translator = translator;
        this.binaries = binaries;
        this.points = points;
        this.label = label;
        this.modules = modules;
    }

    /** On the worker, its context current. */
    void run(CgGLDriver gl) {
        if (!claimed.compareAndSet(false, true)) return;
        try {
            String key = binaries != null && binaries.on() ? GLProgramBinaries.key(translator, points, modules) : null;
            byte[] kept = key == null ? null : binaries.read(key);
            if (kept != null) {
                binary = kept;
                return;
            }
            int program = GLPrograms.compile(gl, translator, points, label, true, modules);
            try {
                binary = GLProgramBinaries.binary(gl, program);
            } finally {
                gl.glDeleteProgram(program);
            }
            if (key != null) binaries.write(key, binary);
        } catch (RuntimeException e) {
            error = e;
        } finally {
            done.countDown();
        }
    }

    void cancel() {
        error = new IllegalStateException(label + ": the link worker stopped before building it");
        done.countDown();
    }

    /** Whether the worker has finished, so {@link #program} will not wait. */
    @Override
    public boolean ready(CgGLDriver gl) {
        return done.getCount() == 0;
    }

    /** Nothing of it is in the render thread's context until {@link #program}: a landed binary is simply dropped. */
    @Override
    public void cancel(CgGLDriver gl) {
    }

    /**
     * The linked program, on the render thread: waits for the worker if it has not finished, then loads the binary. A
     * binary the driver refuses is compiled here instead.
     *
     * @throws com.crystalgraphics.platform.rhi.shader.CgShaderModule.CompileException as the worker's link failed
     */
    @Override
    public int program(CgGLDriver gl) {
        if (claimed.compareAndSet(false, true)) {
            done.countDown();
            return GLPrograms.link(gl, translator, binaries, points, label, modules);
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + ": interrupted waiting for its link", e);
        }
        if (error != null) throw error;
        int program = gl.glCreateProgram();
        if (GLProgramBinaries.load(gl, program, binary)) {
            gl.glObjectLabel(GL_PROGRAM, program, label);
            return program;
        }
        gl.glDeleteProgram(program);
        if (binaries != null && binaries.on()) binaries.forget(GLProgramBinaries.key(translator, points, modules));
        return GLPrograms.link(gl, translator, null, points, label, modules);
    }
}
