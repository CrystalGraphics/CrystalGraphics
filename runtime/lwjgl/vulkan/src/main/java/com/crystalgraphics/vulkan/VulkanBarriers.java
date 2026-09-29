package com.crystalgraphics.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Pipeline barriers, spelled once. Coarse on purpose (plan/device-vulkan.md §5): correct first. */
final class VulkanBarriers {

    private VulkanBarriers() {}

    /** A layout transition over one subresource range, waiting on {@code srcStage} for {@code dstStage}. */
    static void image(VkCommandBuffer cmd, long image, int aspect, int baseMip, int mips, int baseLayer, int layers,
                      int oldLayout, int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            b.subresourceRange().aspectMask(aspect).baseMipLevel(baseMip).levelCount(mips)
                    .baseArrayLayer(baseLayer).layerCount(layers);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
        }
    }

    /** Every earlier write visible to every later access: what a transfer is fenced with on each side. */
    static void global(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }
}
