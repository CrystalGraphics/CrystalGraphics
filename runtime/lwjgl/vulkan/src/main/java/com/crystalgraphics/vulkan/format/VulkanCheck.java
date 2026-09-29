package com.crystalgraphics.vulkan.format;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/** A {@code VkResult} other than success, as an exception naming the call. */
public final class VulkanCheck {

    private VulkanCheck() {}

    public static void check(int result, String call) {
        if (result != VK_SUCCESS) throw new IllegalStateException(call + " failed: VkResult " + result);
    }
}
