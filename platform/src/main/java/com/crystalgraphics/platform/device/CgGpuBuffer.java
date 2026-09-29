package com.crystalgraphics.platform.device;

import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;

/**
 * A device buffer. Host-visible ones are mapped for their whole life; device-local ones are written through
 * {@link CgCommandEncoder#writeBuffer}.
 *
 * <pre>{@code
 * CgGpuBuffer slab = device.createBuffer(new CgGpuBuffer.Desc("slab", 1 << 20, CgGpuBuffer.Usage.ALL, true));
 * slab.mapped().putFloat(offset, 1f);   // absolute access only: the buffer is shared
 * }</pre>
 */
public interface CgGpuBuffer extends CgDeviceObject {

    enum Usage {
        VERTEX, INDEX, UNIFORM, STORAGE, TEXEL, COPY_SRC, COPY_DST;

        /** Every usage: a slab that sub-allocations of any kind share. */
        public static final Set<Usage> ALL = EnumSet.allOf(Usage.class);
    }

    record Desc(String label, long size, Set<Usage> usage, boolean hostVisible) {}

    long size();

    boolean hostVisible();

    /**
     * The whole buffer's memory, host-visible buffers only. Writes land at once, so a region the GPU may still
     * read must not be written: the tracked backend writes only regions no unretired frame used.
     *
     * @throws IllegalStateException for a device-local buffer
     */
    ByteBuffer mapped();
}
