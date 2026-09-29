package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import static org.junit.Assert.*;

/** Fences and timers on the tracked backend: a fence is its frame, and nothing here waits inside one by accident. */
public class CgTrackedSyncTest {

    @Test
    public void aFenceIsSignalledWhenItsFrameRetires() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        long fence = gl.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertEquals(CgGL.GL_TIMEOUT_EXPIRED, gl.glClientWaitSync(fence, 0, 0));
        gl.endFrame();
        assertEquals("still in flight", CgGL.GL_TIMEOUT_EXPIRED, gl.glClientWaitSync(fence, 0, 0));
        device.retireAll();
        assertEquals(CgGL.GL_ALREADY_SIGNALED, gl.glClientWaitSync(fence, 0, 0));
    }

    @Test
    public void aBlockingWaitOnTheCurrentFrameSubmitsItAndAHostedDeviceRefuses() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        long fence = gl.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertEquals(CgGL.GL_CONDITION_SATISFIED, gl.glClientWaitSync(fence, CgGL.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000L));
        assertTrue(device.log().contains("endFrame 0"));

        CgRecordingDevice hosted = new CgRecordingDevice(8, 8, 2, false);
        CgTrackedGLBackend onHost = new CgTrackedGLBackend(hosted, FakeGlslCompiler.EMPTY, true);
        long f = onHost.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        try {
            onHost.glClientWaitSync(f, 0, 1_000_000L);
            fail("a hosted device's host submits; waiting would deadlock");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void aTimerAroundADrawLeavesThePassWhole() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        int vs = gl.glCreateShader(CgGL.GL_VERTEX_SHADER), fs = gl.glCreateShader(CgGL.GL_FRAGMENT_SHADER), p = gl.glCreateProgram();
        gl.glAttachShader(p, vs);
        gl.glAttachShader(p, fs);
        gl.glLinkProgram(p);
        gl.glUseProgram(p);
        int q = gl.glGenQuery();
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        gl.glBeginTimeElapsedQuery(q);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        gl.glEndTimeElapsedQuery();
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        gl.endFrame();
        assertEquals(1, device.passes().size());
        assertFalse(gl.glIsQueryResultAvailable(q));
        device.retireAll();
        assertTrue(gl.glIsQueryResultAvailable(q));
    }
}
