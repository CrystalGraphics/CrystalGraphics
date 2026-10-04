package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.platform.device.resource.CgTimerQuery;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import org.lwjgl.system.MemoryStack;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Two timestamps in a pool of its own, reset from the host before each use; read once its frame retires. A timer's
 * result is their difference; a single timestamp's ({@code stamp}) the clock it wrote.
 */
public final class VulkanTimerQuery implements CgTimerQuery {

    public final long pool;
    private final CgVulkanDevice device;
    private final String label;
    /** The frame the end timestamp was written in; -1 before any. */
    public long frame = -1;
    /** Whether its last use was a single timestamp (its result the clock) rather than a timer (a difference). */
    public boolean stamp;

    public VulkanTimerQuery(CgVulkanDevice device, long pool, String label) {
        this.device = device;
        this.pool = pool;
        this.label = label;
    }

    @Override public String label() { return label; }

    @Override
    public long resultNanos() {
        if (frame < 0 || frame > device.retiredFrame()) return -1;
        try (MemoryStack stack = stackPush()) {
            if (stamp) {
                LongBuffer tick = stack.mallocLong(1);
                int result = vkGetQueryPoolResults(device.vk(), pool, 1, 1, tick, 8, VK_QUERY_RESULT_64_BIT);
                // In double: the raw clock is large enough that a float product loses milliseconds.
                return result == VK_SUCCESS ? Math.round(tick.get(0) * (double) device.timestampPeriod()) : -1;
            }
            LongBuffer ticks = stack.mallocLong(2);
            int result = vkGetQueryPoolResults(device.vk(), pool, 0, 2, ticks, 8, VK_QUERY_RESULT_64_BIT);
            if (result != VK_SUCCESS) return -1;
            return Math.round((ticks.get(1) - ticks.get(0)) * device.timestampPeriod());
        }
    }
}
