package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.device.CgTimerQuery;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Two timestamps in a pool of its own, reset from the host before each use; read once its frame retires. */
final class VulkanTimerQuery implements CgTimerQuery {

    final long pool;
    private final CgVulkanDevice device;
    private final String label;
    /** The frame the end timestamp was written in; -1 before any. */
    long frame = -1;

    VulkanTimerQuery(CgVulkanDevice device, long pool, String label) {
        this.device = device;
        this.pool = pool;
        this.label = label;
    }

    @Override public String label() { return label; }

    @Override
    public long resultNanos() {
        if (frame < 0 || frame > device.retiredFrame()) return -1;
        try (MemoryStack stack = stackPush()) {
            LongBuffer ticks = stack.mallocLong(2);
            int result = vkGetQueryPoolResults(device.vk(), pool, 0, 2, ticks, 8, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) return -1;
            return Math.round((ticks.get(1) - ticks.get(0)) * device.timestampPeriod());
        }
    }
}
