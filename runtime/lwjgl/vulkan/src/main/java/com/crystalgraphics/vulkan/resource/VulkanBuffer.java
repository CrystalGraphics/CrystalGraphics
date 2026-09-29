package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.resource.CgGpuBuffer;

import java.nio.ByteBuffer;

/** A {@code VkBuffer} with its VMA allocation; host-visible ones persistently mapped and coherent. */
public final class VulkanBuffer implements CgGpuBuffer {

    final Desc desc;
    public final long buffer;
    public final long allocation;
    private final ByteBuffer mapped;

    public VulkanBuffer(Desc desc, long buffer, long allocation, ByteBuffer mapped) {
        this.desc = desc;
        this.buffer = buffer;
        this.allocation = allocation;
        this.mapped = mapped;
    }

    @Override public String label() { return desc.label(); }
    @Override public long size() { return desc.size(); }
    @Override public boolean hostVisible() { return desc.hostVisible(); }

    @Override
    public ByteBuffer mapped() {
        if (mapped == null) throw new IllegalStateException(desc.label() + " is device-local: it has no mapping");
        return mapped;
    }
}
