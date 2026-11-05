package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assume.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** A hidden GLFW window with a GL 4.6 core debug context current, its errors collected, and shaderc for SPIR-V. */
final class GLTestWindow implements AutoCloseable {

    final List<String> errors = new ArrayList<>();
    private final long window;
    private final GLDebugMessageCallback callback;
    private final long compiler;

    GLTestWindow() {
        assumeTrue("no GLFW here", glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        window = glfwCreateWindow(64, 64, "GL device test", NULL, NULL);
        assumeTrue("no GL 4.6 context here", window != NULL);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type == GL43.GL_DEBUG_TYPE_ERROR) errors.add(GLDebugMessageCallback.getMessage(length, message));
        });
        GL43.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GL43.glDebugMessageCallback(callback, NULL);
        compiler = shaderc_compiler_initialize();
    }

    /** {@code glsl}, Vulkan GLSL, as SPIR-V handed to {@code make} before shaderc's result is freed. */
    <T> T spirv(CgShaderModule.Stage stage, String glsl, Function<ByteBuffer, T> make) {
        int kind = switch (stage) {
            case VERTEX -> shaderc_vertex_shader;
            case FRAGMENT -> shaderc_fragment_shader;
            case COMPUTE -> shaderc_compute_shader;
        };
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        long result = shaderc_compile_into_spv(compiler, glsl, kind, "test", "main", options);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException(shaderc_result_get_error_message(result));
            return make.apply(shaderc_result_get_bytes(result));
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
        }
    }

    @Override
    public void close() {
        shaderc_compiler_release(compiler);
        callback.free();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
