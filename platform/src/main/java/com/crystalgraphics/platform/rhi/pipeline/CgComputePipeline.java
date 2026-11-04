package com.crystalgraphics.platform.rhi.pipeline;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

/** A compiled compute stage and the bindings it reads: what a {@link CgComputePass} dispatches. */
public interface CgComputePipeline extends CgRHIResource {

    CgShaderModule module();

    CgPipelineLayout layout();
}
