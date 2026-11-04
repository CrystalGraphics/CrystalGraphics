package com.crystalgraphics.platform.rhi.command;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.sync.CgGpuReadback;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * What every {@link CgRHI} records: each command of {@link CgCommandList} and its passes, used as the API allows, one
 * frame a test. An implementation's test extends it and says how its device checks a frame (the null RHI by refusing
 * as it records; Vulkan by its validation layer).
 *
 * <pre>{@code
 * public class VulkanCommandListTest extends CgCommandListContract {
 *     protected CgRHI rhi() { return device; }
 *     protected CgShaderModule module(CgShaderModule.Stage stage, String glsl) { return device.createShaderModule(stage, spirv(glsl), "t"); }
 *     protected void checkFrame() { assertEquals(0, device.validationErrors()); }
 * }
 * }</pre>
 *
 * <p>Shaders are Vulkan GLSL 4.50: a device that compiles them must find what each pipeline layout declares.</p>
 */
public abstract class CgCommandListContract {

    protected abstract CgRHI rhi();

    /** {@code glsl} as a module of {@code stage}: compiled where the device runs it, anything valid where it does not. */
    protected abstract CgShaderModule module(CgShaderModule.Stage stage, String glsl);

    /** After a test's frame has ended: whatever the device found wrong with it fails the test here. */
    protected abstract void checkFrame();

    protected static final String VERTEX = "#version 450\nvoid main() {\n"
            + "    gl_Position = vec4(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1), 0.5, 1.0);\n}\n";
    private static final String WHITE = "#version 450\nlayout(location = 0) out vec4 o;\nvoid main() { o = vec4(1.0); }\n";
    private static final String UNIFORM = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(set = 0, binding = 0) uniform U { vec4 c; } u;\nvoid main() { o = u.c; }\n";
    private static final String TWO_SETS = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(set = 0, binding = 0) uniform A { vec4 c; } a;\nlayout(set = 1, binding = 0) uniform B { vec4 c; } b;\n"
            + "void main() { o = a.c + b.c; }\n";
    private static final String CONSTANTS = "#version 450\nlayout(location = 0) out vec4 o;\n"
            + "layout(push_constant) uniform P { int a; int b; } p;\nvoid main() { o = vec4(float(p.a), float(p.b), 0.0, 1.0); }\n";
    private static final String NOTHING = "#version 450\nvoid main() {}\n";
    private static final String BINDLESS = "#version 450\n#extension GL_EXT_nonuniform_qualifier : require\n"
            + "layout(location = 0) out vec4 o;\n"
            + "layout(set = 3, binding = 0) uniform sampler2D textures[];\n"
            + "layout(push_constant) uniform P { uint tex; } p;\n"
            + "vec4 read(sampler2D s) { return texture(s, vec2(0.5)); }\n"   // a heap slot passes to a function
            + "void main() { o = read(textures[p.tex]); }\n";
    private static final String KERNEL = "#version 450\nlayout(local_size_x = 1) in;\n"
            + "layout(set = 0, binding = 0) uniform U { vec4 c; } u;\nlayout(set = 1, binding = 0) buffer B { uint v[]; } b;\n"
            + "layout(push_constant) uniform P { uint at; } p;\nvoid main() { b.v[p.at] = uint(u.c.x); }\n";

    private CgGpuBuffer buffer(String label) {
        CgGpuBuffer b = rhi().createBuffer(new CgGpuBuffer.Desc(label, 256, CgGpuBuffer.Usage.ALL, true));
        ByteBuffer m = b.mapped();
        for (int i = 0; i < 256; i++) m.put(i, (byte) 0);
        return b;
    }

    private CgBindingLayout layout(String label, CgBindingLayout.Type type, boolean pushed) {
        return rhi().createBindingLayout(label, List.of(new CgBindingLayout.Slot(0, type)), pushed);
    }

