package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/** Links a program from SPIR-V modules, each translated under one layout's binding points. */
public final class GLPrograms {

    private GLPrograms() {}

    /** @throws CgShaderModule.CompileException with the driver's log when a stage fails to compile or the program to link */
    public static int link(CgGLDriver gl, GLShaderTranslator translator, GLBindingPoints points, String label,
                           CgShaderModule... modules) {
        int program = gl.glCreateProgram();
        int[] shaders = new int[modules.length];
        try {
            for (int i = 0; i < modules.length; i++) {
                GLShaderModule m = (GLShaderModule) modules[i];
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
