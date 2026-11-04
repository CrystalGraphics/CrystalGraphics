package com.crystalgraphics.platform.rhi;

import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.variable.CgVar;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BiConsumer;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;

/**
 * The device API the engine draws and dispatches through (Unreal's {@code FDynamicRHI}): resources, pipelines, the
 * frame's command list and frames in flight. One implementation runs at a time, reached through {@link #get()}:
 * Vulkan's ({@code CgVulkanDevice}), and {@link CgNullRHI}, which records and validates, for tests and headless runs.
 *
 * <pre>{@code
 * CgRHI rhi = CgRHI.get();
 * CgGpuBuffer vertices = rhi.createBuffer(new CgGpuBuffer.Desc("quad", 96, CgGpuBuffer.Usage.ALL, true));
 * vertices.mapped().putFloat(0, -1f);                 // host-visible: written in place, no upload call
 *
 * CgRenderPass pass = rhi.encoder().beginPass(desc);  // targets and their load and store actions
 * pass.setPipeline(pipeline);
 * pass.setVertexBuffer(0, vertices, 0);
 * pass.draw(6, 1, 0, 0);
 * pass.end();
 *
 * rhi.endFrame();                                     // owned: submits; hosted: the host does
 * rhi.release(vertices);                              // destroyed once the frame current now has retired
 * }</pre>
 *
 * <p>Whether something is there to use: {@link #globals()} and {@link #supports}.</p>
 *
 * <pre>{@code
 * if (rhi.globals().indirectCount()) pass.drawIndexedIndirectCount(...);
 * if (rhi.supports(CgFormat.RGBA16F, CgGpuTexture.Usage.STORAGE)) ...
 * }</pre>
 *
 * <ul>
 *   <li>Record on the owner thread only ({@link #ownedByCurrentThread()}); creation may run on any thread where
 *   {@link #createsOnAnyThread()} says so.</li>
 *   <li>Every pass ends before {@link #endFrame()}, which throws otherwise.</li>
 *   <li>A released object may still be read by frames in flight; using it after the release is a bug the device
 *   may not catch.</li>
 *   <li>{@link #get()} throws until a host has called {@link #install}.</li>
 * </ul>
 */
public interface CgRHI {

    /** How a Vulkan device runs async() compute, read when its host makes its queues. */
    CgVar.Text ASYNC_COMPUTE = CgVar.text("crystalgraphics.vulkan.asyncCompute", "", CgVar.DEV | CgVar.RESTART,
            "Empty: async() compute passes on a compute-only queue family where there is one (an owned device), in order"
                    + " on a hosted one. false: in order everywhere. graphics: a second queue of the frame's family. true:"
                    + " a hosted device (Minecraft) runs async on its host's compute queue, waiting only on the host's"
                    + " end-of-frame submit, which serialised the GPU.");
    CgVar.Bool TRANSFER = CgVar.bool("crystalgraphics.vulkan.transfer", true, CgVar.DEV | CgVar.RESTART,
            "New textures' copies and the mesh store's run on a transfer queue where there is one; false keeps them on the"
                    + " frame's queue (ENGINE_API.md, Async).");
    CgVar.Bool SYNC_VALIDATION = CgVar.bool("crystalgraphics.vulkan.syncValidation", false, CgVar.DEV | CgVar.RESTART,
            "With the validation layer on, its synchronization checks too.");
    CgVar.Text PRESENT_MODE = CgVar.text("crystalgraphics.vulkan.presentMode", "", CgVar.DEV | CgVar.RESTART,
            "immediate, mailbox or fifo: forces one the surface offers (an owned device); empty lets the host choose.");

    /** The RHI installed now (Unreal's {@code GDynamicRHI}). */
    static CgRHI get() {
        CgRHI rhi = Current.rhi;
        if (rhi == null) throw new IllegalStateException("no CgRHI installed: a host installs one as it starts");
        return rhi;
    }

    /** Makes {@code rhi} the one {@link #get()} returns; the host that made it calls this, null when it goes. */
    static void install(CgRHI rhi) {
        Current.rhi = rhi;
    }

    /** What the device is and what it can do. */
    Globals globals();

    /**
     * What the device can do beyond {@link #globals()}, as {@code CgGpuReport} facts under its keys: features
     * ({@code compute}, {@code subgroups}, {@code indirectCount}), limits ({@code computeInvocations}) and queues.
     */
    void describe(BiConsumer<String, String> fact);

    boolean supports(CgFormat format, CgGpuTexture.Usage usage);

