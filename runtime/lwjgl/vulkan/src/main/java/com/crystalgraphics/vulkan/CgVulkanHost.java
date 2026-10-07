package com.crystalgraphics.vulkan;

import com.crystalgraphics.vulkan.host.HostedVulkanHost;
import com.crystalgraphics.vulkan.host.OwnedVulkanHost;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Everything {@link CgVulkanDevice} takes from outside: a device and queue, the command buffer the current frame
 * records into, and the frames in flight. {@link OwnedVulkanHost} creates all of it for a window of its own;
 * a {@link HostedVulkanHost} hands over a game's, which also submits (plan/device-vulkan.md §0).
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
     * buffer; hosted, the host does both, and {@code output} is null: the host presents its own picture.
     */
    void endFrame(CgVulkanImage output);

    boolean ownsSubmission();

    /**
     * The host hands us its frame: commands recorded from here go to {@link #commandBuffer()}. Nothing to do for a
     * host that owns its frames.
     */
    default void fromHost() {}

    /**
     * We hand the frame back with no pass of ours open: a host that submits takes what was recorded since
     * {@link #fromHost()} into its own command stream, in order, and records its own after it.
     */
    default void toHost() {}

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

    /**
     * Whether the device enabled {@code VK_EXT_line_rasterization}'s {@code bresenhamLines}: GL's rule for which
     * pixels a line covers. Without it a line on an exact pixel boundary can vanish where GL draws it.
     */
    boolean bresenhamLines();

    /** Whether the device was created with {@code multiDrawIndirect}: an indirect draw of more than one command. */
    boolean multiDrawIndirect();

    /** Whether the device was created with {@code drawIndirectCount}: a draw's count may come from a buffer. */
    boolean indirectCount();

    /** Whether the device was created with {@code drawIndirectFirstInstance}. */
    boolean indirectFirstInstance();

    /** Whether the device was created with {@code shaderDrawParameters}: a draw's bases and {@code gl_DrawID} in a shader. */
    boolean drawParameters();

    /** Whether the device was created with {@code independentBlend}: attachments of one pipeline may differ in write mask. */
    boolean independentBlend();

    /** Whether {@link #beginAsync} records onto a compute queue of its own, overlapping the frame's other work. */
    boolean asyncCompute();

    /**
     * The queue family async work runs on when it is not {@link #queueFamily()}, else -1: buffers and images are then
     * shared by both, so neither queue's use of them needs an ownership transfer.
     */
    int asyncFamily();

    /**
     * {@link #commandBuffer()} becomes the compute queue's, its work starting after everything recorded before it, until
     * {@link #endAsync}. A host without one keeps recording in order.
     */
    void beginAsync();

    /** Back to the frame's queue: the point {@link #waitAsync} waits for, 0 for a host that recorded it in order. */
    long endAsync();

    /** What is recorded from here runs after the async work up to {@code point}. */
    void waitAsync(long point);

    /**
     * Whether copies into an image no frame has used yet may run on a transfer queue of their own
     * ({@link #transferCommandBuffer}), beside the frame's queue.
     */
    boolean asyncTransfer();

    /** That queue's family, else -1: buffers and images are then shared with it too. */
    int transferFamily();

    /** Where copies for the transfer queue are recorded, begun when first asked for since the last {@link #submitTransfers}. */
    VkCommandBuffer transferCommandBuffer();

    /**
     * Submits what was recorded into {@link #transferCommandBuffer} since the last call, at once: the point
     * {@link #waitTransfers} waits for, the last one again when nothing was recorded.
     */
    long submitTransfers();

    /** What is recorded from here runs after the transfers up to {@code point}. Outside a pass. */
    void waitTransfers(long point);
}
