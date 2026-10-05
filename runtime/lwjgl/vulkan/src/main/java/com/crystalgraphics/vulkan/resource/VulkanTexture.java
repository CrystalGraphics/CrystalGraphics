package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.vulkan.command.VulkanBarriers;
import com.crystalgraphics.vulkan.command.VulkanComputeCommandBuffer;
import com.crystalgraphics.vulkan.command.VulkanTransferCommandBuffer;
import com.crystalgraphics.vulkan.format.VulkanCheck;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A {@code VkImage} with its layout per mip and layer, and its views cached by range. Outside a pass and a transfer,
 * every subresource of a sampled texture rests in {@code SHADER_READ_ONLY_OPTIMAL}, so a pass samples it as found;
 * on a device with a transfer queue, a subresource nothing has touched yet stays {@code UNDEFINED} until the image is
 * first sampled ({@link #unlaid}).
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
    /** Whether a subresource may still be in {@code UNDEFINED}: its first layout is recorded at its first sampling. */
    public boolean unlaid;
    /** Whether only the transfer queue has used the image, so a copy into it may run there too. */
    public boolean transferOnly;
    /** The transfer batch its last copy there was recorded in: work on the frame's queue touching it waits for it. */
    public long transferBatch;
    /** Whether its mipmaps are owed once that batch lands. */
    public boolean mipmapsOwed;
    private final int[] layouts;
    private final Map<Long, Long> views = new HashMap<>();

    public VulkanTexture(Desc desc, long image, long allocation, int format, int aspect) {
        this(desc, image, allocation, format, aspect, restingFor(desc, aspect), VK_IMAGE_LAYOUT_UNDEFINED);
    }

    private VulkanTexture(Desc desc, long image, long allocation, int format, int aspect, int resting, int layout) {
        this.desc = desc;
        this.image = image;
        this.allocation = allocation;
        this.format = format;
        this.aspect = aspect;
        this.layers = desc.kind() == Kind.D3 ? 1 : desc.depthOrLayers();
        this.layouts = new int[desc.mips() * layers];
        Arrays.fill(layouts, layout);
        this.resting = resting;
    }

    /**
     * A host's image, in {@code layout} now and returned to it after every pass and transfer, which is what keeps
     * the host's own record of it true. It has no allocation: destroying it destroys only its views.
     */
    public static VulkanTexture borrowed(Desc desc, long image, int format, int aspect, int layout) {
        return new VulkanTexture(desc, image, 0L, format, aspect, layout, layout);
    }

    /** Whether the image belongs to a host rather than to this device. */
    public boolean borrowed() {
        return allocation == 0L;
    }

    private static int restingFor(Desc desc, int aspect) {
        boolean depth = (aspect & VK_IMAGE_ASPECT_COLOR_BIT) == 0;
        return desc.usage().contains(Usage.SAMPLED) ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                : desc.usage().contains(Usage.STORAGE) ? VK_IMAGE_LAYOUT_GENERAL
                : desc.usage().contains(Usage.ATTACHMENT)
                ? (depth ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                : VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    }

    /** Whether every subresource is in {@code layout}. */
    public boolean allIn(int layout) {
        for (int l : layouts) if (l != layout) return false;
        return true;
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
        return transition(cmd, baseMip, mips, baseLayer, count, layout, 0, 0, dstStage, dstAccess);
    }

    /**
     * {@link #transition(VkCommandBuffer, int, int, int, int, int, int, int)}, waiting on {@code srcStage} and
     * {@code srcAccess} too: what a queue whose barriers drop the stages a layout's last use implies must name.
     */
    public int transition(VkCommandBuffer cmd, int baseMip, int mips, int baseLayer, int count, int layout,
                          int srcStage, int srcAccess, int dstStage, int dstAccess) {
        requireUsableOn(cmd);
        return move(cmd, baseMip, mips, baseLayer, count, layout, srcStage, srcAccess, dstStage, dstAccess, false);
    }

    /**
     * Refuses a host's image in a command buffer for another queue family: the host made it for its own family
     * alone, and another family reads undefined contents from it. Any use but the transfer queue's ends
     * {@link #transferOnly}.
     */
    public void requireUsableOn(VkCommandBuffer cmd) {
        boolean transfer = cmd instanceof VulkanTransferCommandBuffer;
        if (borrowed() && (transfer || cmd instanceof VulkanComputeCommandBuffer)) {
            throw new IllegalStateException(desc.label() + " is the host's image: a queue of another family cannot "
                    + "use it");
        }
        if (!transfer) transferOnly = false;
    }

    public int transitionAll(VkCommandBuffer cmd, int layout, int dstStage, int dstAccess) {
        return transition(cmd, 0, desc.mips(), 0, layers, layout, dstStage, dstAccess);
    }

    /**
     * Moves every subresource in {@code from} to {@code layout}, one barrier per run of them, and leaves the rest:
     * what a batch of copies rests again without knowing which ranges it wrote.
     *
     * @return barriers recorded
     */
    public int transitionFrom(VkCommandBuffer cmd, int from, int layout, int dstStage, int dstAccess) {
        if (from == layout) return 0;
        requireUsableOn(cmd);
        int barriers = 0;
        for (int mip = 0; mip < desc.mips(); mip++) {
            int layer = 0;
            while (layer < layers) {
                if (layout(mip, layer) != from) {
                    layer++;
                    continue;
                }
                int end = layer + 1;
                while (end < layers && layout(mip, end) == from) end++;
                VulkanBarriers.image(cmd, image, aspect, mip, 1, layer, end - layer, from, layout, srcStage(from),
                        srcAccess(from), dstStage, dstAccess);
                barriers++;
                for (int l = layer; l < end; l++) layouts[mip * layers + l] = layout;
                layer = end;
            }
        }
        return barriers;
    }

    /**
     * Moves every subresource nothing has used yet, still {@code UNDEFINED}, to {@code layout}, and leaves the rest
     * where they are: one in a pass open now is that pass's to return.
     *
     * @return barriers recorded
     */
    public int layUndefined(VkCommandBuffer cmd, int layout, int dstStage, int dstAccess) {
        requireUsableOn(cmd);
        int barriers = 0;
        for (int mip = 0; mip < desc.mips(); mip++) {
            int layer = 0;
            while (layer < layers) {
                if (layout(mip, layer) != VK_IMAGE_LAYOUT_UNDEFINED) {
                    layer++;
                    continue;
                }
                int end = layer + 1;
                while (end < layers && layout(mip, end) == VK_IMAGE_LAYOUT_UNDEFINED) end++;
                VulkanBarriers.image(cmd, image, aspect, mip, 1, layer, end - layer, VK_IMAGE_LAYOUT_UNDEFINED, layout,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0, dstStage, dstAccess);
                barriers++;
                for (int l = layer; l < end; l++) layouts[mip * layers + l] = layout;
                layer = end;
            }
        }
        return barriers;
    }

    /**
     * The last use at {@code srcStage}/{@code srcAccess} made visible to the next, in {@code layout}: a dependency
     * where a subresource is in it already, a transition where not.
     *
     * @return barriers recorded
     */
    public int barrier(VkCommandBuffer cmd, int layout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        requireUsableOn(cmd);
        if (allIn(layout)) {
            VulkanBarriers.image(cmd, image, aspect, 0, desc.mips(), 0, layers, layout, layout, srcStage, srcAccess,
                    dstStage, dstAccess);
            return 1;
        }
        return move(cmd, 0, desc.mips(), 0, layers, layout, srcStage, srcAccess, dstStage, dstAccess, true);
    }

    /**
     * One barrier per run of subresources sharing a layout: a transition from any other, waiting on what that layout's
     * last use and {@code srcStage}/{@code srcAccess} might be doing; with {@code depend}, a dependency alone on a run
     * already in {@code layout}.
     */
    private int move(VkCommandBuffer cmd, int baseMip, int mips, int baseLayer, int count, int layout, int srcStage,
                     int srcAccess, int dstStage, int dstAccess, boolean depend) {
        int barriers = 0;
        for (int mip = baseMip; mip < baseMip + mips; mip++) {
            int layer = baseLayer;
            while (layer < baseLayer + count) {
                int old = layout(mip, layer);
                int end = layer + 1;
                while (end < baseLayer + count && layout(mip, end) == old) end++;
                if (old != layout) {
                    VulkanBarriers.image(cmd, image, aspect, mip, 1, layer, end - layer, old, layout,
                            srcStage(old) | srcStage, srcAccess(old) | srcAccess, dstStage, dstAccess);
                    barriers++;
                    for (int l = layer; l < end; l++) layouts[mip * layers + l] = layout;
                } else if (depend) {
                    VulkanBarriers.image(cmd, image, aspect, mip, 1, layer, end - layer, layout, layout,
                            srcStage, srcAccess, dstStage, dstAccess);
                    barriers++;
                }
                layer = end;
            }
        }
        return barriers;
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
            default: return VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        }
    }

    private static int srcAccess(int layout) {
        switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL: return VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL: return VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL: return VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_GENERAL: return VK_ACCESS_SHADER_WRITE_BIT;   // a storage image a kernel wrote
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
