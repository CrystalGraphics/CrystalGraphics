package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgBindingLayout;

import java.util.List;

/** One push-descriptor set layout and the pipeline layout over it: every binding of a program is in set 0. */
final class VulkanBindingLayout implements CgBindingLayout {

    final long setLayout;
    final long pipelineLayout;
    private final List<Slot> slots;
    private final String label;

    VulkanBindingLayout(String label, List<Slot> slots, long setLayout, long pipelineLayout) {
        this.label = label;
        this.slots = slots;
        this.setLayout = setLayout;
        this.pipelineLayout = pipelineLayout;
    }

    @Override public List<Slot> slots() { return slots; }
    @Override public String label() { return label; }
}
