package com.crystalgraphics.vulkan.sync;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Where {@link VulkanTimerQuery}s take their two timestamps: pools of {@link #QUERIES}, a pair a timer, given back when the
 * timer is destroyed (as Unreal's Vulkan RHI keeps large query pools rather than one a query). Any thread.
 */
public final class VulkanQueryPools {

    static final int QUERIES = 256;

    private final VkDevice device;
    private final List<Long> pools = new ArrayList<>();
    /** Free pairs: a pool and the first of its two queries. */
    private final ArrayDeque<long[]> free = new ArrayDeque<>();

    public VulkanQueryPools(VkDevice device) {
        this.device = device;
    }

    /** A pair of timestamp queries: its pool, then the first of the two. */
    public synchronized long[] take() {
        if (free.isEmpty()) grow();
        return free.pollFirst();
    }

    public synchronized void give(long pool, int first) {
        free.addLast(new long[]{pool, first});
    }

    public synchronized void destroy() {
        for (long pool : pools) vkDestroyQueryPool(device, pool, null);
        pools.clear();
        free.clear();
    }

    private void grow() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            int r = vkCreateQueryPool(device, VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP).queryCount(QUERIES), null, lp);
            if (r != VK_SUCCESS) throw new IllegalStateException("vkCreateQueryPool: " + r);
            pools.add(lp.get(0));
            for (int first = 0; first < QUERIES; first += 2) free.addLast(new long[]{lp.get(0), first});
        }
    }
}
