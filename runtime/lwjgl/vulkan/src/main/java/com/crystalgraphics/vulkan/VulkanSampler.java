package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgGpuSampler;

/** A {@code VkSampler}, one per distinct description: the device caches them. */
final class VulkanSampler implements CgGpuSampler {

    final Desc desc;
    final long sampler;

    VulkanSampler(Desc desc, long sampler) {
        this.desc = desc;
        this.sampler = sampler;
    }

    @Override public Desc desc() { return desc; }
    @Override public String label() { return "sampler"; }
}
