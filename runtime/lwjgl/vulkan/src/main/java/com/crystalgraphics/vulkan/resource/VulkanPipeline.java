package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;

public final class VulkanPipeline implements CgPipeline {

    public final CgPipelineDesc desc;
    public final long pipeline;

    public VulkanPipeline(CgPipelineDesc desc, long pipeline) {
        this.desc = desc;
        this.pipeline = pipeline;
    }

    @Override public CgPipelineDesc desc() { return desc; }
}
