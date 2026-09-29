package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgShaderModule;

final class VulkanShaderModule implements CgShaderModule {

    final Stage stage;
    final long module;
    private final String label;

    VulkanShaderModule(Stage stage, long module, String label) {
        this.stage = stage;
        this.module = module;
        this.label = label;
    }

    @Override public Stage stage() { return stage; }
    @Override public String label() { return label; }
}
