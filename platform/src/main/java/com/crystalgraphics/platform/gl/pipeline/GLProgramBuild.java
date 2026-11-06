package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.util.concurrent.CountDownLatch;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * One program's build on the {@link GLLinkWorker}: its kept binary where there is one, else translated, compiled and
 * linked on the worker's context, its binary kept; then loaded on the render thread at the first ask.
 */
public final class GLProgramBuild {

    private final GLShaderTranslator translator;
    private final GLProgramBinaries binaries;
    private final GLBindingPoints points;
    private final String label;
    private final GLShaderModule[] modules;
    private final CountDownLatch done = new CountDownLatch(1);
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
    public boolean ready() {
        return done.getCount() == 0;
    }

    /**
     * The linked program, on the render thread: waits for the worker if it has not finished, then loads the binary. A
     * binary the driver refuses is compiled here instead.
     *
     * @throws com.crystalgraphics.platform.rhi.shader.CgShaderModule.CompileException as the worker's link failed
     */
    public int program(CgGLDriver gl) {
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
