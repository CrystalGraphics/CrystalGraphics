package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Shader-buffer storage readable until the next upload, however many frames apart: a device-local buffer, each upload
 * written into one shared frame ring and copied on the GPU into it at offset 0, Unreal's {@code RHIUpdateBuffer} and
 * Vulkan's staging upload. {@link CgStreamBuffer#createForShaderBuffer} makes one; it is used as any stream.
 *
 * <pre>{@code
 * CgStreamBuffer storage = CgStreamBuffer.createForShaderBuffer(CgStreamBuffer.Use.UNIFORM, bytes);
 * storage.uploadFloats(block, n);                       // lands at 0, readable until the next upload
 * contents.buffer(binding, UNIFORM_BUFFER, storage.gpuBuffer(), 0, n * 4);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread: the copy is the device's, outside any pass.</li>
 *   <li>An upload replaces the whole contents: storage grown for a larger one is a new buffer, its first bytes the
 *       upload's.</li>
 * </ul>
 */
final class RetainedStreamBuffer extends CgStreamBuffer {

    /** Where every retained upload is written before its copy: regions reused three frames on, after the copy ran. */
    private static CgStreamBuffer staging;

    private static final int STAGING_START_BYTES = 64 << 10;

    RetainedStreamBuffer(Use use, int capacityBytes) {
        super(use, Math.max(capacityBytes, 16));
        gpu = make(this.capacityBytes, false);
    }

    private static CgStreamBuffer staging() {
        if (staging == null || staging.isDeleted()) staging = create(Use.STAGING, STAGING_START_BYTES);
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
        fit(usedBytes);
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().copyBuffer(staging.gpuBuffer(), from, gpu, 0, usedBytes);
        } finally {
            CgDeviceSection.end();
        }
        writeOffset = 0;
        return 0;
    }

    @Override
    public void uploadFrom(CgUploadLease lease) {
        int size = lease.size();
        fit(size);
        CgDeviceSection.begin();
        try {
            if (lease.direct()) {
                CgRHI.get().encoder().writeBuffer(gpu, 0, lease.range(0, size));
            } else {
                CgRHI.get().encoder().copyBuffer(lease.unpackBuffer(), lease.unpackOffset(), gpu, 0, size);
            }
        } finally {
            CgDeviceSection.end();
        }
        writeOffset = 0;
        committedBytes = size;
    }

    /** A new buffer where {@code bytes} outgrow this one; the old one lives on until the frames reading it retire. */
    private void fit(int bytes) {
        if (bytes <= capacityBytes) return;
        capacityBytes = Math.max(bytes, capacityBytes * 2);
        release(gpu);
        gpu = make(capacityBytes, false);
        generation++;
    }

    /** The shared staging ring, at the device's teardown. */
    static void releaseStaging() {
        if (staging != null) staging.delete();
        staging = null;
    }
}
