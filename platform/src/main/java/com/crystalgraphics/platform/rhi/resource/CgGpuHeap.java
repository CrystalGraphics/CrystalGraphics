package com.crystalgraphics.platform.rhi.resource;

import com.crystalgraphics.platform.rhi.memory.CgTransientMemory;

/**
 * One device memory allocation that textures are placed in at offsets ({@link CgTransientMemory#place}), so textures
 * whose uses never overlap share memory. Released with {@code CgRHI.release} once nothing placed in it is used again.
 */
public interface CgGpuHeap extends CgRHIResource {

    long size();

    /** Its memory type, as an index into a requirement's {@link CgTransientMemory.Requirements#memoryTypes} bits. */
    int memoryType();
}
