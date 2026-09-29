package com.crystalgraphics.vulkan;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-visible memory an upload or a readback passes through: chunks bump-allocated for the frame, handed back when
 * it retires. A request larger than a chunk gets one of its own, destroyed rather than kept.
 */
final class VulkanStaging {

    /** A span of a staging chunk. */
    record Region(VulkanBuffer buffer, long offset, long size) {
        /** The span's bytes, positioned at 0. */
        ByteBuffer bytes() {
            return buffer.mapped().duplicate().limit((int) (offset + size)).position((int) offset).slice()
                    .order(buffer.mapped().order());
        }
    }

    private static final long CHUNK = 8L << 20;

    private final CgVulkanDevice device;
    private final ArrayDeque<VulkanBuffer> free = new ArrayDeque<>();
    private final List<VulkanBuffer> used = new ArrayList<>();
    private VulkanBuffer current;
    private long offset;

    VulkanStaging(CgVulkanDevice device) {
        this.device = device;
    }

    Region take(long size, long align) {
        long at = (offset + align - 1) / align * align;
        if (current == null || at + size > current.size()) {
            if (current != null) used.add(current);
            current = size > CHUNK ? device.stagingBuffer(size) : free.isEmpty() ? device.stagingBuffer(CHUNK) : free.pop();
            at = 0;
        }
        offset = at + size;
        return new Region(current, at, size);
    }

    /** This frame's chunks come back once {@code frame} has retired. */
    void endFrame(long frame) {
        if (current != null) used.add(current);
        current = null;
        offset = 0;
        List<VulkanBuffer> done = new ArrayList<>(used);
        used.clear();
        device.whenRetired(frame, () -> {
            for (VulkanBuffer b : done) {
                if (b.size() == CHUNK) free.push(b);
                else device.destroy(b);
            }
        });
    }

    void destroy() {
        for (VulkanBuffer b : free) device.destroy(b);
        free.clear();
    }
}
