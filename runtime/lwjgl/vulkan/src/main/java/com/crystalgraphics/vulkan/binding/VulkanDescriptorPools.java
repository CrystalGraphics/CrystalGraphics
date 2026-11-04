package com.crystalgraphics.vulkan.binding;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_ERROR_OUT_OF_POOL_MEMORY;

/**
 * Where {@link VulkanBindingSet}s come from: pools of {@link #SETS} sets each, a new one made when every pool is full.
 * A set goes back to its pool when it is destroyed, once its last frame has retired. Any thread.
 */
public final class VulkanDescriptorPools {

    /** Sets a pool holds, and descriptors of each type: enough that a scene's materials fill a few. */
    static final int SETS = 256;
    private static final int[] TYPES = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
            VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};

    private final VkDevice device;
    private final List<Long> pools = new ArrayList<>();

    public VulkanDescriptorPools(VkDevice device) {
        this.device = device;
    }

    /** A set of {@code setLayout}: its pool, then the set. */
    public synchronized long[] allocate(long setLayout, String label) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer set = stack.mallocLong(1);
            for (int i = pools.size() - 1; i >= 0; i--) {
                VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pools.get(i)).pSetLayouts(stack.longs(setLayout));
                int r = vkAllocateDescriptorSets(device, ai, set);
                if (r == VK_SUCCESS) return new long[]{pools.get(i), set.get(0)};
                if (r != VK_ERROR_OUT_OF_POOL_MEMORY && r != VK_ERROR_FRAGMENTED_POOL)
                    throw new IllegalStateException("vkAllocateDescriptorSets " + label + ": " + r);
            }
            long pool = create(stack);
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(setLayout));
            int r = vkAllocateDescriptorSets(device, ai, set);
            if (r != VK_SUCCESS) throw new IllegalStateException("vkAllocateDescriptorSets " + label + ": " + r);
            return new long[]{pool, set.get(0)};
        }
    }

    public synchronized void free(long pool, long set) {
        try (MemoryStack stack = stackPush()) {
            vkFreeDescriptorSets(device, pool, stack.longs(set));
        }
    }

    public synchronized void destroy() {
        for (long pool : pools) vkDestroyDescriptorPool(device, pool, null);
        pools.clear();
    }

    private long create(MemoryStack stack) {
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(TYPES.length, stack);
        for (int i = 0; i < TYPES.length; i++) sizes.get(i).type(TYPES[i]).descriptorCount(SETS * 4);
        LongBuffer lp = stack.mallocLong(1);
        int r = vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT).maxSets(SETS).pPoolSizes(sizes), null, lp);
        if (r != VK_SUCCESS) throw new IllegalStateException("vkCreateDescriptorPool: " + r);
        pools.add(lp.get(0));
        return lp.get(0);
    }
}