    /** Whether the calling thread may record: the device's owner, hosted or owned. */
    boolean ownedByCurrentThread();

    // ── objects ───────────────────────────────────────────────────────────────

    CgGpuBuffer createBuffer(CgGpuBuffer.Desc desc);

    CgGpuTexture createTexture(CgGpuTexture.Desc desc);

    CgGpuSampler createSampler(CgGpuSampler.Desc desc);

    /** One stage's SPIR-V, from a {@link CgGlslCompiler}. */
    CgShaderModule createShaderModule(CgShaderModule.Stage stage, ByteBuffer spirv, String label);

    CgBindingLayout createBindingLayout(String label, List<CgBindingLayout.Slot> slots);

    CgGraphicsPipeline createPipeline(CgGraphicsPipelineDesc desc);

    CgComputePipeline createComputePipeline(String label, CgShaderModule module, CgBindingLayout layout);

    CgTimerQuery createTimerQuery(String label);

    /** Destroys {@code object} once the frame current now has retired. */
    void release(CgRHIResource object);

    // ── commands ──────────────────────────────────────────────────────────────

    /** The current frame's encoder; the same object until {@link #endFrame}. */
    CgCommandList encoder();

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
     * Whether binding layouts, shader modules and compute pipelines may be created on a thread other than the owner's,
     * as Vulkan allows: the tracked backend then builds a kernel's on its compile worker, off the frame. False unless
     * the device says so.
     */
    default boolean createsOnAnyThread() {
        return false;
    }

    /**
     * The host hands us its frame, as a host section opens. Nothing to do for a device that owns
     * its frames.
     */
    default void fromHost() {}

    /**
     * We hand the frame back, as the host section closes, with no pass open: a hosted device gives the
     * host what it recorded since {@link #fromHost()}, in the order recorded.
     */
    default void toHost() {}

    /**
     * Blocks until {@code frame} has retired, submitting it first if it is the current one.
     *
     * @throws IllegalStateException when the device does not own submission: the host submits, and waiting
     *         for it would deadlock
     */
    void waitRetired(long frame);

    /**
     * What the device is and what it can do (Unreal's {@code FRHIGlobals}): read through {@link #globals()}.
     *
     * @param timestamps            timer queries return results
     * @param nonSolidFill          a polygon mode of lines or points
     * @param multiDrawIndirect     an indirect draw may take more than one command
     * @param indirectCount         a draw's count may come from a buffer
     * @param indirectFirstInstance an indirect command's first instance may be other than 0
     * @param asyncCompute          a compute queue beside the frame's, so async work overlaps the frame's other work
     * @param drawParameters        {@code gl_DrawID} and a draw's base vertex and instance in a shader
     * @param independentBlend      a pipeline's colour attachments may differ in blend and write mask
     */
    record Globals(String name, String vendor, String driver, Limits limits,
                   boolean timestamps, boolean anisotropy, boolean nonSolidFill,
                   boolean multiDrawIndirect, boolean indirectCount, boolean indirectFirstInstance,
                   boolean asyncCompute, boolean drawParameters, boolean independentBlend) {

        public record Limits(int maxTextureSize, int max3DTextureSize, int maxArrayLayers, int maxColorAttachments,
                             int maxSamples, int maxVertexAttributes, int maxTextureUnits, int maxUniformBlockSize,
                             int maxUniformBufferBindings, int maxStorageBufferBindings, int maxTexelBufferElements,
                             int uniformOffsetAlignment, int storageOffsetAlignment, int texelOffsetAlignment,
                             int maxViewportSize, float maxAnisotropy, Compute compute) {}

        /**
         * What a kernel may ask for: shared memory in bytes, invocations in a work group, the work group's size and a
         * dispatch's group count per axis, and the subgroup operations a compute stage has.
         *
         * @param subgroupOperations {@code VkSubgroupFeatureFlags} for the compute stage; 0 where compute has
         *                           none
         */
        public record Compute(int sharedMemory, int invocations, int sizeX, int sizeY, int sizeZ, int countX, int countY,
                              int countZ, int subgroupSize, int subgroupOperations) {

            /** Vulkan's guaranteed minimums. */
            public static final Compute MINIMUM = new Compute(16384, 128, 128, 128, 64, 65535, 65535, 65535, 1, 0x1);
        }
    }

    /** Where {@link #get()} reads from; written by {@link #install} alone. */
    final class Current {
        static volatile CgRHI rhi;

        private Current() {}
    }
}
