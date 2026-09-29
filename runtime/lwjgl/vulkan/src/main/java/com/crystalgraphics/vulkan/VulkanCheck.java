package com.crystalgraphics.vulkan;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** A {@code VkResult} other than success, as an exception naming the call. */
final class VulkanCheck {

    private VulkanCheck() {}

    static void check(int result, String call) {
        if (result != VK_SUCCESS) throw new IllegalStateException(call + " failed: VkResult " + result);
    }
}
