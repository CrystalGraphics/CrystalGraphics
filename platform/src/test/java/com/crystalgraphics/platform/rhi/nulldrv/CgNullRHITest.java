package com.crystalgraphics.platform.rhi.nulldrv;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** The null RHI records each command and refuses what Vulkan's validation would, so a caller's bug fails a headless test. */
public class CgNullRHITest {

    private final CgNullRHI device = new CgNullRHI(64, 64);
    private final CgCommandList cmd = device.encoder();

    private CgGpuBuffer buffer() {
        return device.createBuffer(new CgGpuBuffer.Desc("b", 256, CgGpuBuffer.Usage.ALL, true));
    }

    private CgPassDesc surfacePass() {
        return new CgPassDesc("fbo0", List.of(new CgPassDesc.Color(CgTextureView.whole(device.surfaceColor()),
                CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.StoreOp.STORE)), null, 64, 64);
    }

    private CgPassDesc depthPass(boolean readOnly, CgPassDesc.LoadOp load) {
        return new CgPassDesc("depth", List.of(), new CgPassDesc.Depth(CgTextureView.whole(device.surfaceDepth()), load, 1,
                CgPassDesc.LoadOp.LOAD, 0, CgPassDesc.StoreOp.STORE, readOnly), 64, 64);
    }

