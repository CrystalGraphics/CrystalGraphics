package com.crystalgraphics.api.buffer;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

/**
 * A device buffer an engine type owns: the storage a shader reads, and its delete.
 *
 * <pre>{@code
 * CgGpuBuffer storage = block.gpuBuffer();   // read when binding: growth replaces it
 * block.delete();                            // idempotent; destroyed once the frames reading it retire
 * }</pre>
 */
public interface CgObjectBuffer {

    /** The device buffer holding its storage now; null before it is made. */
    CgGpuBuffer gpuBuffer();

    /** The device name ({@code CgDeviceNames}) of {@link #gpuBuffer()}, as the graph knows it; 0 before it is made. */
    int getGlBufferId();

    /** Frees its storage. Idempotent. */
    void delete();

    /** Whether {@link #delete()} has been called. */
    boolean isDeleted();
}
