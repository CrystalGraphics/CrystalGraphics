package com.crystalgraphics.vulkan.command;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Pipeline barriers, spelled once. Coarse on purpose (plan/device-vulkan.md §5): correct first.
 *
 * <p>Into a {@link VulkanComputeCommandBuffer} a barrier keeps to the stages and accesses a compute queue has. What it
 * drops is drawing, which only the frame's queue does: the work before is covered by the semaphore the async work
 * waits on, and the work after by the frame's queue waiting for it.</p>
 */
public final class VulkanBarriers {

    /** What a queue that computes and copies, and does not draw, may wait on. */
    private static final int COMPUTE_QUEUE_STAGES = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
            | VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

    private VulkanBarriers() {}

    /** A layout transition over one subresource range, waiting on {@code srcStage} for {@code dstStage}. */
    public static void image(VkCommandBuffer cmd, long image, int aspect, int baseMip, int mips, int baseLayer, int layers,
                      int oldLayout, int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(access(cmd, srcAccess, src)).dstAccessMask(access(cmd, dstAccess, dst))
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            b.subresourceRange().aspectMask(aspect).baseMipLevel(baseMip).levelCount(mips)
                    .baseArrayLayer(baseLayer).layerCount(layers);
            vkCmdPipelineBarrier(cmd, src, dst, 0, null, null, b);
        }
    }

    /** One buffer's writes at {@code srcStage} made visible to {@code dstStage}: a compute pass's precise barrier. */
    static void buffer(VkCommandBuffer cmd, long buffer, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        try (MemoryStack stack = stackPush()) {
            VkBufferMemoryBarrier.Buffer b = VkBufferMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(access(cmd, srcAccess, src)).dstAccessMask(access(cmd, dstAccess, dst))
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buffer).offset(0).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(cmd, src, dst, 0, null, b, null);
        }
    }

    /** Every earlier write visible to every later access: what a transfer is fenced with on each side. */
    static void global(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer b = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(access(cmd, srcAccess, src)).dstAccessMask(access(cmd, dstAccess, dst));
            vkCmdPipelineBarrier(cmd, src, dst, 0, b, null, null);
        }
    }

    /** {@code stages} as {@code cmd}'s queue has them; {@code none} where it has none of them. */
    private static int stage(VkCommandBuffer cmd, int stages, int none) {
        if (!(cmd instanceof VulkanComputeCommandBuffer)) return stages;
        int kept = stages & COMPUTE_QUEUE_STAGES;
        return kept != 0 ? kept : none;
    }

    /** The accesses {@code stages}, already kept to {@code cmd}'s queue, can make: every access needs its stage. */
    private static int access(VkCommandBuffer cmd, int accesses, int stages) {
        if (!(cmd instanceof VulkanComputeCommandBuffer)) return accesses;
        boolean all = (stages & VK_PIPELINE_STAGE_ALL_COMMANDS_BIT) != 0;
        int kept = accesses & (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        if (all || (stages & VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT) != 0)
            kept |= accesses & (VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        if (all || (stages & VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT) != 0) kept |= accesses & VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        if (all || (stages & VK_PIPELINE_STAGE_TRANSFER_BIT) != 0)
            kept |= accesses & (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
        if (all || (stages & VK_PIPELINE_STAGE_HOST_BIT) != 0) kept |= accesses & (VK_ACCESS_HOST_READ_BIT | VK_ACCESS_HOST_WRITE_BIT);
        return kept;
    }
}
