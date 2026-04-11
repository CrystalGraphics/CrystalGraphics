package io.github.somehussar.crystalgraphics.gl.buffer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

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
        GL15.glBufferData(target, capacityBytes, GL15.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        bind();
        if (sizeBytes > capacityBytes) {
            GL15.glBufferData(target, sizeBytes, GL15.GL_STREAM_DRAW);
            capacityBytes = sizeBytes;
        }
        ByteBuffer mapped = GL30.glMapBufferRange(target, 0, sizeBytes,
                GL30.GL_MAP_WRITE_BIT | GL30.GL_MAP_INVALIDATE_BUFFER_BIT, null);
        
        if (mapped == null) throw new IllegalStateException("glMapBufferRange (orphan) returned null (size=" + sizeBytes + ")");
        return mapped;
    }

    @Override
    public int commit(int usedBytes) {
        GL15.glUnmapBuffer(target);
        return 0; // orphaning resets to offset 0
    }

    @Override
    public void delete() {
        GL15.glDeleteBuffers(glBuffer);
    }
}
