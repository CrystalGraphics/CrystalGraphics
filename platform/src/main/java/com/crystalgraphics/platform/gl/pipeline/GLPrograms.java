package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/** Links a program from SPIR-V modules, each translated under one layout's binding points. */
public final class GLPrograms {

    private GLPrograms() {}

    /**
     * Links on the calling thread, from a kept binary where there is one.
     *
     * @param binaries kept binaries; null for none
     * @throws CgShaderModule.CompileException with the driver's log when a stage fails to compile or the program to link
     */
    public static int link(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries,
                           GLBindingPoints points, String label, GLShaderModule... modules) {
        String key = binaries != null && binaries.on() ? GLProgramBinaries.key(points, modules) : null;
        if (key != null) {
            byte[] kept = binaries.read(key);
            if (kept != null) {
                int program = gl.glCreateProgram();
                if (GLProgramBinaries.load(gl, program, kept)) {
                    gl.glObjectLabel(GL_PROGRAM, program, label);
                    return program;
                }
                gl.glDeleteProgram(program);
                binaries.forget(key);
            }
        }
        int program = compile(gl, translator, points, label, key != null, modules);
        if (key != null) binaries.write(key, GLProgramBinaries.binary(gl, program));
        return program;
    }

    /**
     * Translates, compiles and links on the calling thread.
     *
     * @param retrievable whether the driver is asked to keep the program's binary
     * @throws CgShaderModule.CompileException with the driver's log when a stage fails to compile or the program to link
     */
    static int compile(CgGLDriver gl, GLShaderTranslator translator, GLBindingPoints points, String label,
                       boolean retrievable, GLShaderModule... modules) {
        int program = gl.glCreateProgram();
        if (retrievable) gl.glProgramParameteri(program, GL_PROGRAM_BINARY_RETRIEVABLE_HINT, 1);
        int[] shaders = new int[modules.length];
        try {
            for (int i = 0; i < modules.length; i++) {
                GLShaderModule m = modules[i];
                String source = translator.glsl(m.spirv, m.stage(), points, label);
                int shader = shaders[i] = gl.glCreateShader(type(m.stage()));
                gl.glShaderSource(shader, source);
                gl.glCompileShader(shader);
                if (gl.glGetShaderi(shader, GL_COMPILE_STATUS) == 0)
                    throw new CgShaderModule.CompileException(label + " " + m.stage() + ": " + gl.glGetShaderInfoLog(shader)
                            + "\n" + source);
                gl.glAttachShader(program, shader);
            }
            gl.glLinkProgram(program);
            if (gl.glGetProgrami(program, GL_LINK_STATUS) == 0)
                throw new CgShaderModule.CompileException(label + ": " + gl.glGetProgramInfoLog(program));
            gl.glObjectLabel(GL_PROGRAM, program, label);
            return program;
        } catch (RuntimeException e) {
            gl.glDeleteProgram(program);
            throw e;
        } finally {
            for (int shader : shaders) {
                if (shader == 0) continue;
                gl.glDetachShader(program, shader);
                gl.glDeleteShader(shader);
            }
        }
    }

    private static int type(CgShaderModule.Stage stage) {
        return switch (stage) {
            case VERTEX -> GL_VERTEX_SHADER;
            case FRAGMENT -> GL_FRAGMENT_SHADER;
            case COMPUTE -> GL_COMPUTE_SHADER;
        };
    }
}
