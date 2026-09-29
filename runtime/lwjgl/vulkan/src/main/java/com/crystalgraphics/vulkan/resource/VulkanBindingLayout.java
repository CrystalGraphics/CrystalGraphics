package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;

import java.util.List;

/** One push-descriptor set layout and the pipeline layout over it: every binding of a program is in set 0. */
public final class VulkanBindingLayout implements CgBindingLayout {

    public final long setLayout;
    public final long pipelineLayout;
    private final List<Slot> slots;
    private final String label;

    public VulkanBindingLayout(String label, List<Slot> slots, long setLayout, long pipelineLayout) {
        this.label = label;
        this.slots = slots;
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
    }

    @Override public List<Slot> slots() { return slots; }
    @Override public String label() { return label; }
}
