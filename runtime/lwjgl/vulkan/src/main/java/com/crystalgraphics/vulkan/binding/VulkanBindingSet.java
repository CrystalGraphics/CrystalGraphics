package com.crystalgraphics.vulkan.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;

/** A descriptor set written once from a {@code CgBindings}, and the pool it goes back to. */
public final class VulkanBindingSet implements CgBindingSet {

    public final long pool;
    public final long set;
    private final String label;
    private final CgBindingLayout layout;

    public VulkanBindingSet(String label, CgBindingLayout layout, long pool, long set) {
        this.label = label;
        this.layout = layout;
        this.pool = pool;
        this.set = set;
    }

    @Override public CgBindingLayout layout() { return layout; }

    @Override public String label() { return label; }
}