    private CgBindingLayout layout(String label, boolean pushed) {
        return device.createBindingLayout(label, List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER)), pushed);
    }

    private CgGraphicsPipeline pipeline(CgPipelineLayout layout) {
        return device.createPipeline(desc(layout, List.of(new CgGraphicsPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, null, 0xF)),
                null, CgGraphicsPipelineDesc.DepthStencil.OFF));
    }

    private CgGraphicsPipelineDesc desc(CgPipelineLayout layout, List<CgGraphicsPipelineDesc.ColorTarget> colors,
                                        CgFormat depth, CgGraphicsPipelineDesc.DepthStencil depthStencil) {
        CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, CgNullRHI.emptySpirv(), "vs");
        CgShaderModule fs = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, CgNullRHI.emptySpirv(), "fs");
        return new CgGraphicsPipelineDesc("p", layout, vs, fs, List.of(), CgGraphicsPipelineDesc.Topology.TRIANGLES,
                CgGraphicsPipelineDesc.Raster.DEFAULT, depthStencil, colors, depth, 1);
    }

    private CgBindings uniform() {
        return new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, buffer(), 0, 64);
    }

    private static void refused(String expected, Runnable call) {
        try {
            call.run();
            fail("expected a refusal: " + expected);
        } catch (IllegalStateException | IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    @Test
    public void aReleasedObjectLivesUntilItsFrameRetiresAndIsRefusedAfter() {
        CgGpuBuffer b = buffer();
        device.release(b);
        device.endFrame();
        assertFalse("frames in flight may still read it", device.isDestroyed(b));
        device.endFrame();
        device.endFrame();
        assertTrue(device.isDestroyed(b));
        refused("used after it was destroyed", b::mapped);
    }

    @Test
    public void aTransferInsideAPassIsRefused() {
        CgRenderPass pass = cmd.beginPass(surfacePass());
        refused("inside pass", () -> cmd.writeBuffer(buffer(), 0, ByteBuffer.allocate(4)));
        pass.end();
    }

    @Test
    public void aDrawMissingABindingItsPushedSetReadsIsRefused() {
        CgRenderPass pass = cmd.beginPass(surfacePass());
        pass.setPipeline(pipeline(CgPipelineLayout.of(layout("l", true))));
        refused("binding 0", () -> pass.draw(3, 1, 0, 0));
        pass.pushBindings(uniform());
        pass.draw(3, 1, 0, 0);
        pass.end();
        assertEquals(1, device.draws());
    }

    @Test
    public void aDrawReadsEverySetBoundWithItsOwnLayout() {
        CgBindingLayout view = layout("view", false), material = layout("material", false);
        CgBindingSet viewSet = device.createBindingSet("view0", view, uniform());
        CgBindingSet stone = device.createBindingSet("stone", material, uniform());
        CgRenderPass pass = cmd.beginPass(surfacePass());
        pass.setPipeline(pipeline(CgPipelineLayout.of(view, material)));
        pass.setBindingSet(0, viewSet);
        refused("reads set 1", () -> pass.draw(3, 1, 0, 0));
        pass.setBindingSet(1, viewSet);
        refused("bound there is view", () -> pass.draw(3, 1, 0, 0));
        pass.setBindingSet(1, stone);
        pass.draw(3, 1, 0, 0);
        pass.end();
    }

    @Test
    public void aBindingSetIsOfAnUnpushedLayoutGivenEverySlot() {
        refused("which is pushed", () -> device.createBindingSet("s", layout("push", true), uniform()));
        refused("binding 0", () -> device.createBindingSet("s", layout("set", false), new CgBindings()));
        CgRenderPass pass = cmd.beginPass(surfacePass());
        pass.setPipeline(pipeline(CgPipelineLayout.of(layout("set", false))));
        refused("pushes no set", () -> pass.pushBindings(uniform()));
        pass.end();
    }

    @Test
    public void pushConstantsStayInsideTheLayoutsBytes() {
        refused("a multiple of 4, at most 128", () -> new CgPipelineLayout(List.of(), 132));
        refused("at most one", () -> CgPipelineLayout.of(layout("a", true), layout("b", true)));
        CgRenderPass pass = cmd.beginPass(surfacePass());
        refused("before a pipeline", () -> pass.pushConstants(0, 7));
        pass.setPipeline(pipeline(new CgPipelineLayout(List.of(), 8)));
        pass.pushConstants(4, 7);
        refused("4 pushed at 8", () -> pass.pushConstants(8, 7));
        refused("12 pushed at 0", () -> pass.pushConstants(0, ByteBuffer.allocate(12)));
        pass.draw(3, 1, 0, 0);
        pass.end();
    }

    @Test
    public void aComputePassBindsSetsAndConstantsAsADrawDoes() {
        CgBindingLayout params = layout("params", false);
        CgShaderModule cs = device.createShaderModule(CgShaderModule.Stage.COMPUTE, CgNullRHI.emptySpirv(), "cs");
        CgComputePipeline kernel = device.createComputePipeline("k", cs, new CgPipelineLayout(List.of(params), 4));
        CgComputePass pass = cmd.beginCompute("k");
        pass.setPipeline(kernel);
        pass.pushConstants(0, 64);
        refused("reads set 0", () -> pass.dispatch(1, 1, 1));
        pass.setBindingSet(0, device.createBindingSet("p", params, uniform()));
        pass.dispatch(1, 1, 1);
        pass.end();
    }

    @Test
    public void aSplitTransitionEndsUnchangedBeforeTheFrameEnds() {
        CgGpuBuffer particles = buffer();
        CgTransition moves = new CgTransition().buffer(particles, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
        int mark = device.mark();
        cmd.beginTransitions(moves);
        refused("twice", () -> cmd.beginTransitions(moves));
        refused("transitions begun", device::endFrame);
        cmd.endTransitions(moves);
        assertEquals(2, device.logSince(mark).size());
        refused("never begun", () -> cmd.endTransitions(moves));

        cmd.beginTransitions(moves);
        moves.texture(CgTextureView.whole(device.surfaceColor()), CgAccess.COLOR_WRITE, CgAccess.SAMPLED_READ);
        refused("changed since", () -> cmd.endTransitions(moves));

        CgTransition later = new CgTransition().buffer(particles, CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT);
        CgRenderPass pass = cmd.beginPass(surfacePass());
        refused("inside pass", () -> cmd.transition(later));
        pass.end();
        cmd.transition(later);
    }

    @Test
    public void aTransitionRefusesAccessItsKindOfResourceHasNot() {
        refused("a buffer moving", () -> new CgTransition().buffer(buffer(), CgAccess.COMPUTE_WRITE, CgAccess.COLOR_WRITE));
        refused("an image moving", () -> new CgTransition().texture(CgTextureView.whole(device.surfaceColor()),
                CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT));
    }

    @Test
    public void aStorageOverlapIsOneLevelDeepAndClosedBeforeTheFrameEnds() {
        refused("none begun", cmd::endStorageOverlap);
        cmd.beginStorageOverlap();
        refused("inside a storage overlap", cmd::beginStorageOverlap);
        refused("inside a storage overlap", device::endFrame);
        cmd.endStorageOverlap();
        CgRenderPass pass = cmd.beginPass(surfacePass());
        refused("inside pass", cmd::beginStorageOverlap);
        pass.end();
        device.endFrame();
    }

    @Test
    public void aReadOnlyDepthPassNeitherClearsNorWritesIt() {
        refused("clears a read-only", () -> cmd.beginPass(depthPass(true, CgPassDesc.LoadOp.CLEAR)));
        CgPipelineLayout none = CgPipelineLayout.of();
        CgGraphicsPipelineDesc.DepthStencil test = new CgGraphicsPipelineDesc.DepthStencil(true, false, CgCompare.LESS, false,
                CgGraphicsPipelineDesc.StencilFace.KEEP, CgGraphicsPipelineDesc.StencilFace.KEEP, 0xFF, 0xFF);
        CgGraphicsPipelineDesc.DepthStencil write = new CgGraphicsPipelineDesc.DepthStencil(true, true, CgCompare.LESS, false,
                CgGraphicsPipelineDesc.StencilFace.KEEP, CgGraphicsPipelineDesc.StencilFace.KEEP, 0xFF, 0xFF);
        CgGraphicsPipeline testing = device.createPipeline(desc(none, List.of(), CgFormat.DEPTH24_PLUS_STENCIL8, test));
        CgGraphicsPipeline writing = device.createPipeline(desc(none, List.of(), CgFormat.DEPTH24_PLUS_STENCIL8, write));
        CgRenderPass pass = cmd.beginPass(depthPass(true, CgPassDesc.LoadOp.LOAD));
        refused("writes depth or stencil", () -> pass.setPipeline(writing));
        refused("read-only", () -> pass.clearDepthStencil(true, 1, false, 0, 0, 0, 64, 64));
        pass.setPipeline(testing);
        pass.draw(3, 1, 0, 0);
        pass.end();
    }

    @Test
    public void anIndirectDrawOfManyStridesAtLeastOneCommand() {
        CgGpuBuffer args = buffer();
        CgRenderPass pass = cmd.beginPass(surfacePass());
        pass.setPipeline(pipeline(CgPipelineLayout.of()));
        refused("stride 8", () -> pass.drawIndirect(args, 0, 2, 8));
        pass.drawIndirect(args, 0, 2, 16);
        pass.drawIndirect(args, 0, 1, 0);
        pass.end();
    }

    @Test
    public void aHostedDeviceRefusesToWaitForAFrame() {
        CgNullRHI hosted = new CgNullRHI(8, 8, 2, false);
        refused("cannot wait", () -> hosted.waitRetired(0));
    }
}
