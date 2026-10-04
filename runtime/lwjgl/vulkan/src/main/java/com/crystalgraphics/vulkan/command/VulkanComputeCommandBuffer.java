package com.crystalgraphics.vulkan.command;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * A command buffer for a queue family that computes and copies but does not draw. {@link VulkanBarriers} keeps a
 * barrier recorded into it to the stages and accesses such a queue has.
 *
 * <pre>{@code
 * VkCommandBuffer cmd = computeOnly ? new VulkanComputeCommandBuffer(handle, device) : new VkCommandBuffer(handle, device);
 * }</pre>
 */
public final class VulkanComputeCommandBuffer extends VkCommandBuffer {

    public VulkanComputeCommandBuffer(long handle, VkDevice device) {
        super(handle, device);
    }
}
