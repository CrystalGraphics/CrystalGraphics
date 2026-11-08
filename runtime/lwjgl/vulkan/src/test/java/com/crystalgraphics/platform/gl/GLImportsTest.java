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
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Assume;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.lwjgl.opengl.GL46.*;

/** CgGL-made textures and buffers drawn with on the GL device, and the device's own textures named for CgGL. */
public class GLImportsTest {

    private static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String SAMPLED = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(set = 0, binding = 0) uniform U { vec4 c; } u;\n"
            + "layout(set = 0, binding = 1) uniform sampler2D t;\n"
            + "void main() { o = u.c * texture(t, vec2(0.5)); }\n";

    @Test
    public void importedObjectsDrawAndDeviceTexturesKeepTheirNames() {
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
            GLImports imports = device.imports();

            int texture = glGenTextures();                               // as CgGL makes one: mutable storage
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glBindTexture(GL_TEXTURE_2D, 0);
            int buffer = glGenBuffers();
            glBindBuffer(GL_UNIFORM_BUFFER, buffer);
            glBufferData(GL_UNIFORM_BUFFER, 64, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_UNIFORM_BUFFER, 0);
            device.fromHost();

            CgGpuTexture.Desc desc = new CgGpuTexture.Desc("cggl texture", CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, 4, 4,
                    1, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED);
            CgGpuTexture imported = imports.texture(texture, desc);
            assertSame("kept until CgGL deletes the name", imported, imports.texture(texture, desc));
            CgGpuBuffer uniform = imports.buffer(buffer);
            assertEquals(64, uniform.size());

            CgBindingLayout set = device.createBindingLayout("imported", List.of(
                    new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                    new CgBindingLayout.Slot(1, CgBindingLayout.Type.SAMPLED_TEXTURE)), true);
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
            pass.pushBindings(new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, uniform,
                    imports.bufferOffset(buffer), 64).texture(1, CgTextureView.whole(imported), device.createSampler(
                    CgGpuSampler.Desc.DEFAULT)));
            pass.draw(3, 1, 0, 0);
            pass.end();
            device.endFrame();

            imports.textureDeleted(texture);
            assertNotSame("a deleted name is wrapped anew", imported, imports.texture(texture, desc));
            CgGpuTexture own = device.createTexture(desc);
            assertTrue("an RHI texture's name is a GL texture", glIsTexture(imports.name(own)));
            device.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    /** A name the device frees is GL's to hand to CgGL again: its import is made anew, at the new storage's size. */
    @Test
    public void aNameTheDeviceFreesIsImportedAnewWhenCgGLReusesIt() {
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), new SpvcGlslTranslator(), 64, 64, true);
            GLImports imports = device.imports();
            CgGpuBuffer own = device.createBuffer(new CgGpuBuffer.Desc("device buffer", 4096, CgGpuBuffer.Usage.ALL, false));
            int name = imports.name(own);
            assertEquals(4096, imports.buffer(name).size());

            device.release(own);
            for (int f = 0; f <= CgGLDevice.FRAMES_IN_FLIGHT; f++) device.endFrame();   // its frame retires: deleted
            glFinish();
            device.endFrame();
            int reused = 0;
            for (int i = 0; i < 64 && reused != name; i++) reused = glGenBuffers();
            Assume.assumeTrue("the driver handed the freed name out again", reused == name);
            glBindBuffer(GL_COPY_WRITE_BUFFER, name);
            glBufferData(GL_COPY_WRITE_BUFFER, 256, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_COPY_WRITE_BUFFER, 0);

            assertEquals("imported at the new storage's size", 256, imports.buffer(name).size());
            device.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }
}
