package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
    public void aCopyIntoMemoryTheCpuReadsRunsOnTheGpu_andAMapWaitsOnlyForTheFrameThatCopied() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        int counts = gl.glGenBuffers(), staging = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, counts);
        ByteBuffer words = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        words.putInt(7).putInt(8).putInt(9).putInt(10).flip();
        gl.glBufferData(CgGL.GL_COPY_READ_BUFFER, words, CgGL.GL_STATIC_DRAW);       // device-local
        gl.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, staging);
        gl.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, 16, CgGL.GL_STREAM_READ);          // host-visible

        gl.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, 0, 0, 16);
        long fence = gl.glFenceSync(CgGL.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        assertTrue("a device copy", device.log().stream().anyMatch(l -> l.startsWith("copyBuffer")));
        assertFalse("not a read now", device.log().stream().anyMatch(l -> l.startsWith("readBuffer")));
        gl.endFrame();
        device.retireAll();
        assertEquals(CgGL.GL_ALREADY_SIGNALED, gl.glClientWaitSync(fence, 0, 0));
        ByteBuffer landed = gl.glMapBufferRange(CgGL.GL_COPY_WRITE_BUFFER, 0, 16, CgGL.GL_MAP_READ_BIT, null);
        assertEquals(9, landed.getInt(8));
        gl.glUnmapBuffer(CgGL.GL_COPY_WRITE_BUFFER);
        assertFalse("its frame retired: nothing to wait for", device.log().contains("finish"));

        // Read in the frame that copied: the frame's commands run first, and the frame goes on.
        gl.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, 4, 0, 4);
        assertEquals(8, gl.glMapBufferRange(CgGL.GL_COPY_WRITE_BUFFER, 0, 4, CgGL.GL_MAP_READ_BIT, null).getInt(0));
        gl.glUnmapBuffer(CgGL.GL_COPY_WRITE_BUFFER);
        assertTrue(device.log().contains("finish"));
        assertEquals(1, device.frameIndex());

        CgRecordingDevice hosted = new CgRecordingDevice(8, 8, 2, false);
        CgTrackedGLBackend onHost = new CgTrackedGLBackend(hosted, FakeGlslCompiler.EMPTY, true);
        int from = onHost.glGenBuffers(), to = onHost.glGenBuffers();
        onHost.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
        onHost.glBufferData(CgGL.GL_COPY_READ_BUFFER, 16, CgGL.GL_STATIC_DRAW);
        onHost.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, to);
        onHost.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, 16, CgGL.GL_STREAM_READ);
        onHost.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, 0, 0, 16);
        try {
            onHost.glMapBufferRange(CgGL.GL_COPY_WRITE_BUFFER, 0, 16, CgGL.GL_MAP_READ_BIT, null);
            fail("a hosted device's host submits: reading in the frame that copied would deadlock");
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
