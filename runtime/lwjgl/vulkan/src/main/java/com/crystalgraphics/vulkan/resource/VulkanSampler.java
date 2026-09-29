package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.resource.CgGpuSampler;

/** A {@code VkSampler}, one per distinct description: the device caches them. */
public final class VulkanSampler implements CgGpuSampler {

    final Desc desc;
    public final long sampler;

    public VulkanSampler(Desc desc, long sampler) {
        this.desc = desc;
        this.sampler = sampler;
    }

    @Override public Desc desc() { return desc; }
    @Override public String label() { return "sampler"; }
}
