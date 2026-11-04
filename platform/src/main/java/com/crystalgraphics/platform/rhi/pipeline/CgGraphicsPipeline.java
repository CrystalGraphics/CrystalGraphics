package com.crystalgraphics.platform.rhi.pipeline;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;

/** A compiled {@link CgGraphicsPipelineDesc}. */
public interface CgGraphicsPipeline extends CgRHIResource {

    CgGraphicsPipelineDesc desc();

    @Override default String label() { return desc().label(); }
}
