package com.crystalgraphics.platform.device;

import java.util.List;

/**
 * What the tracked backend drives: objects, the current frame's commands, and frames in flight. Implemented
 * over Vulkan by {@code CgVulkanDevice}, and by {@link CgRecordingDevice} for tests; {@code CgTrackedGLBackend}
 * is its only caller, so nothing above {@code CgGL} names it.
 *
 * <pre>{@code
 * CgGpuBuffer vertices = device.createBuffer(new CgGpuBuffer.Desc("quad", 96, CgGpuBuffer.Usage.ALL, true));
 * vertices.mapped().putFloat(0, -1f);
 *
 * CgRenderPass pass = device.encoder().beginPass(desc);
 * pass.setPipeline(pipeline);
 * pass.setVertexBuffer(0, vertices, 0);
 * pass.draw(6, 1, 0, 0);
 * pass.end();
 *
 * device.endFrame();              // submits; this frame's memory is reusable once it retires
 * device.release(vertices);       // destroyed once the frame current now has retired
 * }</pre>
 *
 * <p>Owner thread only. A released object may still be read by frames in flight, which is why it outlives the
 * call; using it after the release is a bug the device may not catch.</p>
 */
public interface CgDevice {

    CgDeviceInfo info();

    boolean supports(CgFormat format, CgGpuTexture.Usage usage);

    /** Whether the calling thread may record: the device's owner, hosted or owned. */
    boolean ownedByCurrentThread();

    // ── objects ───────────────────────────────────────────────────────────────

    CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc);

    CgGpuTexture createTexture(CgGpuTexture.Desc desc);

    CgGpuSampler createSampler(CgGpuSampler.Desc desc);

    /** @throws CgShaderModule.CompileException with the compiler's log */
    CgShaderModule createShaderModule(CgShaderModule.Stage stage, String source, String label);

    CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots);

    CgPipeline createPipeline(CgPipelineDesc desc);

    CgTimerQuery createTimerQuery(String label);

    /** Destroys {@code object} once the frame current now has retired. */
    void release(CgDeviceObject object);

    // ── commands ──────────────────────────────────────────────────────────────

    /** The current frame's encoder; the same object until {@link #endFrame}. */
    CgCommandEncoder encoder();

    /** The default framebuffer's colour image, this frame. A resize may hand out a new one. */
    CgGpuTexture surfaceColor();

    /** The default framebuffer's depth-stencil image, or {@code null} when it has none. */
    CgGpuTexture surfaceDepth();

    // ── frames ────────────────────────────────────────────────────────────────

    /** The frame being recorded. */
    long frameIndex();

    /** The newest frame the GPU has finished; -1 before any. */
    long retiredFrame();

    /** Runs {@code action} once {@code frame} has retired — at once when it already has. */
    void whenRetired(long frame, Runnable action);

    /**
     * Ends the frame: every pass must be ended. Owned, it submits; hosted, the host does.
     *
     * @throws IllegalStateException with a pass open
     */
    void endFrame();

    /** Whether this device submits its own frames. A hosted device's host does. */
    boolean ownsSubmission();

    /**
     * Blocks until {@code frame} has retired, submitting it first if it is the current one.
     *
     * @throws IllegalStateException when the device does not own submission: the host submits, and waiting
     *         for it would deadlock
     */
    void waitRetired(long frame);
}