    private CgBindings uniform(CgGpuBuffer b) {
        return new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, b, 0, 16);
    }

    private CgGraphicsPipeline pipeline(CgPipelineLayout layout, String fragment) {
        CgFormat color = rhi().surfaceColor().desc().format();
        return rhi().createPipeline(new CgGraphicsPipelineDesc("contract", layout,
                module(CgShaderModule.Stage.VERTEX, VERTEX), module(CgShaderModule.Stage.FRAGMENT, fragment), List.of(),
                CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                CgGraphicsPipelineDesc.DepthStencil.OFF, List.of(new CgGraphicsPipelineDesc.ColorTarget(color, null, 0xF)),
                null, 1));
    }

    private CgRenderPass surfacePass() {
        CgPassDesc desc = new CgPassDesc("contract", List.of(new CgPassDesc.Color(CgTextureView.whole(rhi().surfaceColor()),
                CgPassDesc.LoadOp.CLEAR, 0, 0, 0, 1, CgPassDesc.StoreOp.STORE)), null,
                rhi().surfaceColor().desc().width(), rhi().surfaceColor().desc().height());
        CgRenderPass pass = rhi().encoder().beginPass(desc);
        pass.setViewport(0, 0, desc.width(), desc.height(), 0, 1);
        pass.setScissor(0, 0, desc.width(), desc.height());
        return pass;
    }

    private void endFrame() {
        rhi().endFrame();
        checkFrame();
    }

    @Test
    public void aDrawReadsItsPushedSet() {
        CgRenderPass pass = surfacePass();
        pass.setPipeline(pipeline(CgPipelineLayout.of(layout("pushed", CgBindingLayout.Type.UNIFORM_BUFFER, true)), UNIFORM));
        pass.pushBindings(uniform(buffer("u")));
        pass.draw(3, 1, 0, 0);
        pass.end();
        endFrame();
    }

    @Test
    public void aDrawReadsItsBindingSets() {
        CgBindingLayout view = layout("view", CgBindingLayout.Type.UNIFORM_BUFFER, false);
        CgBindingLayout material = layout("material", CgBindingLayout.Type.UNIFORM_BUFFER, false);
        CgBindingSet viewSet = rhi().createBindingSet("view", view, uniform(buffer("v")));
        CgBindingSet stone = rhi().createBindingSet("stone", material, uniform(buffer("m")));
        CgRenderPass pass = surfacePass();
        pass.setBindingSet(0, viewSet);                  // before the pipeline: bound with it
        pass.setPipeline(pipeline(CgPipelineLayout.of(view, material), TWO_SETS));
        pass.setBindingSet(1, stone);
        pass.draw(3, 1, 0, 0);
        pass.end();
        endFrame();
    }

    @Test
    public void aDrawReadsItsPushConstants() {
        CgRenderPass pass = surfacePass();
        pass.setPipeline(pipeline(new CgPipelineLayout(List.of(), 8), CONSTANTS));
        pass.pushConstants(0, 3);
        pass.pushConstants(4, ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).putInt(0, 5));
        pass.draw(3, 1, 0, 0);
        pass.end();
        endFrame();
    }

    @Test
    public void aDispatchReadsItsSetsAndConstants() {
        CgBindingLayout params = layout("params", CgBindingLayout.Type.UNIFORM_BUFFER, false);
        CgBindingLayout out = layout("out", CgBindingLayout.Type.STORAGE_BUFFER, true);
        CgComputePipeline kernel = rhi().createComputePipeline("contract",
                module(CgShaderModule.Stage.COMPUTE, KERNEL), new CgPipelineLayout(List.of(params, out), 4));
        CgComputePass pass = rhi().encoder().beginCompute("contract");
        pass.setPipeline(kernel);
        pass.setBindingSet(0, rhi().createBindingSet("params", params, uniform(buffer("p"))));
        pass.pushBindings(new CgBindings().buffer(0, CgBindingLayout.Type.STORAGE_BUFFER, buffer("o"), 0, 256));
        pass.pushConstants(0, 2);
        pass.dispatch(1, 1, 1);
        pass.end();
        endFrame();
    }

    @Test
    public void dispatchesInAStorageOverlapThenATransitionToTheirReader() {
        CgBindingLayout params = layout("params", CgBindingLayout.Type.UNIFORM_BUFFER, false);
        CgBindingLayout out = layout("out", CgBindingLayout.Type.STORAGE_BUFFER, true);
        CgComputePipeline kernel = rhi().createComputePipeline("contract",
                module(CgShaderModule.Stage.COMPUTE, KERNEL), new CgPipelineLayout(List.of(params, out), 4));
        CgGpuBuffer written = buffer("written");
        CgCommandList cmd = rhi().encoder();
        cmd.beginStorageOverlap();
        CgComputePass pass = cmd.beginCompute("contract");
        pass.setPipeline(kernel);
        pass.setBindingSet(0, rhi().createBindingSet("params", params, uniform(buffer("p"))));
        pass.pushBindings(new CgBindings().buffer(0, CgBindingLayout.Type.STORAGE_BUFFER, written, 0, 256));
        for (int i = 0; i < 4; i++) {
            pass.pushConstants(0, i);                     // disjoint words: no barrier between
            pass.dispatch(1, 1, 1);
        }
        pass.end();
        cmd.endStorageOverlap();
        CgTransition moves = new CgTransition().buffer(written, CgAccess.COMPUTE_WRITE, CgAccess.UNIFORM_READ);
        cmd.transition(moves);
        endFrame();
    }

    @Test
    public void aSplitTransitionAroundOtherWork() {
        CgGpuBuffer written = buffer("written"), other = buffer("other");
        CgGpuTexture target = rhi().createTexture(new CgGpuTexture.Desc("target", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, 16, 16, 1, 1, 1, CgGpuTexture.Usage.ALL));
        CgCommandList cmd = rhi().encoder();
        CgTransition moves = new CgTransition()
                .buffer(written, CgAccess.COPY_WRITE, CgAccess.UNIFORM_READ)
                .texture(CgTextureView.whole(target), CgAccess.COLOR_WRITE, CgAccess.SAMPLED_READ);
        cmd.fillBuffer(written, 0, 256, 7);
        cmd.beginTransitions(moves);
        cmd.fillBuffer(other, 0, 256, 9);                 // touches neither
        cmd.endTransitions(moves);
        CgTransition back = new CgTransition()
                .texture(CgTextureView.whole(target), CgAccess.SAMPLED_READ, CgAccess.COLOR_WRITE);
        cmd.transition(back);
        endFrame();
        rhi().release(target);
    }

    @Test
    public void aReadOnlyDepthPassTestsWithoutWriting() {
        CgFormat depth = rhi().surfaceDepth().desc().format();
        CgGraphicsPipelineDesc.DepthStencil test = new CgGraphicsPipelineDesc.DepthStencil(true, false, CgCompare.LESS_EQUAL,
                false, CgGraphicsPipelineDesc.StencilFace.KEEP, CgGraphicsPipelineDesc.StencilFace.KEEP, 0xFF, 0);
        CgGraphicsPipeline testing = rhi().createPipeline(new CgGraphicsPipelineDesc("depth test", CgPipelineLayout.of(),
                module(CgShaderModule.Stage.VERTEX, VERTEX), module(CgShaderModule.Stage.FRAGMENT, NOTHING), List.of(),
                CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT, test, List.of(), depth, 1));
        int w = rhi().surfaceDepth().desc().width(), h = rhi().surfaceDepth().desc().height();
        CgRenderPass pass = rhi().encoder().beginPass(new CgPassDesc("depth read", List.of(),
                new CgPassDesc.Depth(CgTextureView.whole(rhi().surfaceDepth()), CgPassDesc.LoadOp.LOAD, 1,
                        CgPassDesc.LoadOp.LOAD, 0, CgPassDesc.StoreOp.STORE, true), w, h));
        pass.setViewport(0, 0, w, h, 0, 1);
        pass.setScissor(0, 0, w, h);
        pass.setPipeline(testing);
        pass.draw(3, 1, 0, 0);
        pass.end();
        endFrame();
    }

    private CgGpuTexture sampled(String label) {
        return rhi().createTexture(new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM, 4, 4, 1, 1, 1,
                CgGpuTexture.Usage.SAMPLED_UPLOADED));
    }

    /** Two textures drawn by one pipeline through the heap, each named by an index pushed per draw. */
    @Test
    public void drawsTexturesThroughTheBindlessHeap() {
        assumeTrue("no bindless heap", rhi().globals().bindless());
        CgGpuTexture a = sampled("a"), b = sampled("b");
        CgTextureView va = CgTextureView.whole(a), vb = CgTextureView.whole(b);
        CgCommandList cmd = rhi().encoder();
        cmd.transition(new CgTransition().texture(va, CgAccess.RESTING, CgAccess.SAMPLED_READ)
                .texture(vb, CgAccess.RESTING, CgAccess.SAMPLED_READ));
        CgGpuSampler sampler = rhi().createSampler(CgGpuSampler.Desc.DEFAULT);
        int ia = rhi().bindlessIndex(va, sampler), ib = rhi().bindlessIndex(vb, sampler);
        assertNotEquals("two views, two indices", ia, ib);
        assertEquals("asked again, the same index", ia, rhi().bindlessIndex(CgTextureView.whole(a), sampler));
        CgBindingLayout none = rhi().createBindingLayout("none", List.of(), false);
        CgRenderPass pass = surfacePass();
        pass.setPipeline(pipeline(new CgPipelineLayout(List.of(none, none, none, rhi().bindlessLayout()), 4), BINDLESS));
        pass.setBindingSet(CgRHI.BINDLESS_SET, rhi().bindlessSet());
        for (int index : new int[] {ia, ib}) {
            pass.pushConstants(0, index);
            pass.draw(3, 1, 0, 0);
        }
        pass.end();
        cmd.transition(new CgTransition().texture(va, CgAccess.SAMPLED_READ, CgAccess.RESTING)
                .texture(vb, CgAccess.SAMPLED_READ, CgAccess.RESTING));
        endFrame();
        rhi().release(a);
        framesUntil(() -> false);
        CgGpuTexture c = sampled("c");
        assertEquals("a released texture's index, taken by the next view once its frames retired", ia,
                rhi().bindlessIndex(CgTextureView.whole(c), sampler));
        rhi().release(b);
        rhi().release(c);
        endFrame();
    }

    /** Ends frames, never waiting, until {@code landed} holds: a few at most, as frames in flight retire. */
    private void framesUntil(BooleanSupplier landed) {
        for (int i = 0; i < 8 && !landed.getAsBoolean(); i++) rhi().endFrame();
    }

    @Test
    public void aReadbackLandsFramesLaterWithoutAStall() {
        CgGpuBuffer counts = buffer("counts");
        CgGpuReadback back = new CgGpuReadback(rhi(), "counts back", 64);
        CgCommandList cmd = rhi().encoder();
        cmd.fillBuffer(counts, 0, 64, 7);
        cmd.transition(new CgTransition().buffer(counts, CgAccess.COPY_WRITE, CgAccess.COPY_READ));
        back.enqueue(cmd, counts, 0, 64);
        assertFalse("not in the frame that copied it", back.ready());
        endFrame();
        framesUntil(back::ready);
        assertTrue("landed within the frames in flight", back.ready());
        assertEquals(7, back.data().order(ByteOrder.nativeOrder()).getInt(60));
        back.release();
        checkFrame();
    }

    @Test
    public void aTimerAnswersOnceItsFrameRetires() {
        CgTimerQuery timer = rhi().createTimerQuery("contract");
        CgCommandList cmd = rhi().encoder();
        cmd.beginTimer(timer);
        CgRenderPass pass = surfacePass();
        pass.setPipeline(pipeline(CgPipelineLayout.of(), WHITE));
        pass.draw(3, 1, 0, 0);
        pass.end();
        cmd.endTimer(timer);
        assertEquals("no answer while in flight", -1, timer.resultNanos());
        endFrame();
        framesUntil(() -> timer.resultNanos() >= 0);
        assertTrue(timer.resultNanos() >= 0);
        rhi().release(timer);
        checkFrame();
    }

    @Test
    public void indirectDrawsOfManyCommands() {
        CgGpuBuffer args = buffer("args");                // zeroed: draws of nothing
        CgRenderPass pass = surfacePass();
        pass.setPipeline(pipeline(CgPipelineLayout.of(), WHITE));
        pass.drawIndirect(args, 0, 2, 16);
        if (rhi().globals().indirectCount()) pass.drawIndirectCount(args, 0, args, 64, 2, 16);
        pass.end();
        endFrame();
    }
}
