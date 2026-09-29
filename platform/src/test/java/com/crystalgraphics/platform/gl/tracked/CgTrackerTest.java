package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.*;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.Assert.*;

/** The tracker's spine, each on a recording device: load ops, pass breaks, renames, deferred frees, feedback. */
public class CgTrackerTest {

    private final CgRecordingDevice device = new CgRecordingDevice(64, 64);
    private final CgTracker tracker = new CgTracker(device, true);

    private CgTrackedProgram program(CgBindingLayout.Slot... slots) {
        CgBindingLayout layout = device.createBindingLayout("p", List.of(slots));
        CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, "#version 450\n", "vs");
        CgShaderModule fs = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, "#version 450\n", "fs");
        return new CgTrackedProgram("p", layout, vs, vs, fs);
    }

    private void drawTriangle() {
        tracker.draw(CgPipelineDesc.Topology.TRIANGLES, 3, 1, 0, 0);
    }

    private static ByteBuffer bytes(int... values) {
        ByteBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        for (int v : values) b.putInt(v);
        b.flip();
        return b;
    }

    @Test
    public void aClearBeforeTheFirstDrawIsThePassesLoadOp() {
        tracker.bindTarget(CgTarget.surface(device));
        tracker.state.program = program();
        tracker.clear(true, 0, 0, 0, 1, false, 1, false, 0);
        drawTriangle();

        assertEquals(CgPassDesc.LoadOp.CLEAR, device.passes().get(0).colors().get(0).load());
        assertFalse("no attachment clear inside the pass", device.log().stream().anyMatch(l -> l.startsWith("clearColor")));

        tracker.clear(true, 1, 0, 0, 1, false, 1, false, 0);
        assertTrue("after a draw, a clear is inside the pass", device.log().get(device.log().size() - 1).startsWith("clearColor"));
    }

    @Test
    public void anUploadMidPassBreaksItAndTheNextDrawResumesWithLoad() {
        CgGpuTexture atlas = device.createTexture(new CgGpuTexture.Desc("atlas", CgGpuTexture.Kind.D2,
                CgFormat.R8_UNORM, 4, 4, 1, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED));
        tracker.bindTarget(CgTarget.surface(device));
        tracker.state.program = program();
        tracker.clear(true, 0, 0, 0, 1, false, 1, false, 0);
        drawTriangle();
        int mark = device.mark();
        tracker.transfer().writeTexture(atlas, CgTextureRegion.of2D(0, 0, 0, 4, 4), ByteBuffer.allocateDirect(16));
        drawTriangle();

        List<String> log = device.logSince(mark);
        assertEquals("endPass", log.get(0));
        assertTrue(log.get(1).startsWith("writeTexture"));
        assertTrue(log.get(2).startsWith("beginPass"));
        assertEquals(CgPassDesc.LoadOp.LOAD, device.passes().get(1).colors().get(0).load());
        assertEquals(1, tracker.stats().passBreaks);
    }

    @Test
    public void dataWrittenIntoADrawnFromBufferGoesToFreshMemory() {
        CgTrackedBuffer vbo = new CgTrackedBuffer(tracker, "vbo");
        vbo.data(16, bytes(1, 2, 3, 4), true);
        CgAllocation before = vbo.allocation();
        vbo.subData(0, bytes(9));
        assertSame("nothing has read it: written in place", before, vbo.allocation());

        tracker.bindTarget(CgTarget.surface(device));
        tracker.state.program = program();
        tracker.state.vertexLayouts = List.of(new CgPipelineDesc.VertexBuffer(0, 4, false,
                List.of(new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32, 0))));
        tracker.state.vertexBuffer(0, vbo.allocation(), 0);
        drawTriangle();

        vbo.subData(4, bytes(7));
        assertNotSame("the draw still reads the old memory", before, vbo.allocation());
        assertEquals("the draw's bytes are untouched", 2, before.memory().getInt(4));
        assertEquals("the rest was carried over", 9, vbo.allocation().memory().getInt(0));
        assertEquals(7, vbo.allocation().memory().getInt(4));
        assertEquals(1, tracker.stats().renames);
    }

    @Test
    public void aDeleteIsFreedOnlyAfterItsFrameRetires() {
        CgGpuTexture used = device.createTexture(new CgGpuTexture.Desc("used", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, 4, 4, 1, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED));
        CgTrackedBuffer ubo = new CgTrackedBuffer(tracker, "ubo");
        ubo.data(64, null, true);
        tracker.bindTarget(CgTarget.surface(device));
        tracker.state.program = program(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                new CgBindingLayout.Slot(1, CgBindingLayout.Type.SAMPLED_TEXTURE));
        tracker.state.clearBindings().uniform(0, ubo.allocation(), 0, 64)
                .texture(1, CgTextureView.whole(used), device.createSampler(CgGpuSampler.Desc.GL_DEFAULT));
        drawTriangle();
        CgAllocation freed = ubo.allocation();

        tracker.release(used);
        ubo.release();
        CgTrackedBuffer next = new CgTrackedBuffer(tracker, "next");
        next.data(64, null, true);
        assertFalse("the range is still read by this frame", freed.buffer() == next.allocation().buffer()
                && freed.offset() == next.allocation().offset());

        tracker.endFrame();
        assertFalse(device.isDestroyed(used));
        device.retireAll();
        assertTrue(device.isDestroyed(used));
        CgTrackedBuffer again = new CgTrackedBuffer(tracker, "again");
        again.data(64, null, true);
        assertEquals("retired: the range is reused", freed.offset(), again.allocation().offset());
    }

    @Test
    public void samplingATextureThePassRendersToIsRefusedInDebug() {
        CgGpuTexture layer = device.createTexture(new CgGpuTexture.Desc("layer", CgGpuTexture.Kind.D2,
                CgFormat.RGBA8_UNORM, 16, 16, 1, 1, 1, CgGpuTexture.Usage.ALL));
        tracker.bindTarget(new CgTarget(List.of(CgTextureView.whole(layer)), null));
        tracker.state.program = program(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE));
        tracker.state.clearBindings().texture(0, CgTextureView.whole(layer),
                device.createSampler(CgGpuSampler.Desc.GL_DEFAULT));
        try {
            drawTriangle();
            fail("expected a feedback loop to be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("layer"));
        }
    }
}
