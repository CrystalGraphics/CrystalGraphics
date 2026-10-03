package com.crystalgraphics.platform.device.pipeline;

import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.command.CgComputePass;
import com.crystalgraphics.platform.device.shader.CgShaderModule;

/** A compiled compute stage and the bindings it reads: what a {@link CgComputePass} dispatches. */
public interface CgComputePipeline extends CgDeviceObject {

    CgShaderModule module();

    CgBindingLayout layout();
}
