package com.crystalgraphics.platform.device.resource;

import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.command.CgCommandEncoder;

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
 *
 * // read back by the CPU: memory the CPU caches
 * CgGpuBuffer staging = device.createBuffer(new CgGpuBuffer.Desc("readback", size, CgGpuBuffer.Usage.ALL, true, true));
 * }</pre>
 *
 * <p>A host-visible buffer without {@code hostReads} may sit in write-combined memory, where the CPU writes at full
 * speed and reads perhaps a hundred times slower: a 4 MB readback read pixel by pixel took a second on NVIDIA.</p>
 */
public interface CgGpuBuffer extends CgDeviceObject {

    enum Usage {
        VERTEX, INDEX, UNIFORM, STORAGE, TEXEL, INDIRECT, COPY_SRC, COPY_DST;

        /** Every usage: a slab that sub-allocations of any kind share. */
        public static final Set<Usage> ALL = EnumSet.allOf(Usage.class);
    }

    /** @param hostReads the CPU reads it: host-visible memory the CPU caches */
    record Desc(String label, long size, Set<Usage> usage, boolean hostVisible, boolean hostReads) {

        public Desc {
            if (hostReads && !hostVisible) throw new IllegalArgumentException(label + ": read by the CPU, not host-visible");
        }

        public Desc(String label, long size, Set<Usage> usage, boolean hostVisible) {
            this(label, size, usage, hostVisible, false);
        }
    }

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
