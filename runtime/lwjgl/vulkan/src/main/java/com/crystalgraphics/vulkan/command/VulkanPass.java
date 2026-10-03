package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.resource.VulkanBindingLayout;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanPipeline;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndirectCount;

/**
 * One {@code vkCmdBeginRenderingKHR} instance. Bindings are pushed per draw into set 0; the viewport is positive, so
 * GL's clip space lands on the same memory rows it does on GL (plan/device-vulkan.md §6).
 */
final class VulkanPass implements CgRenderPass {

    private final CgVulkanDevice device;
    private final VulkanEncoder encoder;
    private final CgPassDesc desc;
    private VulkanPipeline pipeline;

    VulkanPass(CgVulkanDevice device, VulkanEncoder encoder, CgPassDesc desc) {
        this.device = device;
        this.encoder = encoder;
        this.desc = desc;
    }

    private VkCommandBuffer cmd() {
        return device.host().commandBuffer();
    }

    @Override
    public void setPipeline(CgPipeline p) {
        pipeline = (VulkanPipeline) p;
        vkCmdBindPipeline(cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline);
    }

    @Override
    public void pushBindings(CgBindings b) {
        if (b.count() == 0) return;
        if (pipeline == null) throw new IllegalStateException("Bindings pushed before a pipeline");
        VulkanDescriptors.push(device, cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS,
                ((VulkanBindingLayout) pipeline.desc.layout()).pipelineLayout, b);
    }

    @Override
    public void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(cmd(), binding, stack.longs(((VulkanBuffer) buffer).buffer), stack.longs(offset));
        }
    }

    @Override
    public void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide) {
        vkCmdBindIndexBuffer(cmd(), ((VulkanBuffer) buffer).buffer, offset, wide ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16);
    }

    @Override
    public void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {
        try (MemoryStack stack = stackPush()) {
            vkCmdSetViewport(cmd(), 0, VkViewport.calloc(1, stack).x(x).y(y).width(width).height(height)
                    .minDepth(minDepth).maxDepth(maxDepth));
        }
    }

    @Override
    public void setScissor(int x, int y, int width, int height) {
        if (x < 0) { width += x; x = 0; }
        if (y < 0) { height += y; y = 0; }
        try (MemoryStack stack = stackPush()) {
            VkRect2D.Buffer rect = VkRect2D.calloc(1, stack);
            rect.offset().set(x, y);
            rect.extent().set(Math.max(0, width), Math.max(0, height));
            vkCmdSetScissor(cmd(), 0, rect);
        }
    }

    @Override
    public void setDepthBias(float constant, float slope) {
        vkCmdSetDepthBias(cmd(), constant, 0f, slope);
    }

    @Override
    public void setStencilReference(int reference) {
        vkCmdSetStencilReference(cmd(), VK_STENCIL_FACE_FRONT_AND_BACK, reference);
    }

    @Override
    public void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VkClearAttachment.Buffer ca = VkClearAttachment.calloc(1, stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .colorAttachment(attachment);
            VulkanEncoder.color(ca.get(0).clearValue(), desc.colors().get(attachment).view().texture().desc().format(),
                    r, g, b, a);
            clear(stack, ca, x, y, width, height);
        }
    }

    @Override
    public void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                                  int x, int y, int width, int height) {
        int aspect = (depth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
        try (MemoryStack stack = stackPush()) {
            VkClearAttachment.Buffer ca = VkClearAttachment.calloc(1, stack).aspectMask(aspect);
            ca.get(0).clearValue().depthStencil().depth(clearDepth).stencil(clearStencil);
            clear(stack, ca, x, y, width, height);
        }
    }

    /** A clear rectangle inside the render area; an empty one is no clear. */
    private void clear(MemoryStack stack, VkClearAttachment.Buffer what, int x, int y, int width, int height) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(desc.width(), x + width), y1 = Math.min(desc.height(), y + height);
        if (x1 <= x0 || y1 <= y0) return;
        VkClearRect.Buffer rect = VkClearRect.calloc(1, stack).baseArrayLayer(0).layerCount(1);
        rect.get(0).rect().offset().set(x0, y0);
        rect.get(0).rect().extent().set(x1 - x0, y1 - y0);
        vkCmdClearAttachments(cmd(), what, rect);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        vkCmdDraw(cmd(), vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        vkCmdDrawIndexed(cmd(), indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }

    @Override
    public void drawIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
        requireMultiDraw(drawCount);
        vkCmdDrawIndirect(cmd(), ((VulkanBuffer) buffer).buffer, offset, drawCount, stride);
    }

    @Override
    public void drawIndexedIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride) {
        requireMultiDraw(drawCount);
        vkCmdDrawIndexedIndirect(cmd(), ((VulkanBuffer) buffer).buffer, offset, drawCount, stride);
    }

    @Override
    public void drawIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws,
                                  int stride) {
        requireIndirectCount();
        vkCmdDrawIndirectCount(cmd(), ((VulkanBuffer) buffer).buffer, offset, ((VulkanBuffer) count).buffer, countOffset,
                maxDraws, stride);
    }

    @Override
    public void drawIndexedIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset,
                                         int maxDraws, int stride) {
        requireIndirectCount();
        vkCmdDrawIndexedIndirectCount(cmd(), ((VulkanBuffer) buffer).buffer, offset, ((VulkanBuffer) count).buffer,
                countOffset, maxDraws, stride);
    }

    private void requireMultiDraw(int drawCount) {
        if (drawCount > 1 && !device.info().multiDrawIndirect()) {
            throw new IllegalStateException("drawIndirect of " + drawCount + " commands: the device was created without "
                    + "multiDrawIndirect");
        }
    }

    private void requireIndirectCount() {
        if (!device.info().indirectCount()) {
            throw new IllegalStateException("drawIndirectCount: the device was created without drawIndirectCount");
        }
    }

    /** Ends rendering; an attachment a shader samples goes back to rest for the next pass to read. */
    @Override
    public void end() {
        VkCommandBuffer cmd = cmd();
        vkCmdEndRenderingKHR(cmd);
        for (CgPassDesc.Color c : desc.colors()) settle(cmd, (VulkanTexture) c.view().texture(), c);
        if (desc.depth() != null) {
            VulkanTexture t = (VulkanTexture) desc.depth().view().texture();
            device.barriers += t.transition(cmd, desc.depth().view().baseMip(), 1, desc.depth().view().baseLayer(), 1,
                    t.resting, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
        }
        encoder.passEnded();
    }

    private void settle(VkCommandBuffer cmd, VulkanTexture t, CgPassDesc.Color c) {
        device.barriers += t.transition(cmd, c.view().baseMip(), 1, c.view().baseLayer(), 1, t.resting,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);
    }
}
