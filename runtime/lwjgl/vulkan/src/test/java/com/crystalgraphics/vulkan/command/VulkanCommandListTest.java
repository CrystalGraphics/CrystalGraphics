package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandListContract;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** The RHI's command contract on the owned Vulkan device, with the validation layer on: a frame it flags fails. */
public class VulkanCommandListTest extends CgCommandListContract {

    private static long window;
    private static OwnedVulkanHost host;
    private static CgVulkanDevice device;
    private static long compiler;
    private int errorsBefore;

    @BeforeClass
    public static void open() {
        assumeTrue("no GLFW here", glfwInit());
        assumeTrue("no Vulkan loader here", glfwVulkanSupported());
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        window = glfwCreateWindow(64, 64, "command contract", NULL, NULL);
        host = new OwnedVulkanHost(window, true);
        assumeTrue("the validation layer is not installed", host.validates());
        device = new CgVulkanDevice(host, 64, 64);
        compiler = shaderc_compiler_initialize();
    }

    @AfterClass
    public static void close() {
        if (compiler != 0) shaderc_compiler_release(compiler);
        if (device != null) device.close();
        if (host != null) host.close();
        if (window != NULL) glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Before
    public void count() {
        errorsBefore = device.validationErrors();
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
            ByteBuffer copy = ByteBuffer.allocateDirect(spirv.remaining()).put(spirv).flip();
            return device.createShaderModule(stage, copy, "contract");
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
        }
    }

    @Override
    protected void checkFrame() {
        device.waitRetired(device.frameIndex() - 1);
        assertEquals("validation messages for this frame", errorsBefore, device.validationErrors());
    }
}
