package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;

import java.nio.ByteBuffer;

/**
 * Package-private {@link CgVertexOutput} that writes into a direct {@link ByteBuffer}.
 *
 * <p>Used by {@link CgVertexWriter#forBuffer(ByteBuffer, CgVertexFormat)} to enable
 * mesh builders and loaders to pack vertex data via the same semantic-aware writer
 * without requiring a {@link CgStagingBuffer}.</p>
 *
 * <p>Color values are stored as {@code Float.intBitsToFloat(abgr)}, matching the
 * exact layout expected by {@link CgStagingBuffer#putIntBits(int)}.</p>
 */
final class CgStagingByteBuffer implements CgVertexOutput {

    private final ByteBuffer buf;

    CgStagingByteBuffer(ByteBuffer buf) {
        this.buf = buf;
    }

    @Override
    public void putFloat(float v) {
        buf.putFloat(v);
    }

    @Override
    public void putIntBits(int i) {
        buf.putFloat(Float.intBitsToFloat(i));
    }
}
