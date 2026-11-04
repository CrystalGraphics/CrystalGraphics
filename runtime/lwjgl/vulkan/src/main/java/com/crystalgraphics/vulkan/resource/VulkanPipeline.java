package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;

public final class VulkanPipeline implements CgGraphicsPipeline {

    public final CgGraphicsPipelineDesc desc;
    public final long pipeline;

    public VulkanPipeline(CgGraphicsPipelineDesc desc, long pipeline) {
        this.desc = desc;
        this.pipeline = pipeline;
    }

    @Override public CgGraphicsPipelineDesc desc() { return desc; }
}
