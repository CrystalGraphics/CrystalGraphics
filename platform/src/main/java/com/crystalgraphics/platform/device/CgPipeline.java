package com.crystalgraphics.platform.device;

/** A compiled {@link CgPipelineDesc}. */
public interface CgPipeline extends CgDeviceObject {

    CgPipelineDesc desc();

    @Override default String label() { return desc().label(); }
}
