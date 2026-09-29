package com.crystalgraphics.vulkan;

import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Everything {@link CgVulkanDevice} takes from outside: a device and queue, the command buffer the current frame
 * records into, and the frames in flight. {@link OwnedVulkanHost} creates all of it for a window of its own;
 * a host inside Minecraft hands over Minecraft's (plan/device-vulkan.md §0).
 *
 * <pre>{@code
 * try (OwnedVulkanHost host = new OwnedVulkanHost(window, true)) {
 *     CgVulkanDevice device = new CgVulkanDevice(host);
 *     ... draw a frame through the device ...
 *     device.endFrame();          // the host submits and presents
 * }
 * }</pre>
 *
 * <p>Frames count from 0. A frame retires when the GPU has finished it, at most {@link #framesInFlight()} frames
 * behind the one being recorded. Owner thread only.</p>
 */
public interface CgVulkanHost {

    VkInstance instance();

    VkPhysicalDevice physicalDevice();

    VkDevice device();

    VkQueue queue();

    int queueFamily();

    /** The Vulkan version the device was created for, as {@code VK_MAKE_API_VERSION} packs it. */
    int apiVersion();

    int framesInFlight();

    /** Where the current frame's commands go. */
    VkCommandBuffer commandBuffer();

    /**
     * Commands that run before everything {@link #commandBuffer()} holds this frame, whatever is open in it: a new
     * image's first layout, which a render pass in progress could not take.
     */
    VkCommandBuffer setupCommandBuffer();

    /** The frame being recorded. */
    long frameIndex();

    /** The newest frame the GPU has finished; -1 before any. */
    long retiredFrame();

    /** Runs {@code action} once {@code frame} has retired — at once when it already has. */
    void whenFrameRetired(long frame, Runnable action);

    /**
     * Ends the frame. {@code output} is the frame's picture in {@code TRANSFER_SRC_OPTIMAL}, row 0 at GL's bottom;
     * a host that presents shows it the right way up. Owned, this submits and begins the next frame's command
     * buffer; hosted, the host does both.
     */
    void endFrame(CgVulkanImage output);

    boolean ownsSubmission();

    /**
     * Submits what the current frame has recorded, waits for it, and carries on recording the same frame: a
     * readback's cost.
     *
     * @throws IllegalStateException when the host submits, since waiting for it would deadlock
     */
    void submitAndWait();

    /**
     * Blocks until {@code frame}, one already ended, has retired.
     *
     * @throws IllegalStateException when the host submits
     */
    void waitRetired(long frame);

    /** How many validation messages at error severity the host has seen; 0 without validation. */
    int validationErrors();
}
