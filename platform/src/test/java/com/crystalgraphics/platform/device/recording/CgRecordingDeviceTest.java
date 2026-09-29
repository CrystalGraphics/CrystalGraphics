package com.crystalgraphics.platform.device.recording;

import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** The recording device refuses what Vulkan's validation would, so a tracker bug fails a headless test. */
public class CgRecordingDeviceTest {

    private final CgRecordingDevice device = new CgRecordingDevice(64, 64);

    private CgGpuBuffer buffer() {
        return device.createBuffer(new CgGpuBuffer.Desc("b", 256, CgGpuBuffer.Usage.ALL, true));
    }

    private CgPassDesc surfacePass() {
        return new CgPassDesc("fbo0", List.of(new CgPassDesc.Color(CgTextureView.whole(device.surfaceColor()),
                CgPassDesc.LoadOp.LOAD, 0, 0, 0, 0, CgPassDesc.StoreOp.STORE)), null, 64, 64);
    }

    private CgPipeline pipeline(CgBindingLayout layout) {
        CgShaderModule vs = device.createShaderModule(CgShaderModule.Stage.VERTEX, CgRecordingDevice.emptySpirv(), "vs");
        CgShaderModule fs = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, CgRecordingDevice.emptySpirv(), "fs");
        return device.createPipeline(new CgPipelineDesc("p", layout, vs, fs, List.of(),
                CgPipelineDesc.Topology.TRIANGLES, CgPipelineDesc.Raster.DEFAULT, CgPipelineDesc.DepthStencil.OFF,
                List.of(new CgPipelineDesc.ColorTarget(CgFormat.RGBA8_UNORM, null, 0xF)), null, 1));
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
        try {
            b.mapped();
            fail("expected use after destroy to be refused");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void aTransferInsideAPassIsRefused() {
        CgRenderPass pass = device.encoder().beginPass(surfacePass());
        try {
            device.encoder().writeBuffer(buffer(), 0, ByteBuffer.allocate(4));
            fail("expected a transfer inside a pass to be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("inside pass"));
        }
        pass.end();
    }

    @Test
    public void aDrawMissingABindingItsPipelineReadsIsRefused() {
        CgBindingLayout layout = device.createBindingLayout("l",
                List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER)));
        CgRenderPass pass = device.encoder().beginPass(surfacePass());
        pass.setPipeline(pipeline(layout));
        try {
            pass.draw(3, 1, 0, 0);
            fail("expected the missing binding to be refused");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("binding 0"));
        }
        pass.pushBindings(new CgBindings().buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, buffer(), 0, 64));
        pass.draw(3, 1, 0, 0);
        pass.end();
        assertEquals(1, device.draws());
    }

    @Test
    public void aHostedDeviceRefusesToWaitForAFrame() {
        CgRecordingDevice hosted = new CgRecordingDevice(8, 8, 2, false);
        try {
            hosted.waitRetired(0);
            fail("a hosted device's host submits; waiting would deadlock");
        } catch (IllegalStateException expected) {
        }
    }
}
