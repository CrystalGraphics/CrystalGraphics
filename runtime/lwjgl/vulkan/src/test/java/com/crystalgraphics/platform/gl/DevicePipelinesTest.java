package com.crystalgraphics.platform.gl;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.shader.CgShader;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLContext;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.ShippedPrograms;
import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Every shipped program as a device pipeline through the engine's own path, {@link CgPipeline#device}: its sources
 * compiled as written, its pass's declared state, its shader's vertex format. On the GL device, then on Vulkan.
 */
public class DevicePipelinesTest {

    @Test
    public void everyShippedPipelineBuildsOnBothDevices() throws Exception {
        try (GLTestWindow window = new GLTestWindow();
             ShadercGlslCompiler compiler = new ShadercGlslCompiler()) {
            CgGLDevice gl = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
            glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            long vkWindow = glfwCreateWindow(64, 64, "device pipelines", NULL, NULL);
            OwnedVulkanHost host = new OwnedVulkanHost(vkWindow, true);
            CgVulkanDevice vulkan = new CgVulkanDevice(host, 64, 64);
            CgTrackedGLBackend tracked = new CgTrackedGLBackend(new CgNullRHI(64, 64), compiler, true);
            List<String> failures = new ArrayList<>();
            try {
                CgGL.init(tracked);
                CgCapabilities.init(new CgTrackedGLContext());
                CgCapabilities.clearCache();
                CgGlState.reset();
                CgGlState.setProvider(new CgTrackedStateProvider(tracked));
                CgBindingPoints.init(CgCapabilities.detect());
                CgFallbackTextures.init();
                CgGlslCompiler.install(compiler);
                int[] built = new int[2];
                CgRHI[] devices = {gl, vulkan};
                for (int d = 0; d < devices.length; d++) {
                    CgRHI device = devices[d];
                    CgRHI.install(device);
                    int at = d;
                    int visited = ShippedPrograms.forEachVariant(failures, false, (path, shader, pass, keywords) -> {
                        CgPipeline p = CgPipeline.pass(shader, pass, keywords, shader.getRenderState(pass), CgInstanceKind.OBJECT);
                        String what = device.globals().name() + " " + p;
                        try {
                            CgShader linked = p.program();
                            if (linked == null) {
                                failures.add(what + ": did not link");
                                return;
                            }
                            int outputs = Math.max(1, linked.explicit(what).colorOutputs());
                            if (p.device(CgRenderState.DEFAULT, CgGraphicsPipelineDesc.Topology.TRIANGLES, true,
                                    Collections.nCopies(outputs, CgFormat.RGBA16_FLOAT),
                                    CgFormat.DEPTH32_FLOAT, 1) != null) built[at]++;
                            else failures.add(what + ": no pipeline");
                        } catch (RuntimeException e) {
                            failures.add(what + ": " + firstLines(e.getMessage()));
                        }
                        device.endFrame();
                    });
                    assertTrue("no program was visited: the test proves nothing", visited > 10);
                }
                assertTrue(String.join("\n", failures.subList(0, Math.min(20, failures.size()))) + "\n("
                        + failures.size() + " failures; built " + built[0] + " on GL, " + built[1] + " on Vulkan)",
                        failures.isEmpty());
                assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
            } finally {
                CgRHI.install(null);
                CgGlslCompiler.install(null);
                CgGlState.reset();
                vulkan.close();
                host.close();
                glfwDestroyWindow(vkWindow);
                gl.close();
            }
        }
    }

    private static String firstLines(String s) {
        String[] lines = s == null ? new String[0] : s.split("\n");
        return String.join(" | ", Arrays.copyOf(lines, Math.min(4, lines.length)));
    }
}
