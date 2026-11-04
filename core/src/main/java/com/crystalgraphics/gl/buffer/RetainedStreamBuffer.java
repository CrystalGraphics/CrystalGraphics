package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Shader-buffer storage readable until the next upload, however many frames apart: each upload is written into one
 * shared frame ring and copied on the GPU into this buffer's own storage at offset 0, Unreal's {@code RHIUpdateBuffer}
 * and Vulkan's staging upload. {@link CgStreamBuffer#createForShaderBuffer} makes one; it is used as any stream.
 *
 * <pre>{@code
 * CgStreamBuffer storage = CgStreamBuffer.createForShaderBuffer(CgGL.GL_UNIFORM_BUFFER, bytes);
 * storage.uploadFloats(block, n);                       // lands at 0, readable until the next upload
 * CgGL.glBindBufferBase(CgGL.GL_UNIFORM_BUFFER, binding, storage.getGlBuffer());
 * }</pre>
 *
 * <ul>
 *   <li>Render thread: the staging ring and the copy are GL calls.</li>
 *   <li>An upload replaces the whole contents: storage grown for a larger one starts empty.</li>
 * </ul>
 */
final class RetainedStreamBuffer extends CgStreamBuffer {

    /** Where every retained upload is written before its copy: regions reused three frames on, after the copy ran. */
    private static CgStreamBuffer staging;

    private static final int STAGING_START_BYTES = 64 << 10;

    RetainedStreamBuffer(int target, int capacityBytes) {
        super(target, capacityBytes);
        bind();
        CgGL.glBufferData(target, capacityBytes, CgGL.GL_DYNAMIC_DRAW);
        unbind();
    }

    private static CgStreamBuffer staging() {
        if (staging == null || staging.isDeleted()) staging = create(CgGL.GL_COPY_READ_BUFFER, STAGING_START_BYTES);
        return staging;
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        return staging().map(sizeBytes);
    }

    @Override
    public FloatBuffer mapFloats(int sizeBytes) {
        return staging().mapFloats(sizeBytes);
    }

    @Override
    public IntBuffer mapInts(int sizeBytes) {
        return staging().mapInts(sizeBytes);
    }

    @Override
    public void write(byte[] src, int from, int count) {
        staging().write(src, from, count);
    }

    @Override
    public int commit(int usedBytes) {
        int from = staging.commit(usedBytes);
        if (usedBytes > capacityBytes) {
            capacityBytes = Math.max(usedBytes, capacityBytes * 2);
            bind();
            CgGL.glBufferData(target, capacityBytes, CgGL.GL_DYNAMIC_DRAW);
        }
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, staging.getGlBuffer());
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, glBuffer);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, from, 0, usedBytes);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        writeOffset = 0;
        return 0;
    }

    @Override
    protected void deleteGlResources() {
        CgGL.glDeleteBuffers(glBuffer);
    }

    /** The shared staging ring, at context teardown. */
    static void releaseStaging() {
        if (staging != null) staging.delete();
        staging = null;
    }
}
