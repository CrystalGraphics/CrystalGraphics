package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.binding.VulkanBindingSet;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanComputePipeline;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A compute pass on the frame's command buffer. Where the device infers, a storage image it binds moves to
 * {@code GENERAL} and rests again when the pass ends, as a render pass's attachments do; where every transition is
 * stated, it is found in {@code GENERAL} and left there.
 */
final class VulkanComputePass implements CgComputePass {

    private static final int AFTER = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;

    private final CgVulkanDevice device;
    private final VulkanEncoder encoder;
    private final List<CgTextureView> images = new ArrayList<>();
    private VulkanComputePipeline pipeline;
    /**
     * Sets bound by index. A pipeline keeps those its layout shares with the last one's from set 0 up (Vulkan's pipeline
     * layout compatibility, push range included); a set from the first that differs is dropped, for the caller to bind again.
     */
    private final VulkanBindingSet[] sets = new VulkanBindingSet[8];

    VulkanComputePass(CgVulkanDevice device, VulkanEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
    }

    private VkCommandBuffer cmd() {
        encoder.endCopies();
        return device.host().commandBuffer();
    }

    @Override
    public void setPipeline(CgComputePipeline p) {
        VulkanComputePipeline was = pipeline;
        pipeline = (VulkanComputePipeline) p;
        vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipeline);
        if (was == null) {
            for (int i = 0; i < sets.length; i++) if (sets[i] != null) bind(i);
            return;
        }
        List<CgBindingLayout> before = was.layout().sets(), now = pipeline.layout().sets();
        boolean shared = was.layout().pushConstantBytes() == pipeline.layout().pushConstantBytes();
        for (int i = 0; i < sets.length; i++) {
            shared &= i < before.size() && i < now.size() && before.get(i) == now.get(i);
            if (!shared) sets[i] = null;
        }
    }

    @Override
    public void pushBindings(CgBindings b) {
        if (pipeline == null) throw new IllegalStateException("Bindings pushed before a pipeline");
        for (int i = 0; i < b.count(); i++) {
            if (b.type(i) == CgBindingLayout.Type.SAMPLED_TEXTURE) ((VulkanTexture) b.view(i).texture()).requireUsableOn(cmd());
            if (b.type(i) != CgBindingLayout.Type.STORAGE_IMAGE || !encoder.infers()) continue;
            CgTextureView view = b.view(i);
            int moved = ((VulkanTexture) view.texture()).transition(cmd(), view.baseMip(), 1, view.baseLayer(), 1,
                    VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            device.barriers += moved;
            if (moved > 0) images.add(view);
        }
        VulkanDescriptors.push(device, cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipelineLayout, pipeline.pushSet, b,
                encoder.computePushed);
    }

    @Override
    public void setBindingSet(int set, CgBindingSet bindings) {
        sets[set] = (VulkanBindingSet) bindings;
        if (pipeline != null) bind(set);
    }

    private void bind(int index) {
        if (index >= pipeline.layout().sets().size()) return;
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(cmd(), VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.pipelineLayout, index,
                    stack.longs(sets[index].set), null);
        }
    }

    @Override
    public void pushConstants(int offset, int value) {
        if (pipeline == null) throw new IllegalStateException("Push constants before a pipeline");
        try (MemoryStack stack = stackPush()) {
            vkCmdPushConstants(cmd(), pipeline.pipelineLayout, CgVulkanDevice.PUSH_CONSTANT_STAGES, offset, stack.ints(value));
        }
    }

    @Override
    public void pushConstants(int offset, ByteBuffer data) {
        if (pipeline == null) throw new IllegalStateException("Push constants before a pipeline");
        vkCmdPushConstants(cmd(), pipeline.pipelineLayout, CgVulkanDevice.PUSH_CONSTANT_STAGES, offset, data);
    }

    @Override
    public void dispatch(int groupsX, int groupsY, int groupsZ) {
        vkCmdDispatch(cmd(), groupsX, groupsY, groupsZ);
    }

    @Override
    public void dispatchIndirect(CgGpuBuffer buffer, long offset) {
        vkCmdDispatchIndirect(cmd(), ((VulkanBuffer) buffer).buffer, offset);
    }

    /** Every storage image it moved back to rest, its writes visible to whatever reads it next. */
    @Override
    public void end() {
        for (CgTextureView view : images) {
            VulkanTexture t = (VulkanTexture) view.texture();
            device.barriers += t.transition(cmd(), view.baseMip(), 1, view.baseLayer(), 1, t.resting, AFTER,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        }
        images.clear();
        encoder.computeEnded();
    }
}
