package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * One program built by the driver's own compiler threads ({@code KHR_parallel_shader_compile}), issued on the render
 * thread: compiled and linked with no status asked, then polled with {@code GL_COMPLETION_STATUS} until the driver has
 * it, as Chromium builds WebGL programs. The alternative to {@link GLLinkWorker}'s second context.
 *
 * <pre>{@code
 * GLParallelBuild b = GLParallelBuild.start(gl, translator, binaries, points, label, vs, fs);   // returns at once
 * if (b.ready(gl)) program = b.program(gl);       // each frame until ready; program() would wait for it
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only: the program lives in the render thread's context.</li>
 *   <li>Any status asked before completion waits for it, so nothing is asked until {@link #ready} answers true.</li>
 * </ul>
 */
public final class GLParallelBuild implements GLProgramCache.Build {

    private final GLShaderTranslator translator;
    private final GLProgramBinaries binaries;
    private final GLBindingPoints points;
    private final String label;
    private final GLShaderModule[] modules;
    private final String key;
    private int program;
    private int[] shaders;
    private String[] sources;
    private boolean done;

    private GLParallelBuild(GLShaderTranslator translator, GLProgramBinaries binaries, GLBindingPoints points,
                            String label, GLShaderModule[] modules) {
        this.translator = translator;
        this.binaries = binaries;
        this.points = points;
        this.label = label;
        this.modules = modules;
        this.key = binaries != null && binaries.on() ? GLProgramBinaries.key(translator, points, modules) : null;
    }

    /** Issues the compile and link, or loads the kept binary: answers at once. */
    public static GLParallelBuild start(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries,
                                        GLBindingPoints points, String label, GLShaderModule... modules) {
        GLParallelBuild b = new GLParallelBuild(translator, binaries, points, label, modules);
        b.issue(gl);
        return b;
    }

    private void issue(CgGLDriver gl) {
        byte[] kept = key == null ? null : binaries.read(key);
        if (kept != null) {
            int p = gl.glCreateProgram();
            if (GLProgramBinaries.load(gl, p, kept)) {
                gl.glObjectLabel(GL_PROGRAM, p, label);
                program = p;
                done = true;
                return;
            }
            gl.glDeleteProgram(p);
            binaries.forget(key);
        }
        program = gl.glCreateProgram();
        // No GL_PROGRAM_BINARY_RETRIEVABLE_HINT: NVIDIA finishes a hinted program at its first status read, on this
        // thread, and gives its binary unhinted all the same
        shaders = new int[modules.length];
        sources = new String[modules.length];
        for (int i = 0; i < modules.length; i++) {
            GLShaderModule m = modules[i];
            sources[i] = translator.glsl(m.spirv, m.stage(), points, label);
            int shader = shaders[i] = gl.glCreateShader(GLPrograms.type(m.stage()));
            gl.glShaderSource(shader, sources[i]);
            gl.glCompileShader(shader);
            gl.glAttachShader(program, shader);
        }
        // Linked whatever the stages' compiles: a failed one shows as a failed link, its log read in program()
        gl.glLinkProgram(program);
    }

    /** Whether the driver has finished it, so {@link #program} will not wait. Never waits. */
    @Override
    public boolean ready(CgGLDriver gl) {
        return done || gl.glGetProgrami(program, GL_COMPLETION_STATUS) != 0;
    }

    /**
     * The linked program, waiting for the driver if it has not finished.
     *
     * @throws CgShaderModule.CompileException with the driver's log when a stage failed to compile or the program to link
     */
    @Override
    public int program(CgGLDriver gl) {
        if (done) return program;
        done = true;
        try {
            if (gl.glGetProgrami(program, GL_LINK_STATUS) == 0) {
                StringBuilder log = new StringBuilder(label).append(": ").append(gl.glGetProgramInfoLog(program));
                for (int i = 0; i < shaders.length; i++) {
                    if (gl.glGetShaderi(shaders[i], GL_COMPILE_STATUS) == 0) {
                        log.append('\n').append(modules[i].stage()).append(": ").append(gl.glGetShaderInfoLog(shaders[i]))
                                .append('\n').append(sources[i]);
                    }
                }
                gl.glDeleteProgram(program);
                program = 0;
                throw new CgShaderModule.CompileException(log.toString());
            }
            gl.glObjectLabel(GL_PROGRAM, program, label);
            if (key != null) binaries.write(key, GLProgramBinaries.binary(gl, program));
            return program;
        } finally {
            GLPrograms.release(gl, program, shaders);
            shaders = null;
            sources = null;
        }
    }

    /** Deletes what it holds, finished or not. */
    @Override
    public void cancel(CgGLDriver gl) {
        if (shaders != null) GLPrograms.release(gl, program, shaders);
        if (program != 0) gl.glDeleteProgram(program);
        program = 0;
        shaders = null;
        done = true;
    }
}
