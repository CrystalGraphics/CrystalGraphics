package com.crystalgraphics.vulkan.command;

import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Pipeline barriers, spelled once. Coarse on purpose (plan/device-vulkan.md §5): correct first. Filled in
 * {@link VulkanScratch}, since one is recorded per kernel access.
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
        VulkanScratch s = VulkanScratch.get(VkImageMemoryBarrier.SIZEOF);
        int range = VkImageMemoryBarrier.SUBRESOURCERANGE;
        s.bytes.putInt(VkImageMemoryBarrier.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                .putLong(VkImageMemoryBarrier.PNEXT, 0L)
                .putInt(VkImageMemoryBarrier.SRCACCESSMASK, access(cmd, srcAccess, src))
                .putInt(VkImageMemoryBarrier.DSTACCESSMASK, access(cmd, dstAccess, dst))
                .putInt(VkImageMemoryBarrier.OLDLAYOUT, oldLayout)
                .putInt(VkImageMemoryBarrier.NEWLAYOUT, newLayout)
                .putInt(VkImageMemoryBarrier.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                .putInt(VkImageMemoryBarrier.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                .putLong(VkImageMemoryBarrier.IMAGE, image)
                .putInt(range + VkImageSubresourceRange.ASPECTMASK, aspect)
                .putInt(range + VkImageSubresourceRange.BASEMIPLEVEL, baseMip)
                .putInt(range + VkImageSubresourceRange.LEVELCOUNT, mips)
                .putInt(range + VkImageSubresourceRange.BASEARRAYLAYER, baseLayer)
                .putInt(range + VkImageSubresourceRange.LAYERCOUNT, layers);
        nvkCmdPipelineBarrier(cmd, src, dst, 0, 0, 0L, 0, 0L, 1, s.address);
    }

    /** One buffer's writes at {@code srcStage} made visible to {@code dstStage}: a compute pass's precise barrier. */
    static void buffer(VkCommandBuffer cmd, long buffer, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        VulkanScratch s = VulkanScratch.get(VkBufferMemoryBarrier.SIZEOF);
        s.bytes.putInt(VkBufferMemoryBarrier.STYPE, VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                .putLong(VkBufferMemoryBarrier.PNEXT, 0L)
                .putInt(VkBufferMemoryBarrier.SRCACCESSMASK, access(cmd, srcAccess, src))
                .putInt(VkBufferMemoryBarrier.DSTACCESSMASK, access(cmd, dstAccess, dst))
                .putInt(VkBufferMemoryBarrier.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                .putInt(VkBufferMemoryBarrier.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                .putLong(VkBufferMemoryBarrier.BUFFER, buffer)
                .putLong(VkBufferMemoryBarrier.OFFSET, 0L)
                .putLong(VkBufferMemoryBarrier.SIZE, VK_WHOLE_SIZE);
        nvkCmdPipelineBarrier(cmd, src, dst, 0, 0, 0L, 1, s.address, 0, 0L);
    }

    /** Every earlier write visible to every later access: what a transfer is fenced with on each side. */
    static void global(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        VulkanScratch s = VulkanScratch.get(VkMemoryBarrier.SIZEOF);
        s.bytes.putInt(VkMemoryBarrier.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .putLong(VkMemoryBarrier.PNEXT, 0L)
                .putInt(VkMemoryBarrier.SRCACCESSMASK, access(cmd, srcAccess, src))
                .putInt(VkMemoryBarrier.DSTACCESSMASK, access(cmd, dstAccess, dst));
        nvkCmdPipelineBarrier(cmd, src, dst, 0, 1, s.address, 0, 0L, 0, 0L);
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
