package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgPipeline;
import com.crystalgraphics.platform.device.CgPipelineDesc;

final class VulkanPipeline implements CgPipeline {

    final CgPipelineDesc desc;
    final long pipeline;

    VulkanPipeline(CgPipelineDesc desc, long pipeline) {
        this.desc = desc;
        this.pipeline = pipeline;
    }

    @Override public CgPipelineDesc desc() { return desc; }
}
