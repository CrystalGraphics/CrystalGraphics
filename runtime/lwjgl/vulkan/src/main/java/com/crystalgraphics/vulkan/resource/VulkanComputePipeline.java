package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

public final class VulkanComputePipeline implements CgComputePipeline {

    private final String label;
    private final CgShaderModule module;
    private final CgBindingLayout layout;
    public final long pipeline;

    public VulkanComputePipeline(String label, CgShaderModule module, CgBindingLayout layout, long pipeline) {
        this.label = label;
        this.module = module;
        this.layout = layout;
        this.pipeline = pipeline;
    }

    @Override public String label() { return label; }
    @Override public CgShaderModule module() { return module; }
    @Override public CgBindingLayout layout() { return layout; }
}
