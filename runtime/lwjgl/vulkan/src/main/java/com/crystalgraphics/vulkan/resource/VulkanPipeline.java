package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;

public final class VulkanPipeline implements CgGraphicsPipeline {

    public final CgGraphicsPipelineDesc desc;
    public final long pipeline;
    /** The device's pipeline layout for {@code desc.layout()}, and the index of its pushed set (-1 with none). */
    public final long pipelineLayout;
    public final int pushSet;

    public VulkanPipeline(CgGraphicsPipelineDesc desc, long pipeline, long pipelineLayout) {
        this.desc = desc;
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
        this.pushSet = desc.layout().pushSet();
    }

    @Override public CgGraphicsPipelineDesc desc() { return desc; }
}
