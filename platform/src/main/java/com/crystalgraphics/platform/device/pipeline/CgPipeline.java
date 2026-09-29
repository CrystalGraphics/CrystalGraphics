package com.crystalgraphics.platform.device.pipeline;

import com.crystalgraphics.platform.device.CgDeviceObject;

/** A compiled {@link CgPipelineDesc}. */
public interface CgPipeline extends CgDeviceObject {

    CgPipelineDesc desc();

    @Override default String label() { return desc().label(); }
}
