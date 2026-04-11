package io.github.somehussar.crystalgraphics.gl.buffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

/**
 * Compatibility fallback streaming path using CPU staging + glBufferSubData.
 */
public class SubDataStreamBuffer extends CgStreamBuffer {

    private ByteBuffer staging;

    public SubDataStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        this.staging = BufferUtils.createByteBuffer(capacityBytes);
        bind();
        GL15.glBufferData(target, capacityBytes, GL15.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        if (sizeBytes > capacityBytes) {
            capacityBytes = sizeBytes;
            staging = BufferUtils.createByteBuffer(capacityBytes);
            bind();
            GL15.glBufferData(target, capacityBytes, GL15.GL_STREAM_DRAW);
            unbind();
        }
        staging.clear();
        staging.limit(sizeBytes);
        return staging;
    }

    @Override
    public int commit(int usedBytes) {
        bind();
        staging.position(0);
        staging.limit(usedBytes);
        GL15.glBufferSubData(target, 0, staging);
        unbind();
        return 0;
    }

    @Override
    public void delete() {
        GL15.glDeleteBuffers(glBuffer);
    }
}
