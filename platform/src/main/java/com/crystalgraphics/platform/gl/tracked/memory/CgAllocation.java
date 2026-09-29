package com.crystalgraphics.platform.gl.tracked.memory;

import com.crystalgraphics.platform.device.resource.CgGpuBuffer;

import java.nio.ByteBuffer;

/**
 * A range of a device buffer: a slab's sub-allocation or a dedicated buffer. It records the last frame that read
 * or wrote it on the GPU, which is what decides whether the CPU may still write it.
 */
public final class CgAllocation {

    public final CgSlabAllocator owner;
    public final CgGpuBuffer buffer;
    public final long offset;
    final long size;
    final boolean dedicated;
    public long lastUse = -1;

    CgAllocation(CgSlabAllocator owner, CgGpuBuffer buffer, long offset, long size, boolean dedicated) {
        this.owner = owner;
        this.buffer = buffer;
        this.offset = offset;
        this.size = size;
        this.dedicated = dedicated;
    }

    public CgGpuBuffer buffer() { return buffer; }

    /** Where the range starts in {@link #buffer()}. */
    public long offset() { return offset; }

    public long size() { return size; }

    public boolean hostVisible() { return buffer.hostVisible(); }

    /** The newest frame whose GPU work used it; -1 if none has. */
    public long lastUse() { return lastUse; }

    /** The range's memory, positioned at 0 and limited to its size. Host-visible only. */
    public ByteBuffer memory() {
        ByteBuffer b = buffer.mapped().duplicate();
        b.limit((int) (offset + size));
        b.position((int) offset);
        return b.slice().order(buffer.mapped().order());
    }
}
