package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;

/**
 * Compatibility fallback streaming path using CPU staging + glBufferSubData.
 */
public class SubDataStreamBuffer extends CgStreamBuffer {

    private ByteBuffer staging;

    public SubDataStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        this.staging = CgBufferUtils.createByteBuffer(capacityBytes);
        bind();
        CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
        unbind();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        if (sizeBytes > capacityBytes) {
            capacityBytes = sizeBytes;
            staging = CgBufferUtils.createByteBuffer(capacityBytes);
            bind();
            CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
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
        CgGL.glBufferSubData(target, 0, staging);
        unbind();
        return 0;
    }

    @Override
    public void deleteGlResources() {
        CgGL.glDeleteBuffers(glBuffer);
    }
}
