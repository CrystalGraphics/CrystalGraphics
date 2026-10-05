package com.crystalgraphics.platform.gl.tracked.memory;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * First-fit sub-allocation from large device buffers of one memory kind. A request above half a slab gets a
 * buffer of its own. Freeing returns the range at once; callers defer it to the frame that last used it.
 */
public final class CgSlabAllocator {

    private final CgDevice device;
    private final boolean hostVisible, hostReads, streamed;
    private final long slabSize;
    private final long alignment;
    private final List<Slab> slabs = new ArrayList<>();

    /** @param hostReads the CPU reads what it hands out: {@link CgGpuBuffer.Desc#hostReads} */
    public CgSlabAllocator(CgDevice device, boolean hostVisible, boolean hostReads, long slabSize, long alignment) {
        this(device, hostVisible, hostReads, false, slabSize, alignment);
    }

    /**
     * @param hostReads the CPU reads what it hands out: {@link CgGpuBuffer.Desc#hostReads}
     * @param streamed  written for the GPU to read about once: {@link CgGpuBuffer.Desc#streamed}
     */
    public CgSlabAllocator(CgDevice device, boolean hostVisible, boolean hostReads, boolean streamed, long slabSize,
                           long alignment) {
        this.device = device;
        this.hostVisible = hostVisible;
        this.hostReads = hostReads;
        this.streamed = streamed;
        this.slabSize = slabSize;
        this.alignment = alignment;
    }

    public CgAllocation allocate(long size, String label) {
        long need = align(Math.max(size, 1));
        if (need > slabSize / 2) {
            CgGpuBuffer b = device.createBuffer(new CgGpuBuffer.Desc(label, need, CgGpuBuffer.Usage.ALL, hostVisible,
                    hostReads, streamed));
            return new CgAllocation(this, b, 0, size, true);
        }
        for (Slab s : slabs) {
            long at = s.take(need);
            if (at >= 0) return new CgAllocation(this, s.buffer, at, size, false);
        }
        String kind = hostReads ? "readback" : streamed ? "stream" : hostVisible ? "host" : "device";
        Slab s = new Slab(device.createBuffer(new CgGpuBuffer.Desc(kind + " slab " + slabs.size(), slabSize,
                CgGpuBuffer.Usage.ALL, hostVisible, hostReads, streamed)));
        slabs.add(s);
        return new CgAllocation(this, s.buffer, s.take(need), size, false);
    }

    public void free(CgAllocation a) {
        if (a.dedicated) {
            device.release(a.buffer);
            return;
        }
        for (Slab s : slabs) {
            if (s.buffer == a.buffer) {
                s.give(a.offset, align(Math.max(a.size, 1)));
                return;
            }
        }
        throw new IllegalStateException("Not this allocator's range: " + a.buffer.label());
    }

    /** Bytes handed out and not yet freed, dedicated buffers excluded. */
    long used() {
        long used = 0;
        for (Slab s : slabs) used += slabSize - s.free();
        return used;
    }

    private long align(long n) {
        return (n + alignment - 1) / alignment * alignment;
    }

    private final class Slab {
        final CgGpuBuffer buffer;
        final TreeMap<Long, Long> holes = new TreeMap<>();

        Slab(CgGpuBuffer buffer) {
            this.buffer = buffer;
            holes.put(0L, slabSize);
        }

        long take(long n) {
            for (Map.Entry<Long, Long> h : holes.entrySet()) {
                long at = h.getKey(), hole = h.getValue();
                if (hole >= n) {
                    holes.remove(at);    // may reuse h's node for its successor: read it first
                    if (hole > n) holes.put(at + n, hole - n);
                    return at;
                }
            }
            return -1;
        }

        void give(long at, long n) {
            Map.Entry<Long, Long> before = holes.floorEntry(at);
            if (before != null && before.getKey() + before.getValue() == at) {
                at = before.getKey();
                n += before.getValue();
                holes.remove(at);
            }
            Long afterSize = holes.remove(at + n);
            if (afterSize != null) n += afterSize;
            holes.put(at, n);
        }

        public long free() {
            long f = 0;
            for (long n : holes.values()) f += n;
            return f;
        }
    }
}
