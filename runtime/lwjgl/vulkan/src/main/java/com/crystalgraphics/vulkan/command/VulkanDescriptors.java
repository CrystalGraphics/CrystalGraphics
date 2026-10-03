package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanSampler;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/** Bindings pushed into set 0 at a render pass's or a compute pass's bind point. */
final class VulkanDescriptors {

    private VulkanDescriptors() {}

    static void push(CgVulkanDevice device, VkCommandBuffer cmd, int bindPoint, long pipelineLayout, CgBindings b) {
        if (b.count() == 0) return;
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(b.count(), stack);
            for (int i = 0; i < b.count(); i++) {
                VkWriteDescriptorSet w = writes.get(i).sType$Default().dstBinding(b.binding(i))
                        .descriptorType(CgVulkanDevice.descriptorType(b.type(i)));
                CgBindingLayout.Type type = b.type(i);
                if (type == CgBindingLayout.Type.SAMPLED_TEXTURE) {
                    VulkanTexture t = (VulkanTexture) b.view(i).texture();
                    w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(((VulkanSampler) b.sampler(i)).sampler)
                            .imageView(t.view(device.vk(), b.view(i), false))
                            .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL));
                } else if (type == CgBindingLayout.Type.STORAGE_IMAGE) {
                    VulkanTexture t = (VulkanTexture) b.view(i).texture();
                    w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                            .imageView(t.view(device.vk(), b.view(i), false)).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                } else if (type == CgBindingLayout.Type.TEXEL_BUFFER) {
                    w.pTexelBufferView(stack.longs(device.texelView((VulkanBuffer) b.buffer(i), b.offset(i), b.size(i),
                            b.texelFormat(i))));
                } else {
                    w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(((VulkanBuffer) b.buffer(i)).buffer)
                            .offset(b.offset(i)).range(b.size(i) > 0 ? b.size(i) : VK_WHOLE_SIZE));
                }
                w.descriptorCount(1);
            }
            vkCmdPushDescriptorSetKHR(cmd, bindPoint, pipelineLayout, 0, writes);
        }
    }
}
