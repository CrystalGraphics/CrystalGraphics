package com.crystalgraphics.platform.gl;

import com.crystalgraphics.lwjgl3.Lwjgl3GLDriver;
import com.crystalgraphics.platform.gl.pipeline.GLGraphicsPipeline;
import com.crystalgraphics.platform.gl.pipeline.GLShaderTranslator;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** The GL device's programs linked on its worker, kept as binaries, and loaded from them by the next device. */
public class GLProgramCacheTest {

    private static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String WHITE = "#version 450\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aProgramLinkedOnTheWorkerIsKeptAndTheNextDeviceLoadsIt() throws Exception {
        Path cache = folder.newFolder("glprogram").toPath();
        AtomicInteger translated = new AtomicInteger();
        SpvcGlslTranslator spvc = new SpvcGlslTranslator();
        GLShaderTranslator counting = (spirv, stage, points, label) -> {
            translated.incrementAndGet();
            return spvc.glsl(spirv, stage, points, label);
        };
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDevice first = new CgGLDevice(new Lwjgl3GLDriver(), counting, 64, 64, true);
            first.programCache(cache, Runnable::run);
            assumeTrue("no program binaries or worker context here", first.linkWorker());
            GLGraphicsPipeline p = pipeline(window, first);
            draw(first, p);
            assertEquals("both stages translated, on the worker", 2, translated.get());
            first.close();
            assertEquals("one binary kept", 1, files(cache));

            CgGLDevice second = new CgGLDevice(new Lwjgl3GLDriver(), counting, 64, 64, true);
            second.programCache(cache, Runnable::run);
            draw(second, pipeline(window, second));
            assertEquals("loaded from the binary: nothing translated or compiled", 2, translated.get());
            second.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    private static GLGraphicsPipeline pipeline(GLTestWindow window, CgGLDevice device) {
        CgShaderModule vs = window.spirv(CgShaderModule.Stage.VERTEX, VERTEX,
                b -> device.createShaderModule(CgShaderModule.Stage.VERTEX, b, "v"));
        CgShaderModule fs = window.spirv(CgShaderModule.Stage.FRAGMENT, WHITE,
                b -> device.createShaderModule(CgShaderModule.Stage.FRAGMENT, b, "f"));
        return (GLGraphicsPipeline) device.createPipeline(new CgGraphicsPipelineDesc("cached", CgPipelineLayout.of(), vs, fs,
                List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                CgGraphicsPipelineDesc.DepthStencil.OFF, List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM,
                null, 0xF)), null, 1));
    }

    private static void draw(CgGLDevice device, GLGraphicsPipeline pipeline) {
        CgRenderPass pass = device.encoder().beginPass(new CgPassDesc("cached", List.of(new CgPassDesc.Color(
                CgTextureView.whole(device.surfaceColor()), CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)),
                null, 64, 64));
        pass.setPipeline(pipeline);
        pass.draw(3, 1, 0, 0);
        pass.end();
        device.endFrame();
        device.release(pipeline);
    }

    private static long files(Path dir) throws Exception {
        try (Stream<Path> all = Files.walk(dir)) {
            return all.filter(Files::isRegularFile).count();
        }
    }
}
