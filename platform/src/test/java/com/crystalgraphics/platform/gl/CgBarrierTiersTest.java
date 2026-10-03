package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.device.command.CgAccess;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * gpu-compute C3: what a barrier the frame graph derives becomes on GL (G43) — the reader's {@code glMemoryBarrier} bits
 * after a kernel's write, and nothing for a hazard GL orders itself. The tracked backend's exact barrier is
 * {@code CgTrackedComputeTest}'s.
 */
public class CgBarrierTiersTest {

    @Test
    public void aKernelWriteBecomesTheReadersBits_andEveryOtherHazardNothing() {
        RecordingGlBackend gl = new RecordingGlBackend();
        gl.cgBufferBarrier(5, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
        gl.cgBufferBarrier(5, CgAccess.COMPUTE_WRITE, CgAccess.INDIRECT);
        gl.cgImageBarrier(6, CgAccess.COMPUTE_WRITE, CgAccess.SAMPLED_READ);
        gl.cgImageBarrier(6, CgAccess.COMPUTE_WRITE, CgAccess.COMPUTE_READ);
        gl.cgImageBarrier(6, CgAccess.COLOR_WRITE, CgAccess.COMPUTE_READ);
        gl.cgBufferBarrier(5, CgAccess.COMPUTE_READ, CgAccess.COMPUTE_WRITE);
        gl.cgBufferBarrier(5, CgAccess.COPY_WRITE, CgAccess.COMPUTE_READ);
        assertEquals(List.of(CgGL.GL_SHADER_STORAGE_BARRIER_BIT, CgGL.GL_COMMAND_BARRIER_BIT,
                CgGL.GL_TEXTURE_FETCH_BARRIER_BIT, CgGL.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT), gl.memoryBarriers);
    }
}
