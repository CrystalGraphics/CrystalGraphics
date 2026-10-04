package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * gpu-compute C3 on the tracked backend (tier V): a barrier the frame graph derives is recorded as it is, on the
 * buffer's device memory, and a fill of device-local storage is a device fill. C8: async work reaches the device
 * bracketed, in order.
 */
public class CgTrackedComputeTest {

    @Test
    public void aBarrierIsRecordedExactly_andAFillIsADeviceFill() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        int buffer = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        gl.glBufferStorage(CgGL.GL_COPY_WRITE_BUFFER, 256, 0);
        gl.cgFillBuffer(buffer, 0, 256, 7);
        gl.cgBufferBarrier(buffer, CgAccess.COPY_WRITE | CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
        gl.endFrame();
        String log = String.join(" | ", device.log());
        assertTrue(log, log.contains("fillBuffer"));
        assertTrue(log, log.contains("bufferBarrier") && log.contains("COMPUTE_WRITE|COPY_WRITE VERTEX_READ"));
    }

    @Test
    public void asyncWorkIsBracketedOnTheDevice_andTheWaitForItFollows() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        int buffer = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        gl.glBufferStorage(CgGL.GL_COPY_WRITE_BUFFER, 256, 0);
        gl.cgBeginAsync();
        gl.cgFillBuffer(buffer, 0, 256, 7);
        long point = gl.cgEndAsync();
        gl.cgWaitAsync(point);
        gl.endFrame();
        String log = String.join(" | ", device.log());
        int begin = log.indexOf("beginAsync"), fill = log.indexOf("fillBuffer"), end = log.indexOf("endAsync " + point);
        assertTrue(log, begin >= 0 && begin < fill && fill < end && end < log.indexOf("waitAsync " + point));
        assertEquals(1, gl.stats().asyncSections);
    }

    @Test
    public void aFrameCannotEndInsideAsyncWork() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        gl.cgBeginAsync();
        assertThrows(IllegalStateException.class, gl::endFrame);
    }

    @Test
    public void aFillOfPartWordsIsRefused() {
        CgRecordingDevice device = new CgRecordingDevice(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        int buffer = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        gl.glBufferStorage(CgGL.GL_COPY_WRITE_BUFFER, 256, 0);
        gl.cgFillBuffer(buffer, 2, 8, 0);
        assertEquals(CgGL.GL_INVALID_VALUE, gl.glGetError());
    }
}
