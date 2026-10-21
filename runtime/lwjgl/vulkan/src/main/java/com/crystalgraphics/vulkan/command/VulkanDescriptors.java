package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanSampler;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.lwjgl.vulkan.KHRPushDescriptor.nvkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Bindings pushed into set 0 at a render pass's or a compute pass's bind point. Filled in {@link VulkanScratch}: a
 * write per binding, then an info per binding, since this runs per draw and per dispatch.
 *
 * <p>Only what changed is pushed: a binding that resolves as its bind point last pushed it, in the same command buffer
 * and pipeline layout, is left out, and a push with nothing changed makes no call. Unreal's Vulkan RHI writes a
 * descriptor only when what it holds changed ({@code FVulkanDescriptorSetWriter}'s dirty tracking,
 * {@code FVulkanGraphicsPipelineDescriptorState::InternalUpdateDescriptorSets}); push descriptors are updated
 * incrementally and persist until overwritten or the set is disturbed ({@code vkCmdPushDescriptorSetKHR}).</p>
 */
final class VulkanDescriptors {

    private static final int WRITE = VkWriteDescriptorSet.SIZEOF;
    private static final int INFO = Math.max(VkDescriptorBufferInfo.SIZEOF, VkDescriptorImageInfo.SIZEOF);

    /**
     * What one bind point last pushed: the command buffer and layout, and each binding as it resolved (its slot, type
     * and the three words its info holds). Forgotten at each pass's start.
     */
    static final class Pushed {
        private long cmd, layout;
        private int count = -1;
        private int[] binding = new int[16], type = new int[16];
        private long[] words = new long[48];
        /** The push being resolved; swapped with the last once it is sent. */
        private int[] nextBinding = new int[16], nextType = new int[16];
        private long[] nextWords = new long[48];

        void forget() {
            count = -1;
        }

        /** Room for {@code n} bindings: grows once to what a program binds, then never. */
        private void fit(int n) {
            if (nextBinding.length >= n) return;
            int size = Integer.highestOneBit(n) << 1;
            binding = Arrays.copyOf(binding, size);
            type = Arrays.copyOf(type, size);
            words = Arrays.copyOf(words, size * 3);
            nextBinding = Arrays.copyOf(nextBinding, size);
            nextType = Arrays.copyOf(nextType, size);
            nextWords = Arrays.copyOf(nextWords, size * 3);
        }

        /** Whether the push being resolved is the last one's bindings in order, on the same buffer and layout. */
        private boolean continues(long cmd, long layout, int n) {
            if (count != n || this.cmd != cmd || this.layout != layout) return false;
            for (int i = 0; i < n; i++) {
                if (binding[i] != nextBinding[i] || type[i] != nextType[i]) return false;
            }
            return true;
        }

        /** Whether binding {@code i} resolves as it was last pushed. */
        private boolean unchanged(int i) {
            int at = i * 3;
            return words[at] == nextWords[at] && words[at + 1] == nextWords[at + 1] && words[at + 2] == nextWords[at + 2];
        }

        private void keep(long cmd, long layout, int n) {
            int[] b = binding, t = type;
            long[] w = words;
            binding = nextBinding;
            type = nextType;
            words = nextWords;
            nextBinding = b;
            nextType = t;
            nextWords = w;
            this.cmd = cmd;
            this.layout = layout;
            count = n;
        }
    }

    private VulkanDescriptors() {}

