package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgCommandEncoder;
import com.crystalgraphics.platform.device.CgFormat;
import com.crystalgraphics.platform.device.CgGpuBuffer;
import com.crystalgraphics.platform.device.CgGpuSampler;
import com.crystalgraphics.platform.device.CgGpuTexture;
import com.crystalgraphics.platform.device.CgPassDesc;
import com.crystalgraphics.platform.device.CgRenderPass;
import com.crystalgraphics.platform.device.CgTextureRegion;
import com.crystalgraphics.platform.device.CgTextureView;
import com.crystalgraphics.platform.device.CgTimerQuery;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageResolve;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

/**
 * The frame's commands outside a pass. Every transfer is fenced by a global barrier on each side and leaves each
 * texture it touched back in its resting layout: coarse, and correct (plan/device-vulkan.md §5).
 */
final class VulkanEncoder implements CgCommandEncoder {

    private static final int SHADER_STAGES = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final CgVulkanDevice device;
    private VulkanPass open;

    VulkanEncoder(CgVulkanDevice device) {
        this.device = device;
    }

    VulkanPass openPass() { return open; }

    void passEnded() { open = null; }

    private VkCommandBuffer cmd() {
        return device.host().commandBuffer();
    }

    private void outsidePass(String what) {
        if (open != null) throw new IllegalStateException(what + " inside a pass");
    }

    // ── passes ─────────────────────────────────────────────────────────────────

