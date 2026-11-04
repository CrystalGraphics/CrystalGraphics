package com.crystalgraphics.vulkan.sync;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkEventCreateInfo;

import java.nio.LongBuffer;
import java.util.Arrays;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkCreateEvent;
import static org.lwjgl.vulkan.VK10.vkDestroyEvent;
import static org.lwjgl.vulkan.VK10.vkResetEvent;

/**
 * The events split transitions set and wait on, made as needed and reused once the frame that last used one retires,
 * reset from the host as they are taken again.
 *
 * <pre>{@code
 * long event = events.take(device.frameIndex(), device.retiredFrame());
 * batch.set(cmd, event);       // the writer is done
 * ...
 * batch.waitFor(cmd, event);   // the reader may start
 * }</pre>
 */
public final class VulkanEvents {

    private final VkDevice device;
    private long[] events = new long[16];
    /** Per event, the frame that last used it: free once the device has retired that frame. */
    private long[] usedIn = new long[16];
    private int count;

    public VulkanEvents(VkDevice device) {
        this.device = device;
    }

    /** An event no frame in flight uses, marked as {@code frame}'s. */
    public long take(long frame, long retired) {
        for (int i = 0; i < count; i++) {
            if (usedIn[i] <= retired) {
                usedIn[i] = frame;
                vkResetEvent(device, events[i]);   // set by the frame that last used it, which has retired
                return events[i];
            }
        }
        if (count == events.length) {
            events = Arrays.copyOf(events, count * 2);
            usedIn = Arrays.copyOf(usedIn, count * 2);
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateEvent(device, VkEventCreateInfo.calloc(stack).sType$Default(), null, lp), "vkCreateEvent");
            events[count] = lp.get(0);
        }
        usedIn[count] = frame;
        return events[count++];
    }

    public void destroy() {
        for (int i = 0; i < count; i++) vkDestroyEvent(device, events[i], null);
        count = 0;
    }
}
