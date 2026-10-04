package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.command.CgCommandEncoder;
import com.crystalgraphics.platform.device.command.CgComputePass;
import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.resource.CgTimerQuery;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.format.VulkanFormats;
import com.crystalgraphics.vulkan.resource.VulkanBuffer;
import com.crystalgraphics.vulkan.resource.VulkanStaging;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import com.crystalgraphics.vulkan.resource.VulkanTimerQuery;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearDepthStencilValue;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageResolve;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import java.nio.ByteBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.nvkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkResetQueryPool;

/**
 * The frame's commands outside a pass. Every transfer is fenced by a global barrier on each side and leaves each
 * texture it touched back in its resting layout: coarse, and correct (plan/device-vulkan.md §5).
 */
public final class VulkanEncoder implements CgCommandEncoder {

    private static final int SHADER_STAGES = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    private final CgVulkanDevice device;
    private VulkanPass open;
    private VulkanComputePass openCompute;

    public VulkanEncoder(CgVulkanDevice device) {
        this.device = device;
    }

    public VulkanPass openPass() { return open; }

    /** Whether a pass is open: what a host checks before taking its command stream back. */
    public boolean passOpen() { return open != null; }

    void passEnded() { open = null; }

    void computeEnded() { openCompute = null; }

    private VkCommandBuffer cmd() {
        return device.host().commandBuffer();
    }

    private void outsidePass(String what) {
        if (open != null) throw new IllegalStateException(what + " inside a pass");
    }

    // ── passes ─────────────────────────────────────────────────────────────────

    @Override
    public CgComputePass beginCompute(String label) {
        outsidePass("beginCompute");
        if (openCompute != null) throw new IllegalStateException("beginCompute inside a compute pass");
        openCompute = new VulkanComputePass(device, this);
        return openCompute;
    }

    @Override
    public void bufferBarrier(CgGpuBuffer buffer, int from, int to) {
        outsidePass("bufferBarrier");
        VulkanBarriers.buffer(cmd(), ((VulkanBuffer) buffer).buffer, VulkanAccess.stage(from), VulkanAccess.access(from),
                VulkanAccess.stage(to), VulkanAccess.access(to));
        device.barriers++;
    }

    @Override
    public void imageBarrier(CgGpuTexture texture, int from, int to) {
        outsidePass("imageBarrier");
        device.barriers += ((VulkanTexture) texture).barrier(cmd(), VulkanAccess.layout(to), VulkanAccess.stage(from),
                VulkanAccess.access(from), VulkanAccess.stage(to), VulkanAccess.access(to));
    }

    @Override
    public void memoryBarrier(int from, int to) {
        outsidePass("memoryBarrier");
        VulkanBarriers.global(cmd(), VulkanAccess.stage(from), VulkanAccess.access(from), VulkanAccess.stage(to),
                VulkanAccess.access(to));
        device.barriers++;
    }