    @Override
    public CgRenderPass beginPass(CgPassDesc desc) {
        outsidePass("beginPass");
        VkCommandBuffer cmd = cmd();
        for (CgPassDesc.Color c : desc.colors()) {
            toAttachment(cmd, c.view(), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        }
        if (desc.depth() != null) {
            toAttachment(cmd, desc.depth().view(), VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        }
        try (MemoryStack stack = stackPush()) {
            VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1);
            info.renderArea().extent().set(desc.width(), desc.height());
            if (!desc.colors().isEmpty()) {
                VkRenderingAttachmentInfo.Buffer colors = VkRenderingAttachmentInfo.calloc(desc.colors().size(), stack);
                for (int i = 0; i < desc.colors().size(); i++) {
                    CgPassDesc.Color c = desc.colors().get(i);
                    VulkanTexture t = (VulkanTexture) c.view().texture();
                    VkRenderingAttachmentInfo a = colors.get(i).sType$Default()
                            .imageView(t.view(device.vk(), c.view(), true))
                            .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                            .loadOp(load(c.load())).storeOp(store(c.store()));
                    color(a.clearValue(), t.desc().format(), c.r(), c.g(), c.b(), c.a());
                }
                info.pColorAttachments(colors);
            }
            CgPassDesc.Depth d = desc.depth();
            if (d != null) {
                VulkanTexture t = (VulkanTexture) d.view().texture();
                long view = t.view(device.vk(), d.view(), true);
                if ((t.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0) {
                    VkRenderingAttachmentInfo a = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(view)
                            .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .loadOp(load(d.depthLoad())).storeOp(store(d.store()));
                    a.clearValue().depthStencil().depth(d.clearDepth()).stencil(d.clearStencil());
                    info.pDepthAttachment(a);
                }
                if ((t.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                    VkRenderingAttachmentInfo a = VkRenderingAttachmentInfo.calloc(stack).sType$Default().imageView(view)
                            .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                            .loadOp(load(d.stencilLoad())).storeOp(store(d.store()));
                    a.clearValue().depthStencil().depth(d.clearDepth()).stencil(d.clearStencil());
                    info.pStencilAttachment(a);
                }
            }
            vkCmdBeginRenderingKHR(cmd, info);
        }
        open = new VulkanPass(device, this, desc);
        return open;
    }

    private void toAttachment(VkCommandBuffer cmd, CgTextureView v, int layout, int stage, int access) {
        VulkanTexture t = (VulkanTexture) v.texture();
        device.barriers += t.transition(cmd, v.baseMip(), 1, v.baseLayer(), 1, layout, stage, access);
    }

    static void color(VkClearValue value, CgFormat format, float r, float g, float b, float a) {
        if (format.numeric() == CgFormat.Numeric.INT) {
            value.color().int32(0, (int) r).int32(1, (int) g).int32(2, (int) b).int32(3, (int) a);
        } else {
            value.color().float32(0, r).float32(1, g).float32(2, b).float32(3, a);
        }
    }

    private static int load(CgPassDesc.LoadOp op) {
        switch (op) {
            case CLEAR: return VK_ATTACHMENT_LOAD_OP_CLEAR;
            case DONT_CARE: return VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            default: return VK_ATTACHMENT_LOAD_OP_LOAD;
        }
    }

    private static int store(CgPassDesc.StoreOp op) {
        return op == CgPassDesc.StoreOp.DONT_CARE ? VK_ATTACHMENT_STORE_OP_DONT_CARE : VK_ATTACHMENT_STORE_OP_STORE;
    }

    // ── transfers ──────────────────────────────────────────────────────────────

    private void before(VkCommandBuffer cmd) {
        VulkanBarriers.global(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
        device.barriers++;
    }

    private void after(VkCommandBuffer cmd) {
        VulkanBarriers.global(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_HOST_READ_BIT);
        device.barriers++;
    }

    private void to(VkCommandBuffer cmd, VulkanTexture t, int mip, int mips, int layer, int layers, int layout) {
        int access = layout == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ? VK_ACCESS_TRANSFER_READ_BIT : VK_ACCESS_TRANSFER_WRITE_BIT;
        device.barriers += t.transition(cmd, mip, mips, layer, layers, layout, VK_PIPELINE_STAGE_TRANSFER_BIT, access);
    }

    private void rest(VkCommandBuffer cmd, VulkanTexture t, int mip, int mips, int layer, int layers) {
        device.barriers += t.transition(cmd, mip, mips, layer, layers, t.resting,
                SHADER_STAGES | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                        | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
    }

    /** A copy moves one aspect: a depth-stencil texture's depth. */
    private static int copyAspect(VulkanTexture t) {
        return (t.aspect & VK_IMAGE_ASPECT_COLOR_BIT) != 0 ? VK_IMAGE_ASPECT_COLOR_BIT
                : (t.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_STENCIL_BIT;
    }

    private static boolean volume(VulkanTexture t) {
        return t.desc().kind() == CgGpuTexture.Kind.D3;
    }

    @Override
    public void writeBuffer(CgGpuBuffer dst, long dstOffset, ByteBuffer data) {
        outsidePass("writeBuffer");
        int n = data.remaining();
        VulkanStaging.Region r = device.staging().take(n, 16);
        r.bytes().put(data.duplicate());
        VkCommandBuffer cmd = cmd();
        before(cmd);
        try (MemoryStack stack = stackPush()) {
            vkCmdCopyBuffer(cmd, r.buffer().buffer, ((VulkanBuffer) dst).buffer,
                    VkBufferCopy.calloc(1, stack).srcOffset(r.offset()).dstOffset(dstOffset).size(n));
        }
        after(cmd);
    }

    @Override
    public void copyBuffer(CgGpuBuffer src, long srcOffset, CgGpuBuffer dst, long dstOffset, long size) {
        outsidePass("copyBuffer");
        VkCommandBuffer cmd = cmd();
        before(cmd);
        try (MemoryStack stack = stackPush()) {
            vkCmdCopyBuffer(cmd, ((VulkanBuffer) src).buffer, ((VulkanBuffer) dst).buffer,
                    VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(size));
        }
        after(cmd);
    }

    @Override
    public void writeTexture(CgGpuTexture dst, CgTextureRegion region, ByteBuffer data) {
        outsidePass("writeTexture");
        VulkanTexture t = (VulkanTexture) dst;
        VulkanStaging.Region r = device.staging().take(data.remaining(), Math.max(16, t.desc().format().bytes()));
        r.bytes().put(data.duplicate());
        int layer = volume(t) ? 0 : region.z(), layers = volume(t) ? 1 : region.depth();
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, t, region.mip(), 1, layer, layers, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer c = VkBufferImageCopy.calloc(1, stack).bufferOffset(r.offset());
            region(c.get(0), t, region, layer, layers);
            vkCmdCopyBufferToImage(cmd, r.buffer().buffer, t.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, c);
        }
        rest(cmd, t, region.mip(), 1, layer, layers);
        after(cmd);
    }

    private static void region(VkBufferImageCopy c, VulkanTexture t, CgTextureRegion region, int layer, int layers) {
        c.imageSubresource().aspectMask(copyAspect(t)).mipLevel(region.mip()).baseArrayLayer(layer).layerCount(layers);
        c.imageOffset().set(region.x(), region.y(), volume(t) ? region.z() : 0);
        c.imageExtent().set(region.width(), region.height(), volume(t) ? region.depth() : 1);
    }

    @Override
    public void copyTexture(CgGpuTexture src, CgTextureRegion sr, CgGpuTexture dst, CgTextureRegion dr) {
        outsidePass("copyTexture");
        VulkanTexture s = (VulkanTexture) src, d = (VulkanTexture) dst;
        int sl = volume(s) ? 0 : sr.z(), dl = volume(d) ? 0 : dr.z(), layers = volume(s) ? 1 : sr.depth();
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, s, sr.mip(), 1, sl, layers, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        to(cmd, d, dr.mip(), 1, dl, layers, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkImageCopy.Buffer c = VkImageCopy.calloc(1, stack);
            c.srcSubresource().aspectMask(copyAspect(s)).mipLevel(sr.mip()).baseArrayLayer(sl).layerCount(layers);
            c.srcOffset().set(sr.x(), sr.y(), volume(s) ? sr.z() : 0);
            c.dstSubresource().aspectMask(copyAspect(d)).mipLevel(dr.mip()).baseArrayLayer(dl).layerCount(layers);
            c.dstOffset().set(dr.x(), dr.y(), volume(d) ? dr.z() : 0);
            c.extent().set(sr.width(), sr.height(), volume(s) ? sr.depth() : 1);
            vkCmdCopyImage(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, c);
        }
        rest(cmd, s, sr.mip(), 1, sl, layers);
        rest(cmd, d, dr.mip(), 1, dl, layers);
        after(cmd);
    }

    /** A same-size, unflipped blit between equal formats is a copy: a depth format need not support blits. */
    @Override
    public void blit(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
                     CgTextureView dst, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter) {
        outsidePass("blit");
        VulkanTexture s = (VulkanTexture) src.texture(), d = (VulkanTexture) dst.texture();
        boolean copy = s.format == d.format && sx1 - sx0 == dx1 - dx0 && sy1 - sy0 == dy1 - dy0
                && sx1 > sx0 && sy1 > sy0;
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, s, src.baseMip(), 1, src.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        to(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            if (copy) {
                VkImageCopy.Buffer c = VkImageCopy.calloc(1, stack);
                c.srcSubresource().aspectMask(s.aspect).mipLevel(src.baseMip()).baseArrayLayer(src.baseLayer()).layerCount(1);
                c.srcOffset().set(sx0, sy0, 0);
                c.dstSubresource().aspectMask(d.aspect).mipLevel(dst.baseMip()).baseArrayLayer(dst.baseLayer()).layerCount(1);
                c.dstOffset().set(dx0, dy0, 0);
                c.extent().set(sx1 - sx0, sy1 - sy0, 1);
                vkCmdCopyImage(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, c);
            } else {
                VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().aspectMask(s.aspect).mipLevel(src.baseMip()).baseArrayLayer(src.baseLayer()).layerCount(1);
                b.srcOffsets(0).set(sx0, sy0, 0);
                b.srcOffsets(1).set(sx1, sy1, 1);
                b.dstSubresource().aspectMask(d.aspect).mipLevel(dst.baseMip()).baseArrayLayer(dst.baseLayer()).layerCount(1);
                b.dstOffsets(0).set(dx0, dy0, 0);
                b.dstOffsets(1).set(dx1, dy1, 1);
                vkCmdBlitImage(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, b, VulkanFormats.filter(filter));
            }
        }
        rest(cmd, s, src.baseMip(), 1, src.baseLayer(), 1);
        rest(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1);
        after(cmd);
    }

    @Override
    public void resolve(CgTextureView src, CgTextureView dst) {
        outsidePass("resolve");
        VulkanTexture s = (VulkanTexture) src.texture(), d = (VulkanTexture) dst.texture();
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, s, src.baseMip(), 1, src.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        to(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkImageResolve.Buffer r = VkImageResolve.calloc(1, stack);
            r.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(src.baseMip()).baseArrayLayer(src.baseLayer()).layerCount(1);
            r.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(dst.baseMip()).baseArrayLayer(dst.baseLayer()).layerCount(1);
            r.extent().set(src.width(), src.height(), 1);
            vkCmdResolveImage(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, r);
        }
        rest(cmd, s, src.baseMip(), 1, src.baseLayer(), 1);
        rest(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1);
        after(cmd);
    }

    @Override
    public void generateMipmaps(CgGpuTexture texture) {
        outsidePass("generateMipmaps");
        VulkanTexture t = (VulkanTexture) texture;
        VkCommandBuffer cmd = cmd();
        before(cmd);
        int w = t.desc().width(), h = t.desc().height(), z = volume(t) ? t.desc().depthOrLayers() : 1;
        try (MemoryStack stack = stackPush()) {
            for (int mip = 1; mip < t.desc().mips(); mip++) {
                to(cmd, t, mip - 1, 1, 0, t.layers, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                to(cmd, t, mip, 1, 0, t.layers, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkImageBlit.Buffer b = VkImageBlit.calloc(1, stack);
                b.srcSubresource().aspectMask(t.aspect).mipLevel(mip - 1).layerCount(t.layers);
                b.srcOffsets(1).set(Math.max(1, w >> (mip - 1)), Math.max(1, h >> (mip - 1)), Math.max(1, z >> (mip - 1)));
                b.dstSubresource().aspectMask(t.aspect).mipLevel(mip).layerCount(t.layers);
                b.dstOffsets(1).set(Math.max(1, w >> mip), Math.max(1, h >> mip), Math.max(1, z >> mip));
                vkCmdBlitImage(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, b, VK_FILTER_LINEAR);
            }
        }
        rest(cmd, t, 0, t.desc().mips(), 0, t.layers);
        after(cmd);
    }

    @Override
    public void readTexture(CgGpuTexture src, CgTextureRegion region, ByteBuffer out) {
        outsidePass("readTexture");
        VulkanTexture t = (VulkanTexture) src;
        long size = region.texels() * t.desc().format().bytes();
        VulkanStaging.Region r = device.staging().take(size, 16);
        copyOut(t, region, r.buffer(), r.offset());
        device.host().submitAndWait();
        out.put(r.bytes());
    }

    @Override
    public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion region, CgGpuBuffer dst, long dstOffset) {
        outsidePass("copyTextureToBuffer");
        copyOut((VulkanTexture) src, region, (VulkanBuffer) dst, dstOffset);
    }

    private void copyOut(VulkanTexture t, CgTextureRegion region, VulkanBuffer dst, long dstOffset) {
        int layer = volume(t) ? 0 : region.z(), layers = volume(t) ? 1 : region.depth();
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, t, region.mip(), 1, layer, layers, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer c = VkBufferImageCopy.calloc(1, stack).bufferOffset(dstOffset);
            region(c.get(0), t, region, layer, layers);
            vkCmdCopyImageToBuffer(cmd, t.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.buffer, c);
        }
        rest(cmd, t, region.mip(), 1, layer, layers);
        after(cmd);
    }

    // ── timers ─────────────────────────────────────────────────────────────────

    @Override
    public void beginTimer(CgTimerQuery query) {
        VulkanTimerQuery q = (VulkanTimerQuery) query;
        vkResetQueryPool(device.vk(), q.pool, 0, 2);
        q.frame = -1;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, q.pool, 0);
    }

    @Override
    public void endTimer(CgTimerQuery query) {
        VulkanTimerQuery q = (VulkanTimerQuery) query;
        vkCmdWriteTimestamp(cmd(), VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, q.pool, 1);
        q.frame = device.frameIndex();
    }
}
