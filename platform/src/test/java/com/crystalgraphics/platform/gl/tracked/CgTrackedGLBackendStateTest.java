package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedRenderState;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.junit.Assert.*;

/** Render state on the tracked backend: what GL wrote reads back, and reaches the device as GL would draw it. */
public class CgTrackedGLBackendStateTest {

    private final CgRecordingDevice device = new CgRecordingDevice(32, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);

    @Test
    public void stateReadsBackThroughGlGet() {
        gl.glEnable(CgGL.GL_BLEND);
        gl.glBlendFuncSeparate(CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA, CgGL.GL_ONE, CgGL.GL_ZERO);
        gl.glDepthFunc(CgGL.GL_GEQUAL);
        gl.glColorMaski(0, true, false, true, false);
        gl.glStencilFunc(CgGL.GL_EQUAL, 3, 0x0F);
        gl.glScissor(1, 2, 3, 4);

        assertTrue(gl.glGetBoolean(CgGL.GL_BLEND));
        assertEquals(CgGL.GL_ONE_MINUS_SRC_ALPHA, gl.glGetInteger(CgGL.GL_BLEND_DST_RGB));
        assertEquals(CgGL.GL_ZERO, gl.glGetInteger(CgGL.GL_BLEND_DST_ALPHA));
        assertEquals(CgGL.GL_GEQUAL, gl.glGetInteger(CgGL.GL_DEPTH_FUNC));
        assertEquals(3, gl.glGetInteger(CgGL.GL_STENCIL_REF));
        ByteBuffer mask = ByteBuffer.allocateDirect(4);
        gl.glGetBoolean(CgGL.GL_COLOR_WRITEMASK, mask);
        assertEquals(1, mask.get(0));
        assertEquals(0, mask.get(1));
        IntBuffer box = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).asIntBuffer();
        gl.glGetInteger(CgGL.GL_SCISSOR_BOX, box);
        assertEquals(3, box.get(2));
        box.clear();
        gl.glGetInteger(CgGL.GL_VIEWPORT, box);
        assertEquals("the viewport starts at the surface's size", 32, box.get(2));
    }

    @Test
    public void unchangedStateKeepsItsPipelineRecords() {
        TrackedRenderState state = stateOf();
        gl.glEnable(CgGL.GL_DEPTH_TEST);
        state.sync();
        CgPipelineDesc.DepthStencil before = gl.tracker().state.depthStencil;
        gl.glViewport(0, 0, 8, 8);
        state.sync();
        assertSame(before, gl.tracker().state.depthStencil);
        gl.glDepthMask(false);
        state.sync();
        assertNotSame(before, gl.tracker().state.depthStencil);
    }

    @Test
    public void aClearNoDrawFollowsStillReachesTheDeviceAsALoadOp() {
        gl.glClearColor(0.25f, 0, 0, 1);
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT);
        gl.endFrame();

        CgPassDesc pass = device.passes().get(0);
        assertEquals(CgPassDesc.LoadOp.CLEAR, pass.colors().get(0).load());
        assertEquals(0.25f, pass.colors().get(0).r(), 0f);
        assertEquals(CgPassDesc.LoadOp.CLEAR, pass.depth().depthLoad());
        assertEquals(CgPassDesc.LoadOp.LOAD, pass.depth().stencilLoad());
    }

    @Test
    public void aBadEnumIsAGlErrorOnce() {
        gl.glEnable(0x1234);
        assertEquals(CgGL.GL_INVALID_ENUM, gl.glGetError());
        assertEquals(CgGL.GL_NO_ERROR, gl.glGetError());
    }

    private TrackedRenderState stateOf() {
        return gl.renderState();
    }
}
