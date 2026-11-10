package com.crystalgraphics.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.resource.GLImports;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.lwjgl.opengl.GL46.*;

/** A host's GL texture, known by its name, drawn with on the GL device. */
public class GLImportsTest {

    private static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String SAMPLED = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(set = 0, binding = 0) uniform sampler2D t;\n"
            + "void main() { o = texture(t, vec2(0.5)); }\n";

    @Test
    public void aHostTextureDrawsAndIsDescribedAnewWhenRespecified() {
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
            GLImports imports = device.imports();

            int texture = glGenTextures();                               // as a host makes one: mutable storage
            specify(texture, 4);
            device.fromHost();

            CgGpuTexture imported = imports.texture(texture);
            assertEquals(4, imported.desc().width());
            assertSame("the same texture for the same handle", imported, imports.texture(texture));

            CgBindingLayout set = device.createBindingLayout("imported", List.of(
                    new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)), true);
            CgShaderModule vs = window.spirv(CgShaderModule.Stage.VERTEX, VERTEX,
                    b -> device.createShaderModule(CgShaderModule.Stage.VERTEX, b, "v"));
            CgShaderModule fs = window.spirv(CgShaderModule.Stage.FRAGMENT, SAMPLED,
                    b -> device.createShaderModule(CgShaderModule.Stage.FRAGMENT, b, "f"));
            CgRenderPass pass = device.encoder().beginPass(new CgPassDesc("imports", List.of(new CgPassDesc.Color(
                    CgTextureView.whole(device.surfaceColor()), CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)),
                    null, 64, 64));
            pass.setPipeline(device.createPipeline(new CgGraphicsPipelineDesc("imports", CgPipelineLayout.of(set), vs, fs,
                    List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                    CgGraphicsPipelineDesc.DepthStencil.OFF, List.of(new CgGraphicsPipelineDesc.ColorTarget(
                    CgFormat.RGBA8_UNORM, null, 0xF)), null, 1)));
            pass.pushBindings(new CgBindings().texture(0, CgTextureView.whole(imported),
                    device.createSampler(CgGpuSampler.Desc.DEFAULT)));
            pass.draw(3, 1, 0, 0);
            pass.end();
            device.endFrame();
            device.toHost();

            specify(texture, 8);                                         // the host remade its storage under the name
            device.fromHost();
            CgGpuTexture respecified = imports.texture(texture);
            assertNotSame("a remade texture is wrapped anew", imported, respecified);
            assertEquals(8, respecified.desc().width());
            device.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    private static void specify(int texture, int size) {
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
}
