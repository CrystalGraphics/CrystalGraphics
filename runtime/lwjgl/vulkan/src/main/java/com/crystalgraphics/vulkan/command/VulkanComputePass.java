package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.command.CgComputePass;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.resource.VulkanBindingLayout;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanComputePipeline;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pass on the frame's command buffer. A storage image it binds moves to {@code GENERAL} and rests again
 * when the pass ends, as a render pass's attachments do, so a later pass samples it with no barrier of its own.
 */
final class VulkanComputePass implements CgComputePass {

    private static final int AFTER = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;

    private final CgVulkanDevice device;
    private final VulkanEncoder encoder;
    private final List<CgTextureView> images = new ArrayList<>();
    private VulkanComputePipeline pipeline;

    VulkanComputePass(CgVulkanDevice device, VulkanEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
    }

    private VkCommandBuffer cmd() {
        return device.host().commandBuffer();
    }

    @Override
    public void setPipeline(CgComputePipeline p) {
        pipeline = (VulkanComputePipeline) p;
        vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline);
    }

    @Override
    public void pushBindings(CgBindings b) {
        if (pipeline == null) throw new IllegalStateException("Bindings pushed before a pipeline");
        for (int i = 0; i < b.count(); i++) {
            if (b.type(i) != CgBindingLayout.Type.STORAGE_IMAGE) continue;
            CgTextureView view = b.view(i);
            device.barriers += ((VulkanTexture) view.texture()).transition(cmd(), view.baseMip(), 1, view.baseLayer(), 1,
                    VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            images.add(view);
        }
        VulkanDescriptors.push(device, cmd(), VK_PIPELINE_BIND_POINT_COMPUTE,
                ((VulkanBindingLayout) pipeline.layout()).pipelineLayout, b);
    }

    @Override
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        vkCmdDispatch(cmd(), groupsX, groupsY, groupsZ);
    }

    @Override
    public void dispatchIndirect(CgGpuBuffer buffer, long offset) {
        vkCmdDispatchIndirect(cmd(), ((VulkanBuffer) buffer).buffer, offset);
    }

    /** Every storage image back to rest, its writes visible to whatever reads it next. */
    @Override
    public void end() {
        for (CgTextureView view : images) {
            VulkanTexture t = (VulkanTexture) view.texture();
            device.barriers += t.transition(cmd(), view.baseMip(), 1, view.baseLayer(), 1, t.resting, AFTER,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        }
        encoder.computeEnded();
    }
}
