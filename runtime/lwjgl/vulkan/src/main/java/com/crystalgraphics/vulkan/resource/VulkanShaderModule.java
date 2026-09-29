package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.shader.CgShaderModule;

public final class VulkanShaderModule implements CgShaderModule {

    final Stage stage;
    public final long module;
    private final String label;

    public VulkanShaderModule(Stage stage, long module, String label) {
        this.stage = stage;
        this.module = module;
        this.label = label;
    }

    @Override public Stage stage() { return stage; }
    @Override public String label() { return label; }
}
