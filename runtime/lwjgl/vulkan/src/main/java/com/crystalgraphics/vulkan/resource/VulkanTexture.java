package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.vulkan.command.VulkanBarriers;
import com.crystalgraphics.vulkan.format.VulkanCheck;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A {@code VkImage} with its layout per mip and layer, and its views cached by range. Outside a pass and a transfer,
 * every subresource of a sampled texture rests in {@code SHADER_READ_ONLY_OPTIMAL}, so a pass samples it as found.
 */
public final class VulkanTexture implements CgGpuTexture {

    public final Desc desc;
    public final long image;
    public final long allocation;
    public final int format;
    public final int aspect;
    public final int layers;
    /** Where a subresource returns after a transfer or a pass. */
    public final int resting;
    private final int[] layouts;
    private final Map<Long, Long> views = new HashMap<>();

    public VulkanTexture(Desc desc, long image, long allocation, int format, int aspect) {
        this.desc = desc;
        this.image = image;
        this.allocation = allocation;
        this.format = format;
        this.aspect = aspect;
        this.layers = desc.kind() == Kind.D3 ? 1 : desc.depthOrLayers();
        this.layouts = new int[desc.mips() * layers];
        boolean depth = (aspect & VK_IMAGE_ASPECT_COLOR_BIT) == 0;
        this.resting = desc.usage().contains(Usage.SAMPLED) ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                : desc.usage().contains(Usage.ATTACHMENT)
                ? (depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }

    @Override public Desc desc() { return desc; }

    int layout(int mip, int layer) {
        return layouts[mip * layers + layer];
    }

    /**
     * Moves a range to {@code layout}, one barrier per run of subresources that share an old layout.
     *
     * @return barriers recorded
     */
    public int transition(VkCommandBuffer cmd, int baseMip, int mips, int baseLayer, int count, int layout,
                   int dstStage, int dstAccess) {
        int barriers = 0;
        for (int mip = baseMip; mip < baseMip + mips; mip++) {
            int layer = baseLayer;
            while (layer < baseLayer + count) {
                int old = layout(mip, layer);
                int end = layer + 1;
                while (end < baseLayer + count && layout(mip, end) == old) end++;
                if (old != layout) {
                    VulkanBarriers.image(cmd, image, aspect, mip, 1, layer, end - layer, old, layout,
                            srcStage(old), srcAccess(old), dstStage, dstAccess);
                    barriers++;
                    for (int l = layer; l < end; l++) layouts[mip * layers + l] = layout;
                }
                layer = end;
            }
        }
        return barriers;
    }

    public int transitionAll(VkCommandBuffer cmd, int layout, int dstStage, int dstAccess) {
        return transition(cmd, 0, desc.mips(), 0, layers, layout, dstStage, dstAccess);
    }

    /** What the last use of a subresource in {@code layout} might still be doing. */
    private static int srcStage(int layout) {
        switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED: return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL: return VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL: return VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
                return VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            default: return VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
    }

    private static int srcAccess(int layout) {
        switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL: return VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL: return VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL: return VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            default: return 0;                          // a read: nothing to make visible, only to wait for
        }
    }

    /** The view a pass renders into or a shader samples: one mip and layer, or the range the view names. */
    public long view(VkDevice device, CgTextureView v, boolean attachment) {
        long key = ((long) v.baseMip() << 48) | ((long) v.mips() << 32) | ((long) v.baseLayer() << 16)
                | ((long) v.layers() << 1) | (attachment ? 1 : 0);
        Long cached = views.get(key);
        if (cached != null) return cached;
        int type;
        if (attachment) type = VK_IMAGE_VIEW_TYPE_2D;
        else if (desc.kind() == Kind.D3) type = VK_IMAGE_VIEW_TYPE_3D;
        else if (desc.kind() == Kind.CUBE && v.layers() == 6) type = VK_IMAGE_VIEW_TYPE_CUBE;
        else if (desc.kind() == Kind.D2_ARRAY || v.layers() > 1) type = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
        else type = VK_IMAGE_VIEW_TYPE_2D;
        // A shader samples one aspect of a depth-stencil image: its depth.
        int viewAspect = attachment || aspect == VK_IMAGE_ASPECT_COLOR_BIT ? aspect
                : (aspect & VK_IMAGE_ASPECT_DEPTH_BIT) != 0 ? VK_IMAGE_ASPECT_DEPTH_BIT : aspect;
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(type).format(format);
            ci.subresourceRange().aspectMask(viewAspect).baseMipLevel(v.baseMip()).levelCount(v.mips())
                    .baseArrayLayer(v.baseLayer()).layerCount(v.layers());
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateImageView(device, ci, null, lp), "vkCreateImageView");
            views.put(key, lp.get(0));
            return lp.get(0);
        }
    }

    public void destroyViews(VkDevice device) {
        for (long v : views.values()) vkDestroyImageView(device, v, null);
        views.clear();
    }
}
