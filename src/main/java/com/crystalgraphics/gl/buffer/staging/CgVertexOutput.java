package com.crystalgraphics.gl.buffer.staging;

/**
 * Package-private write target for {@link CgVertexWriter}.
 *
 * <p>Abstracts over {@link CgStagingBuffer} (streaming path) and
 * {@link CgStagingByteBuffer} (static mesh / builder path), allowing
 * {@link CgVertexWriter} to write to either without duplication.</p>
 */
interface CgVertexOutput {
    void putFloat(float v);
    void putIntBits(int abgr);
}
