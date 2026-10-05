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

import static org.lwjgl.vulkan.KHRPushDescriptor.nvkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Bindings pushed into set 0 at a render pass's or a compute pass's bind point. Filled in {@link VulkanScratch}: a
 * write per binding, then an info per binding, since this runs per draw and per dispatch.
 */
final class VulkanDescriptors {

    private static final int WRITE = VkWriteDescriptorSet.SIZEOF;
    private static final int INFO = Math.max(VkDescriptorBufferInfo.SIZEOF, VkDescriptorImageInfo.SIZEOF);

    private VulkanDescriptors() {}

    static void push(CgVulkanDevice device, VkCommandBuffer cmd, int bindPoint, long pipelineLayout, CgBindings b) {
        int n = b.count();
        if (n == 0) return;
        // Before the scratch is taken: laying an image records a barrier, which fills the scratch too.
        for (int i = 0; i < n; i++) {
            CgBindingLayout.Type type = b.type(i);
            if (type != CgBindingLayout.Type.SAMPLED_TEXTURE && type != CgBindingLayout.Type.STORAGE_IMAGE) continue;
            VulkanTexture t = (VulkanTexture) b.view(i).texture();
            if (t.unlaid || t.transferOnly) device.lay(t);
        }
        VulkanScratch s = VulkanScratch.get(n * (WRITE + INFO));
        ByteBuffer m = s.bytes;
        for (int i = 0; i < n; i++) {
            int w = i * WRITE, info = n * WRITE + i * INFO;
            long infoAddress = s.address + info;
            CgBindingLayout.Type type = b.type(i);
            m.putInt(w + VkWriteDescriptorSet.STYPE, VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .putLong(w + VkWriteDescriptorSet.PNEXT, 0L)
                    .putLong(w + VkWriteDescriptorSet.DSTSET, 0L)
                    .putInt(w + VkWriteDescriptorSet.DSTBINDING, b.binding(i))
                    .putInt(w + VkWriteDescriptorSet.DSTARRAYELEMENT, 0)
                    .putInt(w + VkWriteDescriptorSet.DESCRIPTORCOUNT, 1)
                    .putInt(w + VkWriteDescriptorSet.DESCRIPTORTYPE, CgVulkanDevice.descriptorType(type))
                    .putLong(w + VkWriteDescriptorSet.PIMAGEINFO, 0L)
                    .putLong(w + VkWriteDescriptorSet.PBUFFERINFO, 0L)
                    .putLong(w + VkWriteDescriptorSet.PTEXELBUFFERVIEW, 0L);
            if (type == CgBindingLayout.Type.SAMPLED_TEXTURE || type == CgBindingLayout.Type.STORAGE_IMAGE) {
                boolean sampled = type == CgBindingLayout.Type.SAMPLED_TEXTURE;
                VulkanTexture t = (VulkanTexture) b.view(i).texture();
                m.putLong(info + VkDescriptorImageInfo.SAMPLER, sampled ? ((VulkanSampler) b.sampler(i)).sampler : 0L)
                        .putLong(info + VkDescriptorImageInfo.IMAGEVIEW, t.view(device.vk(), b.view(i), false))
                        // A host's image is sampled where the host keeps it.
                        .putInt(info + VkDescriptorImageInfo.IMAGELAYOUT, !sampled ? VK_IMAGE_LAYOUT_GENERAL
                                : t.borrowed() ? t.resting : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                        .putLong(w + VkWriteDescriptorSet.PIMAGEINFO, infoAddress);
            } else if (type == CgBindingLayout.Type.TEXEL_BUFFER) {
                m.putLong(info, device.texelView((VulkanBuffer) b.buffer(i), b.offset(i), b.size(i), b.texelFormat(i)))
                        .putLong(w + VkWriteDescriptorSet.PTEXELBUFFERVIEW, infoAddress);
            } else {
                m.putLong(info + VkDescriptorBufferInfo.BUFFER, ((VulkanBuffer) b.buffer(i)).buffer)
                        .putLong(info + VkDescriptorBufferInfo.OFFSET, b.offset(i))
                        .putLong(info + VkDescriptorBufferInfo.RANGE, b.size(i) > 0 ? b.size(i) : VK_WHOLE_SIZE)
                        .putLong(w + VkWriteDescriptorSet.PBUFFERINFO, infoAddress);
            }
        }
        nvkCmdPushDescriptorSetKHR(cmd, bindPoint, pipelineLayout, 0, n, s.address);
    }
}