    @Override
    public CgRenderPass beginPass(CgPassDesc desc) {
        outsidePass("beginPass");
        if (openCompute != null) throw new IllegalStateException("beginPass inside a compute pass");
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
        // In VulkanScratch: the rendering info, each colour attachment, then depth and stencil.
        int n = desc.colors().size(), size = VkRenderingAttachmentInfo.SIZEOF;
        int colorsAt = (VkRenderingInfo.SIZEOF + 7) & ~7, depthAt = colorsAt + n * size, stencilAt = depthAt + size;
        VulkanScratch s = VulkanScratch.get(stencilAt + size);
        ByteBuffer m = s.zero(stencilAt + size);
        int extent = VkRenderingInfo.RENDERAREA + VkRect2D.EXTENT;
        m.putInt(VkRenderingInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .putInt(extent + VkExtent2D.WIDTH, desc.width()).putInt(extent + VkExtent2D.HEIGHT, desc.height())
                .putInt(VkRenderingInfo.LAYERCOUNT, 1)
                .putInt(VkRenderingInfo.COLORATTACHMENTCOUNT, n)
                .putLong(VkRenderingInfo.PCOLORATTACHMENTS, n == 0 ? 0L : s.address + colorsAt);
        for (int i = 0; i < n; i++) {
            CgPassDesc.Color c = desc.colors().get(i);
            VulkanTexture t = (VulkanTexture) c.view().texture();
            int at = colorsAt + i * size;
            attachment(m, at, t.view(device.vk(), c.view(), true), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    load(c.load()), store(c.store()));
            int color = at + VkRenderingAttachmentInfo.CLEARVALUE + VkClearValue.COLOR;
            if (t.desc().format().numeric() == CgFormat.Numeric.INT) {
                int ints = color + VkClearColorValue.INT32;
                m.putInt(ints, (int) c.r()).putInt(ints + 4, (int) c.g()).putInt(ints + 8, (int) c.b())
                        .putInt(ints + 12, (int) c.a());
            } else {
                int floats = color + VkClearColorValue.FLOAT32;
                m.putFloat(floats, c.r()).putFloat(floats + 4, c.g()).putFloat(floats + 8, c.b())
                        .putFloat(floats + 12, c.a());
            }
        }
        CgPassDesc.Depth d = desc.depth();
        if (d != null) {
            VulkanTexture t = (VulkanTexture) d.view().texture();
            long view = t.view(device.vk(), d.view(), true);
            if ((t.aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0) {
                depthStencil(m, depthAt, view, load(d.depthLoad()), store(d.store()), d);
                m.putLong(VkRenderingInfo.PDEPTHATTACHMENT, s.address + depthAt);
            }
            if ((t.aspect & VK_IMAGE_ASPECT_STENCIL_BIT) != 0) {
                depthStencil(m, stencilAt, view, load(d.stencilLoad()), store(d.store()), d);
                m.putLong(VkRenderingInfo.PSTENCILATTACHMENT, s.address + stencilAt);
            }
        }
        nvkCmdBeginRenderingKHR(cmd, s.address);
        open = new VulkanPass(device, this, desc);
        return open;
    }

    private static void attachment(ByteBuffer m, int at, long view, int layout, int load, int store) {
        m.putInt(at + VkRenderingAttachmentInfo.STYPE, VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .putLong(at + VkRenderingAttachmentInfo.IMAGEVIEW, view)
                .putInt(at + VkRenderingAttachmentInfo.IMAGELAYOUT, layout)
                .putInt(at + VkRenderingAttachmentInfo.LOADOP, load)
                .putInt(at + VkRenderingAttachmentInfo.STOREOP, store);
    }

    private static void depthStencil(ByteBuffer m, int at, long view, int load, int store, CgPassDesc.Depth d) {
        attachment(m, at, view, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, load, store);
        int clear = at + VkRenderingAttachmentInfo.CLEARVALUE + VkClearValue.DEPTHSTENCIL;
        m.putFloat(clear + VkClearDepthStencilValue.DEPTH, d.clearDepth())
                .putInt(clear + VkClearDepthStencilValue.STENCIL, d.clearStencil());
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
    public void fillBuffer(CgGpuBuffer dst, long dstOffset, long size, int value) {
        outsidePass("fillBuffer");
        VkCommandBuffer cmd = cmd();
        before(cmd);
        vkCmdFillBuffer(cmd, ((VulkanBuffer) dst).buffer, dstOffset, size, value);
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
        if ((s.aspect & VK_IMAGE_ASPECT_COLOR_BIT) == 0 && s.format != d.format) {
            depthCopy(src, sx0, sy0, sx1, sy1, dst, dx0, dy0, dx1, dy1);
            return;
        }
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

    /**
     * A depth blit between formats Vulkan will not blit or copy between, when their depth reads back the same way:
     * D24 with and without stencil, D32F with and without. Through a buffer, depth aspect only: GL's
     * {@code DEPTH24_STENCIL8} surface into a {@code DEPTH24} snapshot is the case.
     */
    private void depthCopy(CgTextureView src, int sx0, int sy0, int sx1, int sy1,
                           CgTextureView dst, int dx0, int dy0, int dx1, int dy1) {
        VulkanTexture s = (VulkanTexture) src.texture(), d = (VulkanTexture) dst.texture();
        int w = sx1 - sx0, h = sy1 - sy0;
        if (w != dx1 - dx0 || h != dy1 - dy0 || w <= 0 || h <= 0 || depthLayout(s.format) != depthLayout(d.format)
                || depthLayout(s.format) == 0) {
            throw new UnsupportedOperationException("A depth blit from format " + s.format + " to " + d.format
                    + ", scaled or flipped or between depth layouts that differ");
        }
        VulkanBuffer scratch = device.copyScratch((long) w * h * 4);
        VkCommandBuffer cmd = cmd();
        before(cmd);
        to(cmd, s, src.baseMip(), 1, src.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        to(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        try (MemoryStack stack = stackPush()) {
            VkBufferImageCopy.Buffer c = VkBufferImageCopy.calloc(1, stack);
            c.imageSubresource().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).mipLevel(src.baseMip())
                    .baseArrayLayer(src.baseLayer()).layerCount(1);
            c.imageOffset().set(sx0, sy0, 0);
            c.imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, scratch.buffer, c);
            VulkanBarriers.global(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            device.barriers++;
            c.imageSubresource().mipLevel(dst.baseMip()).baseArrayLayer(dst.baseLayer());
            c.imageOffset().set(dx0, dy0, 0);
            vkCmdCopyBufferToImage(cmd, scratch.buffer, d.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, c);
        }
        rest(cmd, s, src.baseMip(), 1, src.baseLayer(), 1);
        rest(cmd, d, dst.baseMip(), 1, dst.baseLayer(), 1);
        after(cmd);
    }

    /** How a format's depth aspect lands in a buffer: 1 for 24 bits in 32, 2 for a float; 0 for anything else. */
    private static int depthLayout(int format) {
        switch (format) {
            case VK_FORMAT_D24_UNORM_S8_UINT: case VK_FORMAT_X8_D24_UNORM_PACK32: return 1;
            case VK_FORMAT_D32_SFLOAT: case VK_FORMAT_D32_SFLOAT_S8_UINT: return 2;
            default: return 0;
        }
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
    public void readBuffer(CgGpuBuffer src, long srcOffset, ByteBuffer out) {
        outsidePass("readBuffer");
        int size = out.remaining();
        VulkanStaging.Region r = device.staging().take(size, 16);
        copyBuffer(src, srcOffset, r.buffer(), r.offset(), size);
        device.host().submitAndWait();
        out.put(r.bytes());
    }

    @Override
    public void copyTextureToBuffer(CgGpuTexture src, CgTextureRegion region, CgGpuBuffer dst, long dstOffset) {
        outsidePass("copyTextureToBuffer");
        copyOut((VulkanTexture) src, region, (VulkanBuffer) dst, dstOffset);
    }

    @Override
    public void finish() {
        outsidePass("finish");
        device.host().submitAndWait();
    }

    @Override
    public void beginAsync() {
        outsidePass("beginAsync");
        if (openCompute != null) throw new IllegalStateException("beginAsync inside a compute pass");
        device.host().beginAsync();
    }

    @Override
    public long endAsync() {
        if (openCompute != null) throw new IllegalStateException("endAsync inside a compute pass");
        return device.host().endAsync();
    }

    @Override
    public void waitAsync(long point) {
        outsidePass("waitAsync");
        if (openCompute != null) throw new IllegalStateException("waitAsync inside a compute pass");
        device.host().waitAsync(point);
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
