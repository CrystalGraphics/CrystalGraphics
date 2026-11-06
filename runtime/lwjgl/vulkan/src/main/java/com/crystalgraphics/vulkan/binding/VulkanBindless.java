package com.crystalgraphics.vulkan.binding;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBindingFlagsCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * The bindless heap ({@code rhi.md} §2.3, set 3): a texture view with a sampler in each slot, read by index, in one
 * set bound once a command list. Descriptor indexing, as Unreal's Vulkan RHI keeps its heap: slots partially bound and
 * written after bind, so a pair takes a slot while frames using older slots are in flight.
 *
 * <pre>{@code
 * int slot = heap.texture(view, sampler, imageView, imageLayout, vkSampler);   // the pair's slot, written on first ask
 * heap.forget(texture);                                                       // destroyed: its frames have retired
 * }</pre>
 *
 * <p>A slot holds a view and a sampler together, as {@code ARB_bindless_texture}'s handle does: GLSL passes only a
 * whole sampler to a function, never one built from a texture and a sampler. A slot is given back only from
 * {@link #forget}, which the device calls as it destroys the texture, so no frame in flight reads a slot after it is
 * written again. Render thread.</p>
 */
public final class VulkanBindless {

    /** Slots: past any scene's live textures, within every descriptor-indexing device's limits. */
    public static final int SLOTS = 16384;

    public final VulkanBindingLayout layout;
    public final VulkanBindingSet set;
    private final VkDevice device;
    private final long pool;
    /** Each view's slots by sampler: looked up with no key made. */
    private final Map<CgTextureView, Map<CgGpuSampler, Integer>> slots = new HashMap<>();
    private final Map<CgGpuTexture, List<CgTextureView>> viewsOf = new IdentityHashMap<>();
    private int[] free = new int[64];
    private int freeCount, next;

    public VulkanBindless(VkDevice device) {
        this.device = device;
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT | VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutBindingFlagsCreateInfo flags = VkDescriptorSetLayoutBindingFlagsCreateInfo.calloc(stack)
                    .sType$Default().pBindingFlags(stack.ints(VK_DESCRIPTOR_BINDING_PARTIALLY_BOUND_BIT
                            | VK_DESCRIPTOR_BINDING_UPDATE_AFTER_BIND_BIT | VK_DESCRIPTOR_BINDING_UPDATE_UNUSED_WHILE_PENDING_BIT));
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .pNext(flags.address()).flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_UPDATE_AFTER_BIND_POOL_BIT)
                    .pBindings(bindings), null, lp), "vkCreateDescriptorSetLayout bindless");
            layout = new VulkanBindingLayout("bindless", List.of(
                    new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)), lp.get(0), false);

            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(SLOTS);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT).maxSets(1).pPoolSizes(sizes), null, lp),
                    "vkCreateDescriptorPool bindless");
            pool = lp.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(layout.setLayout)), lp), "vkAllocateDescriptorSets bindless");
            set = new VulkanBindingSet("bindless", layout, pool, lp.get(0));
        }
    }

    /**
     * The slot of {@code view} sampled by {@code sampler}, written on the first ask with {@code imageView} in
     * {@code imageLayout} and {@code vkSampler}.
     */
    public int texture(CgTextureView view, CgGpuSampler sampler, long imageView, int imageLayout, long vkSampler) {
        Map<CgGpuSampler, Integer> bySampler = slots.get(view);
        Integer slot = bySampler == null ? null : bySampler.get(sampler);
        if (slot != null) return slot;
        int s = freeCount > 0 ? free[--freeCount] : next++;
        if (s >= SLOTS) throw new IllegalStateException("the bindless heap's " + SLOTS + " slots are full");
        if (bySampler == null) {
            slots.put(view, bySampler = new IdentityHashMap<>(2));
            viewsOf.computeIfAbsent(view.texture(), k -> new ArrayList<>(1)).add(view);
        }
        bySampler.put(sampler, s);
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
            w.get(0).sType$Default().dstSet(set.set).dstBinding(0).dstArrayElement(s).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(vkSampler).imageView(imageView)
                            .imageLayout(imageLayout));
            vkUpdateDescriptorSets(device, w, null);
        }
        return s;
    }

    /** The slots of {@code texture}'s views back to the heap, as it is destroyed: every frame that read them has retired. */
    public void forget(CgGpuTexture texture) {
        List<CgTextureView> views = viewsOf.remove(texture);
        if (views == null) return;
        for (CgTextureView v : views) {
            for (int s : slots.remove(v).values()) {
                if (freeCount == free.length) free = Arrays.copyOf(free, free.length * 2);
                free[freeCount++] = s;
            }
        }
    }

    public void destroy() {
        vkDestroyDescriptorPool(device, pool, null);
        vkDestroyDescriptorSetLayout(device, layout.setLayout, null);
    }

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + ": " + result);
    }
}