    static void push(CgVulkanDevice device, VkCommandBuffer cmd, int bindPoint, long pipelineLayout, CgBindings b,
                     Pushed pushed) {
        int n = b.count();
        if (n == 0) return;
        // Before the scratch is taken: laying an image records a barrier, which fills the scratch too.
        for (int i = 0; i < n; i++) {
            CgBindingLayout.Type type = b.type(i);
            if (type != CgBindingLayout.Type.SAMPLED_TEXTURE && type != CgBindingLayout.Type.STORAGE_IMAGE) continue;
            VulkanTexture t = (VulkanTexture) b.view(i).texture();
            if (t.unlaid || t.transferOnly) device.lay(t);
        }
        pushed.fit(n);
        int[] slots = pushed.nextBinding, types = pushed.nextType;
        long[] w = pushed.nextWords;
        for (int i = 0; i < n; i++) {
            CgBindingLayout.Type type = b.type(i);
            int at = i * 3;
            slots[i] = b.binding(i);
            types[i] = CgVulkanDevice.descriptorType(type);
            if (type == CgBindingLayout.Type.SAMPLED_TEXTURE || type == CgBindingLayout.Type.STORAGE_IMAGE) {
                boolean sampled = type == CgBindingLayout.Type.SAMPLED_TEXTURE;
                VulkanTexture t = (VulkanTexture) b.view(i).texture();
                w[at] = sampled ? ((VulkanSampler) b.sampler(i)).sampler : 0L;
                w[at + 1] = t.view(device.vk(), b.view(i), false);
                // A host's image is sampled where the host keeps it.
                w[at + 2] = !sampled ? VK_IMAGE_LAYOUT_GENERAL : t.borrowed() ? t.resting : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            } else if (type == CgBindingLayout.Type.TEXEL_BUFFER) {
                w[at] = device.texelView((VulkanBuffer) b.buffer(i), b.offset(i), b.size(i), b.texelFormat(i));
                w[at + 1] = 0L;
                w[at + 2] = 0L;
            } else {
                w[at] = ((VulkanBuffer) b.buffer(i)).buffer;
                w[at + 1] = b.offset(i);
                w[at + 2] = b.size(i) > 0 ? b.size(i) : VK_WHOLE_SIZE;
            }
        }
        long cmdAddress = cmd.address();
        boolean incremental = pushed.continues(cmdAddress, pipelineLayout, n);
        int changed = n;
        if (incremental) {
            changed = 0;
            for (int i = 0; i < n; i++) if (!pushed.unchanged(i)) changed++;
            if (changed == 0) {
                device.pushesSkipped++;
                return;
            }
        }
        VulkanScratch s = VulkanScratch.get(changed * (WRITE + INFO));
        ByteBuffer m = s.bytes;
        for (int i = 0, k = 0; i < n; i++) {
            if (incremental && pushed.unchanged(i)) continue;
            int write = k * WRITE, info = changed * WRITE + k * INFO, at = i * 3;
            k++;
            long infoAddress = s.address + info;
            CgBindingLayout.Type type = b.type(i);
            m.putInt(write + VkWriteDescriptorSet.STYPE, VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .putLong(write + VkWriteDescriptorSet.PNEXT, 0L)
                    .putLong(write + VkWriteDescriptorSet.DSTSET, 0L)
                    .putInt(write + VkWriteDescriptorSet.DSTBINDING, slots[i])
                    .putInt(write + VkWriteDescriptorSet.DSTARRAYELEMENT, 0)
                    .putInt(write + VkWriteDescriptorSet.DESCRIPTORCOUNT, 1)
                    .putInt(write + VkWriteDescriptorSet.DESCRIPTORTYPE, types[i])
                    .putLong(write + VkWriteDescriptorSet.PIMAGEINFO, 0L)
                    .putLong(write + VkWriteDescriptorSet.PBUFFERINFO, 0L)
                    .putLong(write + VkWriteDescriptorSet.PTEXELBUFFERVIEW, 0L);
            if (type == CgBindingLayout.Type.SAMPLED_TEXTURE || type == CgBindingLayout.Type.STORAGE_IMAGE) {
                m.putLong(info + VkDescriptorImageInfo.SAMPLER, w[at])
                        .putLong(info + VkDescriptorImageInfo.IMAGEVIEW, w[at + 1])
                        .putInt(info + VkDescriptorImageInfo.IMAGELAYOUT, (int) w[at + 2])
                        .putLong(write + VkWriteDescriptorSet.PIMAGEINFO, infoAddress);
            } else if (type == CgBindingLayout.Type.TEXEL_BUFFER) {
                m.putLong(info, w[at]).putLong(write + VkWriteDescriptorSet.PTEXELBUFFERVIEW, infoAddress);
            } else {
                m.putLong(info + VkDescriptorBufferInfo.BUFFER, w[at])
                        .putLong(info + VkDescriptorBufferInfo.OFFSET, w[at + 1])
                        .putLong(info + VkDescriptorBufferInfo.RANGE, w[at + 2])
                        .putLong(write + VkWriteDescriptorSet.PBUFFERINFO, infoAddress);
            }
        }
        device.pushes++;
        device.pushWrites += changed;
        nvkCmdPushDescriptorSetKHR(cmd, bindPoint, pipelineLayout, 0, changed, s.address);
        pushed.keep(cmdAddress, pipelineLayout, n);
    }
}
