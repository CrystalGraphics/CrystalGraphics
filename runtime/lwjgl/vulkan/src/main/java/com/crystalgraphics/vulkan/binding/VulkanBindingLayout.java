package com.crystalgraphics.vulkan.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;

import java.util.List;

/** One descriptor set layout; a pushed one carries the push-descriptor flag. Pipeline layouts are the device's, by value. */
public final class VulkanBindingLayout implements CgBindingLayout {

    public final long setLayout;
    private final List<Slot> slots;
    private final String label;
    private final boolean pushed;

    public VulkanBindingLayout(String label, List<Slot> slots, long setLayout, boolean pushed) {
        this.label = label;
        this.slots = slots;
        this.setLayout = setLayout;
        this.pushed = pushed;
    }

    @Override public List<Slot> slots() { return slots; }

    @Override public boolean pushed() { return pushed; }

    @Override public String label() { return label; }
}
