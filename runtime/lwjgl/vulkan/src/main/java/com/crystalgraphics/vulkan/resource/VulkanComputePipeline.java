package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

public final class VulkanComputePipeline implements CgComputePipeline {

    private final String label;
    private final CgShaderModule module;
    private final CgPipelineLayout layout;
    public final long pipeline;
    /** The device's pipeline layout for {@link #layout()}, and the index of its pushed set (-1 with none). */
    public final long pipelineLayout;
    public final int pushSet;

    public VulkanComputePipeline(String label, CgShaderModule module, CgPipelineLayout layout, long pipeline,
                                 long pipelineLayout) {
        this.label = label;
        this.module = module;
        this.layout = layout;
        this.pipeline = pipeline;
        this.pipelineLayout = pipelineLayout;
        this.pushSet = layout.pushSet();
    }

    @Override public String label() { return label; }
    @Override public CgShaderModule module() { return module; }
    @Override public CgPipelineLayout layout() { return layout; }
}
