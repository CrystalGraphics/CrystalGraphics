package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.state.GLCheckedHostState;
import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.state.GLContextState;
import com.crystalgraphics.platform.gl.state.GLHostState;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.lwjgl.opengl.GL46.*;

/** A host section on the GL device sets back every field it changed to what the host left. */
public class GLHostSectionTest {

    private static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String TEXTURED = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(set = 0, binding = 0) uniform U { vec4 c; } u;\n"
            + "layout(set = 0, binding = 1) uniform sampler2D t;\n"
            + "layout(push_constant) uniform P { float k; } p;\n"
            + "void main() { o = u.c * texture(t, vec2(0.5)) * p.k; }\n";

    /** A host whose cache declares none of the device's conventions draws under GL's defaults: they are set back. */
    @Test
    public void aCachedHostsSectionLeavesGLsDefaults() {
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDriver driver = new Lwjgl3GLDriver();
            CgGLDevice device = new CgGLDevice(driver, new SpvcGlslTranslator(), 64, 64, false,
                    new GLCheckedHostState("test", driver, (domain, s) -> false, false));
            device.fromHost();
            assertTrue("the device writes sRGB inside the section", glIsEnabled(GL_FRAMEBUFFER_SRGB));
            device.toHost();
            assertFalse("sRGB writes left on: the host's UI encoded twice", glIsEnabled(GL_FRAMEBUFFER_SRGB));
            assertFalse(glIsEnabled(GL_TEXTURE_CUBE_MAP_SEAMLESS));
            assertFalse(glIsEnabled(GL_PROGRAM_POINT_SIZE));
            assertEquals(GL_NEGATIVE_ONE_TO_ONE, glGetInteger(GL_CLIP_DEPTH_MODE));
            assertEquals(GL_LOWER_LEFT, glGetInteger(GL_CLIP_ORIGIN));
            assertEquals(4, glGetInteger(GL_UNPACK_ALIGNMENT));
            assertEquals(4, glGetInteger(GL_PACK_ALIGNMENT));
            device.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    @Test
    public void aSectionLeavesTheHostsStateAsItFoundIt() {
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDriver driver = new Lwjgl3GLDriver();
            hostState();
            GLContextState before = new GLContextState();
            GLHostState host = GLHostState.byQuery(driver);
            host.read(before);

            CgGLDevice device = new CgGLDevice(driver, new SpvcGlslTranslator(), 64, 64, false, host);
            device.fromHost();
            assertEquals("the device's depth convention inside the section", GL_ZERO_TO_ONE,
                    glGetInteger(GL_CLIP_DEPTH_MODE));
            draw(window, device);
            device.toHost();
            device.endFrame();

            GLContextState after = new GLContextState();
            host.read(after);
            assertEquals("fields the section left changed", List.of(), differences(before, after));
            assertEquals("GL errors: " + window.errors, 0, window.errors.size());
            device.fromHost();
            device.close();
            device.toHost();
        }
    }

    /** What a host such as Minecraft leaves set between its draws. */
    private static void hostState() {
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_GREATER);
        glDepthMask(false);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_FRONT);
        glViewport(1, 2, 30, 40);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        int texture = glGenTextures();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(GL_TEXTURE0);
        int ubo = glGenBuffers();
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, ubo);
        glUseProgram(0);
    }

    private static void draw(GLTestWindow window, CgGLDevice device) {
        CgBindingLayout set = device.createBindingLayout("material", List.of(
                new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                new CgBindingLayout.Slot(1, CgBindingLayout.Type.SAMPLED_TEXTURE)), true);
        CgShaderModule vs = window.spirv(CgShaderModule.Stage.VERTEX, VERTEX,
                b -> device.createShaderModule(CgShaderModule.Stage.VERTEX, b, "v"));
        CgShaderModule fs = window.spirv(CgShaderModule.Stage.FRAGMENT, TEXTURED,
                b -> device.createShaderModule(CgShaderModule.Stage.FRAGMENT, b, "f"));
        CgGraphicsPipelineDesc.DepthStencil depth = new CgGraphicsPipelineDesc.DepthStencil(true, true,
                CgCompare.LESS_EQUAL, false, CgGraphicsPipelineDesc.StencilFace.KEEP, CgGraphicsPipelineDesc.StencilFace.KEEP,
                0xFF, 0xFF);
        CgGraphicsPipeline pipeline = device.createPipeline(new CgGraphicsPipelineDesc("section", new CgPipelineLayout(
                List.of(set), 4), vs, fs, List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES,
                new CgGraphicsPipelineDesc.Raster(CgGraphicsPipelineDesc.CullMode.BACK, CgGraphicsPipelineDesc.FrontFace.CW,
                        CgGraphicsPipelineDesc.PolygonMode.FILL, false),
                depth, List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM,
                CgGraphicsPipelineDesc.Blend.ALPHA, 0xF)), device.surfaceDepth().desc().format(), 1));
        CgGpuBuffer uniform = device.createBuffer(new CgGpuBuffer.Desc("u", 16, CgGpuBuffer.Usage.ALL, true));
        CgGpuTexture texture = device.createTexture(new CgGpuTexture.Desc("t", CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM,
                4, 4, 1, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED));
        CgGpuSampler sampler = device.createSampler(CgGpuSampler.Desc.DEFAULT);
        CgRenderPass pass = device.encoder().beginPass(new CgPassDesc("section",
                List.of(new CgPassDesc.Color(CgTextureView.whole(device.surfaceColor()), CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1,
                        CgPassDesc.StoreOp.STORE)),
                new CgPassDesc.Depth(CgTextureView.whole(device.surfaceDepth()), CgPassDesc.LoadOp.CLEAR, 1,
                        CgPassDesc.LoadOp.CLEAR, 0, CgPassDesc.StoreOp.STORE, false), 64, 64));
        pass.setViewport(0, 0, 64, 64, 0, 1);
        pass.setScissor(0, 0, 64, 64);
        pass.setPipeline(pipeline);
        pass.pushBindings(new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, uniform, 0, 16)
                .texture(1, CgTextureView.whole(texture), sampler));
        pass.pushConstants(0, Float.floatToRawIntBits(0.5f));
        pass.draw(3, 1, 0, 0);
        pass.end();
        device.release(uniform);
        device.release(texture);
        device.release(sampler);
        device.release(pipeline);
    }

    /** Every public field of the state that differs, by name and index. */
    private static List<String> differences(GLContextState a, GLContextState b) {
        List<String> out = new ArrayList<>();
        try {
            for (Field f : GLContextState.class.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object x = f.get(a), y = f.get(b);
                if (x.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(x); i++) {
                        if (!Array.get(x, i).equals(Array.get(y, i)))
                            out.add(f.getName() + "[" + i + "] " + Array.get(x, i) + " -> " + Array.get(y, i));
                    }
                } else if (!x.equals(y)) {
                    out.add(f.getName() + " " + x + " -> " + y);
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        return out;
    }
}
