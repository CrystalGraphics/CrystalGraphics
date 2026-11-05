package com.crystalgraphics.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandListContract;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallback;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** The RHI's command contract on the GL 4.6 device, in a hidden window's context: a frame GL reports an error in fails. */
public class GLCommandListTest extends CgCommandListContract {

    private static long window;
    private static CgGLDevice device;
    private static long compiler;
    private static GLDebugMessageCallback callback;
    private static final List<String> errors = new ArrayList<>();
    private int errorsBefore;

    @BeforeClass
    public static void open() {
        assumeTrue("no GLFW here", glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        window = glfwCreateWindow(64, 64, "command contract", NULL, NULL);
        assumeTrue("no GL 4.6 context here", window != NULL);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type == GL43.GL_DEBUG_TYPE_ERROR) errors.add(GLDebugMessageCallback.getMessage(length, message));
        });
        GL43.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        GL43.glDebugMessageCallback(callback, NULL);
        device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
        compiler = shaderc_compiler_initialize();
    }

    @AfterClass
    public static void close() {
        if (compiler != 0) shaderc_compiler_release(compiler);
        if (device != null) device.close();
        if (callback != null) callback.free();
        if (window != NULL) glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Before
    public void count() {
        errorsBefore = errors.size();
    }

    @Override protected CgRHI rhi() { return device; }

    @Override
    protected CgShaderModule module(CgShaderModule.Stage stage, String glsl) {
        int kind = switch (stage) {
            case VERTEX -> shaderc_vertex_shader;
            case FRAGMENT -> shaderc_fragment_shader;
            case COMPUTE -> shaderc_compute_shader;
        };
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
        long result = shaderc_compile_into_spv(compiler, glsl, kind, "contract", "main", options);
        try {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException(shaderc_result_get_error_message(result));
            ByteBuffer spirv = shaderc_result_get_bytes(result);
            return device.createShaderModule(stage, spirv, "contract");
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
        }
    }

    @Override
    protected void checkFrame() {
        device.waitRetired(device.frameIndex() - 1);
        assertEquals("GL errors in this frame: " + errors.subList(errorsBefore, errors.size()), errorsBefore, errors.size());
    }
}
