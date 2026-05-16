package com.crystalgraphics.gl.buffer;


import com.crystalgraphics.platform.gl.CgGL;
import java.nio.ByteBuffer;

/**
 * Orphan-based streaming VBO using {@code glMapBufferRange} with
 * {@code GL_MAP_INVALIDATE_BUFFER_BIT}. This is Dolphin's Tier B strategy.
 *
 * <p>On each {@link #map} call, the entire buffer is orphaned (driver allocates
 * new backing store). This avoids CPU-GPU sync stalls at the cost of one
 * driver-side allocation per map.</p>
 */
public class MapAndOrphanStreamBuffer extends CgStreamBuffer {

    public MapAndOrphanStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        // Pre-allocate the GL buffer
        bind();
        CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        bind();
        if (sizeBytes > capacityBytes) {
            CgGL.glBufferData(target, sizeBytes, CgGL.GL_STREAM_DRAW);
            capacityBytes = sizeBytes;
        }
        
        ByteBuffer mapped = CgGL.glMapBufferRange(target, 0, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_INVALIDATE_BUFFER_BIT, null);
        
        if (mapped == null) throw new IllegalStateException("glMapBufferRange (orphan) returned null (size=" + sizeBytes + ")");
        return mapped;
    }

    @Override
    public int commit(int usedBytes) {
        CgGL.glUnmapBuffer(target);
        return 0; // orphaning resets to offset 0
    }

    @Override
    public void deleteGlResources() {
        CgGL.glDeleteBuffers(glBuffer);
    }
}
