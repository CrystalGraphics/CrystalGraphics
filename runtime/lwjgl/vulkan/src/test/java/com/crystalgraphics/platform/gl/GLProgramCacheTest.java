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
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import com.crystalgraphics.vulkan.shader.SpvcGlslTranslator;
import org.junit.After;
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

/**
 * The GL device's programs built on each route (its link worker, the driver's own compiler threads), kept as binaries,
 * loaded from them by the next device, and shared by every pipeline of the same modules.
 */
public class GLProgramCacheTest {

    private static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String WHITE = "#version 450\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n";
    private static final String PARALLEL = "crystalgraphics.gl.parallelLinks";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void defaultRoute() {
        CgVars.get(PARALLEL).clear(CgVar.Source.CODE);
    }

    /** Which route the next device builds programs on. */
    private static void parallelLinks(boolean on) throws Exception {
        Class.forName(CgGLDevice.class.getName());   // declares its variables
        CgVars.get(PARALLEL).setText(CgVar.Source.CODE, Boolean.toString(on));
    }

    @Test
    public void aProgramLinkedOnTheWorkerIsKeptAndTheNextDeviceLoadsIt() throws Exception {
        parallelLinks(false);
        keptAndLoaded(true);
    }

    @Test
    public void aProgramBuiltByTheDriverIsKeptAndTheNextDeviceLoadsIt() throws Exception {
        parallelLinks(true);
        keptAndLoaded(false);
    }

    @Test
    public void aPreparedProgramIsLinkedOnceForEveryPipelineOfItsModules() throws Exception {
        parallelLinks(false);
        preparedOnce(true);
    }

    @Test
    public void aProgramPreparedOnTheDriversThreadsIsBuiltOnceForEveryPipelineOfItsModules() throws Exception {
        parallelLinks(true);
        preparedOnce(false);
    }

    /** @param worker the route asked: the link worker, else the driver's threads */
    private void keptAndLoaded(boolean worker) throws Exception {
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
            assumeRoute(first, worker);
            GLGraphicsPipeline p = pipeline(window, first);
            draw(first, p);
            assertEquals("both stages translated", 2, translated.get());
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

    /** @param worker the route asked: the link worker, else the driver's threads */
    private void preparedOnce(boolean worker) throws Exception {
        AtomicInteger translated = new AtomicInteger();
        SpvcGlslTranslator spvc = new SpvcGlslTranslator();
        GLShaderTranslator counting = (spirv, stage, points, label) -> {
            translated.incrementAndGet();
            return spvc.glsl(spirv, stage, points, label);
        };
        try (GLTestWindow window = new GLTestWindow()) {
            CgGLDevice device = new CgGLDevice(new Lwjgl3GLDriver(), counting, 64, 64, true);
            assumeRoute(device, worker);
            CgShaderModule vs = window.spirv(CgShaderModule.Stage.VERTEX, VERTEX,
                    b -> device.createShaderModule(CgShaderModule.Stage.VERTEX, b, "v"));
            CgShaderModule fs = window.spirv(CgShaderModule.Stage.FRAGMENT, WHITE,
                    b -> device.createShaderModule(CgShaderModule.Stage.FRAGMENT, b, "f"));
            device.prepareProgram(CgPipelineLayout.of(), vs, fs);
            long until = System.nanoTime() + 10_000_000_000L;
            while (!device.prepareProgram(CgPipelineLayout.of(), vs, fs)) {
                assertTrue("never built", System.nanoTime() < until);
                Thread.sleep(1);
            }
            GLGraphicsPipeline opaque = pipeline(device, vs, fs, null);
            GLGraphicsPipeline blended = pipeline(device, vs, fs, CgGraphicsPipelineDesc.Blend.ALPHA);
            assertTrue("prepared, so its pipelines wait on nothing", opaque.ready() && blended.ready());
            assertEquals("one program for both", opaque.program(device.driver()), blended.program(device.driver()));
            assertEquals("translated once: both stages", 2, translated.get());
            draw(device, opaque);
            draw(device, blended);
            device.close();
            assertTrue("GL errors: " + window.errors, window.errors.isEmpty());
        }
    }

    /** Skips where the route asked cannot run: no binaries or worker context, or no parallel compile. */
    private static void assumeRoute(CgGLDevice device, boolean worker) {
        if (worker) assumeTrue("no program binaries or worker context here", device.linkWorker());
        else assumeTrue("no parallel shader compile here", device.parallelLinks() && !device.linkWorker());
    }

    private static GLGraphicsPipeline pipeline(CgGLDevice device, CgShaderModule vs, CgShaderModule fs,
                                               CgGraphicsPipelineDesc.Blend blend) {
        return (GLGraphicsPipeline) device.createPipeline(new CgGraphicsPipelineDesc("shared", CgPipelineLayout.of(), vs, fs,
                List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                CgGraphicsPipelineDesc.DepthStencil.OFF, List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM,
                blend, 0xF)), null, 1));
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
