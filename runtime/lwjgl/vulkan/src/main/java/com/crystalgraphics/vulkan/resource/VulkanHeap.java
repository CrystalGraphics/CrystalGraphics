package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.rhi.resource.CgGpuHeap;

/** A dedicated VMA allocation that placed images alias ({@code VulkanTexture#placed}). */
public final class VulkanHeap implements CgGpuHeap {

    private final String label;
    /** The {@code VmaAllocation}. */
    public final long allocation;
    private final long size;
    private final int memoryType;

    public VulkanHeap(String label, long allocation, long size, int memoryType) {
        this.label = label;
        this.allocation = allocation;
        this.size = size;
        this.memoryType = memoryType;
    }

    @Override public String label() { return label; }

    @Override public long size() { return size; }

    @Override public int memoryType() { return memoryType; }
}
