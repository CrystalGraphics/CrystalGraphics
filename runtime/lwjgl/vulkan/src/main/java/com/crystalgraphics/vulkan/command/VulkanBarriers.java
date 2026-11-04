package com.crystalgraphics.vulkan.command;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.KHRSynchronization2.nvkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.nvkCmdSetEvent2KHR;
import static org.lwjgl.vulkan.KHRSynchronization2.nvkCmdWaitEvents2KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_DEPENDENCY_INFO;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2;

/**
 * Pipeline barriers, spelled once. Coarse on purpose (plan/device-vulkan.md §5): correct first. Filled in
 * {@link VulkanScratch}, since one is recorded per kernel access. Through synchronization2 where the device took it
 * (the owned device, where the GPU has it), as Vulkan 1.0's barrier where not (a host's device that did not).
 *
 * <p>Into a {@link VulkanComputeCommandBuffer} a barrier keeps to the stages and accesses a compute queue has, and into
 * a {@link VulkanTransferCommandBuffer} to a copy queue's. What it drops is work only the frame's queue does: the work
 * before is covered by the semaphore the other queue waits on, and the work after by the frame's queue waiting for
 * it.</p>
 */
public final class VulkanBarriers {

    /** What a queue that computes and copies, and does not draw, may wait on. */
    private static final int COMPUTE_QUEUE_STAGES = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT | VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
            | VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    /** What a queue that only copies may wait on. */
    private static final int TRANSFER_QUEUE_STAGES = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
            | VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT | VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;

    private VulkanBarriers() {}

