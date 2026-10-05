package com.crystalgraphics.vulkan.command;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

/**
 * A command buffer for a queue family that only copies. {@link VulkanBarriers} keeps a barrier recorded into it to the
 * stages and accesses such a queue has.
 *
 * <pre>{@code
 * VkCommandBuffer cmd = new VulkanTransferCommandBuffer(handle, device);   // from a pool of the transfer family
 * }</pre>
 */
public final class VulkanTransferCommandBuffer extends VkCommandBuffer {

    public VulkanTransferCommandBuffer(long handle, VkDevice device) {
        super(handle, device);
    }
}