    /** A layout transition over one subresource range, waiting on {@code srcStage} for {@code dstStage}. */
    public static void image(VkCommandBuffer cmd, long image, int aspect, int baseMip, int mips, int baseLayer, int layers,
                      int oldLayout, int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        if (sync2(cmd)) {
            int dep = (VkImageMemoryBarrier2.SIZEOF + 7) & ~7;
            VulkanScratch s = VulkanScratch.get(dep + VkDependencyInfo.SIZEOF);
            ByteBuffer m = s.zero(dep + VkDependencyInfo.SIZEOF);
            int range = VkImageMemoryBarrier2.SUBRESOURCERANGE;
            m.putInt(VkImageMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                    .putLong(VkImageMemoryBarrier2.SRCSTAGEMASK, src & 0xFFFFFFFFL)
                    .putLong(VkImageMemoryBarrier2.SRCACCESSMASK, access(cmd, srcAccess, src) & 0xFFFFFFFFL)
                    .putLong(VkImageMemoryBarrier2.DSTSTAGEMASK, dst & 0xFFFFFFFFL)
                    .putLong(VkImageMemoryBarrier2.DSTACCESSMASK, access(cmd, dstAccess, dst) & 0xFFFFFFFFL)
                    .putInt(VkImageMemoryBarrier2.OLDLAYOUT, oldLayout)
                    .putInt(VkImageMemoryBarrier2.NEWLAYOUT, newLayout)
                    .putInt(VkImageMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                    .putInt(VkImageMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                    .putLong(VkImageMemoryBarrier2.IMAGE, image)
                    .putInt(range + VkImageSubresourceRange.ASPECTMASK, aspect)
                    .putInt(range + VkImageSubresourceRange.BASEMIPLEVEL, baseMip)
                    .putInt(range + VkImageSubresourceRange.LEVELCOUNT, mips)
                    .putInt(range + VkImageSubresourceRange.BASEARRAYLAYER, baseLayer)
                    .putInt(range + VkImageSubresourceRange.LAYERCOUNT, layers);
            m.putInt(dep + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                    .putInt(dep + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, 1)
                    .putLong(dep + VkDependencyInfo.PIMAGEMEMORYBARRIERS, s.address);
            nvkCmdPipelineBarrier2KHR(cmd, s.address + dep);
            return;
        }
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

    /**
     * Every earlier write visible to every later access: what a transfer is fenced with on each side, and what buffer
     * barriers are recorded as.
     */
    static void global(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
        int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        if (sync2(cmd)) {
            int dep = (VkMemoryBarrier2.SIZEOF + 7) & ~7;
            VulkanScratch s = VulkanScratch.get(dep + VkDependencyInfo.SIZEOF);
            ByteBuffer m = s.zero(dep + VkDependencyInfo.SIZEOF);
            m.putInt(VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                    .putLong(VkMemoryBarrier2.SRCSTAGEMASK, src & 0xFFFFFFFFL)
                    .putLong(VkMemoryBarrier2.SRCACCESSMASK, access(cmd, srcAccess, src) & 0xFFFFFFFFL)
                    .putLong(VkMemoryBarrier2.DSTSTAGEMASK, dst & 0xFFFFFFFFL)
                    .putLong(VkMemoryBarrier2.DSTACCESSMASK, access(cmd, dstAccess, dst) & 0xFFFFFFFFL);
            m.putInt(dep + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                    .putInt(dep + VkDependencyInfo.MEMORYBARRIERCOUNT, 1)
                    .putLong(dep + VkDependencyInfo.PMEMORYBARRIERS, s.address);
            nvkCmdPipelineBarrier2KHR(cmd, s.address + dep);
            return;
        }
        VulkanScratch s = VulkanScratch.get(VkMemoryBarrier.SIZEOF);
        s.bytes.putInt(VkMemoryBarrier.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .putLong(VkMemoryBarrier.PNEXT, 0L)
                .putInt(VkMemoryBarrier.SRCACCESSMASK, access(cmd, srcAccess, src))
                .putInt(VkMemoryBarrier.DSTACCESSMASK, access(cmd, dstAccess, dst));
        nvkCmdPipelineBarrier(cmd, src, dst, 0, 1, s.address, 0, 0L, 0, 0L);
    }

    /**
     * Barriers recorded as one dependency, as Unreal's Vulkan RHI records a transition: images each with its range and
     * layouts, buffers joined into one memory barrier. Recorded at once, or split across an event: {@link #set} after
     * the work before, {@link #waitFor} with the same content before the work after. Reused: {@link #clear}, then fill.
     *
     * <pre>{@code
     * batch.clear().image(cmd, image, aspect, 0, 1, 0, 1, oldLayout, newLayout, src, srcAccess, dst, dstAccess);
     * batch.record(cmd);                 // here, at once
     * }</pre>
     */
    public static final class Batch {

        private static final int FIELDS = 11;
        private long[] images = new long[8];
        /** Per image: aspect, base mip, mips, base layer, layers, old and new layout, src stage and access, dst stage and access. */
        private int[] fields = new int[8 * FIELDS];
        private int count;
        private int memSrcStage, memSrcAccess, memDstStage, memDstAccess;

        public Batch clear() {
            count = 0;
            memSrcStage = memSrcAccess = memDstStage = memDstAccess = 0;
            return this;
        }

        public boolean isEmpty() {
            return count == 0 && !memory();
        }

        private boolean memory() {
            return memSrcStage != 0 || memDstStage != 0;
        }

        public void image(VkCommandBuffer cmd, long image, int aspect, int baseMip, int mips, int baseLayer, int layers,
                          int oldLayout, int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
            if (count == images.length) {
                images = Arrays.copyOf(images, count * 2);
                fields = Arrays.copyOf(fields, count * 2 * FIELDS);
            }
            int src = stage(cmd, srcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            int dst = stage(cmd, dstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            images[count] = image;
            int f = count++ * FIELDS;
            fields[f] = aspect;
            fields[f + 1] = baseMip;
            fields[f + 2] = mips;
            fields[f + 3] = baseLayer;
            fields[f + 4] = layers;
            fields[f + 5] = oldLayout;
            fields[f + 6] = newLayout;
            fields[f + 7] = src;
            fields[f + 8] = access(cmd, srcAccess, src);
            fields[f + 9] = dst;
            fields[f + 10] = access(cmd, dstAccess, dst);
        }

        /** A buffer's barrier, joined into the batch's one memory barrier: drivers treat a buffer barrier as global. */
        public void memory(int srcStage, int srcAccess, int dstStage, int dstAccess) {
            memSrcStage |= srcStage;
            memSrcAccess |= srcAccess;
            memDstStage |= dstStage;
            memDstAccess |= dstAccess;
        }

        /** Records the batch here: one call under synchronization2, one per barrier under 1.0. Answers barriers recorded. */
        public int record(VkCommandBuffer cmd) {
            if (isEmpty()) return 0;
            if (sync2(cmd)) {
                nvkCmdPipelineBarrier2KHR(cmd, dependency(cmd));
                return 1;
            }
            for (int i = 0; i < count; i++) {
                int f = i * FIELDS;
                image(cmd, images[i], fields[f], fields[f + 1], fields[f + 2], fields[f + 3], fields[f + 4], fields[f + 5],
                        fields[f + 6], fields[f + 7], fields[f + 8], fields[f + 9], fields[f + 10]);
            }
            if (memory()) global(cmd, memSrcStage, memSrcAccess, memDstStage, memDstAccess);
            return count + (memory() ? 1 : 0);
        }

        /** Whether {@code cmd} can split a batch across an event: synchronization2's set and wait. */
        public static boolean splits(VkCommandBuffer cmd) {
            return sync2(cmd);
        }

        /** The first half of a split: {@code event} is set once the work before it is done. */
        public void set(VkCommandBuffer cmd, long event) {
            nvkCmdSetEvent2KHR(cmd, event, dependency(cmd));
        }

        /** The second half, filled as for {@link #set}: waits for {@code event}, and the layouts move. */
        public void waitFor(VkCommandBuffer cmd, long event) {
            long dependency = dependency(cmd);
            MemoryUtil.memPutLong(eventAt, event);
            nvkCmdWaitEvents2KHR(cmd, 1, eventAt, dependency);
        }

        /** Where {@link #dependency} left room for one event handle. */
        private long eventAt;

        /** The barriers and their {@code VkDependencyInfo} in {@link VulkanScratch}; answers the info's address. */
        private long dependency(VkCommandBuffer cmd) {
            int imageSize = (VkImageMemoryBarrier2.SIZEOF + 7) & ~7;
            int memAt = count * imageSize, depAt = memAt + (memory() ? (VkMemoryBarrier2.SIZEOF + 7) & ~7 : 0);
            int eventOffset = depAt + ((VkDependencyInfo.SIZEOF + 7) & ~7);
            VulkanScratch s = VulkanScratch.get(eventOffset + 8);
            ByteBuffer m = s.zero(eventOffset + 8);
            for (int i = 0; i < count; i++) {
                int at = i * imageSize, f = i * FIELDS, range = at + VkImageMemoryBarrier2.SUBRESOURCERANGE;
                m.putInt(at + VkImageMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2)
                        .putLong(at + VkImageMemoryBarrier2.SRCSTAGEMASK, fields[f + 7] & 0xFFFFFFFFL)
                        .putLong(at + VkImageMemoryBarrier2.SRCACCESSMASK, fields[f + 8] & 0xFFFFFFFFL)
                        .putLong(at + VkImageMemoryBarrier2.DSTSTAGEMASK, fields[f + 9] & 0xFFFFFFFFL)
                        .putLong(at + VkImageMemoryBarrier2.DSTACCESSMASK, fields[f + 10] & 0xFFFFFFFFL)
                        .putInt(at + VkImageMemoryBarrier2.OLDLAYOUT, fields[f + 5])
                        .putInt(at + VkImageMemoryBarrier2.NEWLAYOUT, fields[f + 6])
                        .putInt(at + VkImageMemoryBarrier2.SRCQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                        .putInt(at + VkImageMemoryBarrier2.DSTQUEUEFAMILYINDEX, VK_QUEUE_FAMILY_IGNORED)
                        .putLong(at + VkImageMemoryBarrier2.IMAGE, images[i])
                        .putInt(range + VkImageSubresourceRange.ASPECTMASK, fields[f])
                        .putInt(range + VkImageSubresourceRange.BASEMIPLEVEL, fields[f + 1])
                        .putInt(range + VkImageSubresourceRange.LEVELCOUNT, fields[f + 2])
                        .putInt(range + VkImageSubresourceRange.BASEARRAYLAYER, fields[f + 3])
                        .putInt(range + VkImageSubresourceRange.LAYERCOUNT, fields[f + 4]);
            }
            if (memory()) {
                int src = stage(cmd, memSrcStage, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
                int dst = stage(cmd, memDstStage, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
                m.putInt(memAt + VkMemoryBarrier2.STYPE, VK_STRUCTURE_TYPE_MEMORY_BARRIER_2)
                        .putLong(memAt + VkMemoryBarrier2.SRCSTAGEMASK, src & 0xFFFFFFFFL)
                        .putLong(memAt + VkMemoryBarrier2.SRCACCESSMASK, access(cmd, memSrcAccess, src) & 0xFFFFFFFFL)
                        .putLong(memAt + VkMemoryBarrier2.DSTSTAGEMASK, dst & 0xFFFFFFFFL)
                        .putLong(memAt + VkMemoryBarrier2.DSTACCESSMASK, access(cmd, memDstAccess, dst) & 0xFFFFFFFFL);
                m.putInt(depAt + VkDependencyInfo.MEMORYBARRIERCOUNT, 1)
                        .putLong(depAt + VkDependencyInfo.PMEMORYBARRIERS, s.address + memAt);
            }
            m.putInt(depAt + VkDependencyInfo.STYPE, VK_STRUCTURE_TYPE_DEPENDENCY_INFO)
                    .putInt(depAt + VkDependencyInfo.IMAGEMEMORYBARRIERCOUNT, count)
                    .putLong(depAt + VkDependencyInfo.PIMAGEMEMORYBARRIERS, count == 0 ? 0L : s.address);
            eventAt = s.address + eventOffset;
            return s.address + depAt;
        }
    }

    /** Whether {@code cmd}'s device was made with synchronization2. */
    private static boolean sync2(VkCommandBuffer cmd) {
        return cmd.getCapabilities().VK_KHR_synchronization2;
    }

    /** {@code stages} as {@code cmd}'s queue has them; {@code none} where it has none of them. */
    private static int stage(VkCommandBuffer cmd, int stages, int none) {
        int queue = cmd instanceof VulkanComputeCommandBuffer ? COMPUTE_QUEUE_STAGES
                : cmd instanceof VulkanTransferCommandBuffer ? TRANSFER_QUEUE_STAGES : 0;
        if (queue == 0) return stages;
        int kept = stages & queue;
        return kept != 0 ? kept : none;
    }

    /** The accesses {@code stages}, already kept to {@code cmd}'s queue, can make: every access needs its stage. */
    private static int access(VkCommandBuffer cmd, int accesses, int stages) {
        boolean compute = cmd instanceof VulkanComputeCommandBuffer;
        if (!compute && !(cmd instanceof VulkanTransferCommandBuffer)) return accesses;
        boolean all = (stages & VK_PIPELINE_STAGE_ALL_COMMANDS_BIT) != 0;
        int kept = accesses & (VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        if (compute && (all || (stages & VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT) != 0))
            kept |= accesses & (VK_ACCESS_UNIFORM_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        if (compute && (all || (stages & VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT) != 0))
            kept |= accesses & VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
        if (all || (stages & VK_PIPELINE_STAGE_TRANSFER_BIT) != 0)
            kept |= accesses & (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
        if (all || (stages & VK_PIPELINE_STAGE_HOST_BIT) != 0) kept |= accesses & (VK_ACCESS_HOST_READ_BIT | VK_ACCESS_HOST_WRITE_BIT);
        return kept;
    }
}
