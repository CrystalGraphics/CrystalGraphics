package com.crystalgraphics.vulkan.host;

import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.CgVulkanHost;
import com.crystalgraphics.vulkan.CgVulkanImage;
import com.crystalgraphics.vulkan.command.VulkanComputeCommandBuffer;
import com.crystalgraphics.vulkan.command.VulkanEncoder;
import com.crystalgraphics.vulkan.command.VulkanTransferCommandBuffer;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreSignalInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkTimelineSemaphoreSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue;
import static org.lwjgl.vulkan.VK12.vkSignalSemaphore;

/**
 * A Vulkan device someone else owns and submits -- a game's -- as a {@link CgVulkanHost}: CrystalGraphics draws
 * into the host's frame, on its queue and into its images, and the host's own submit carries our commands. A
 * subclass answers what only the host knows: its device, how it hands out and runs a command buffer, when a
 * submit has completed, and what one of its textures is. {@link OwnedVulkanHost} is the other kind, a device of
 * our own.
 *
 * <pre>{@code
 * final class GameVulkanHost extends HostedVulkanHost<GameTexture> {
 *     GameVulkanHost(GameDevice game) {
 *         super("the game", Boolean.getBoolean("crystalgraphics.host.verify"));
 *         this.game = game;
 *     }
 *
 *     @Override protected VkCommandBuffer beginCommandBuffer() { return game.beginTransientCommandBuffer(); }
 *     @Override protected void barrier(VkCommandBuffer cmd)    { game.fullBarrier(cmd); }
 *     @Override protected void execute(VkCommandBuffer cmd)    { game.executeInNextSubmit(cmd); }
 *     @Override protected void afterSubmit(Runnable action)    { game.whenSubmitCompletes(action); }
 *     @Override protected CgGpuTexture wrap(GameTexture t) {
 *         return cgDevice().wrap(t.vkImage(), t.vkFormat(), t.desc(), VK_IMAGE_LAYOUT_GENERAL);   // where it rests
 *     }
 *     @Override protected boolean isClosed(GameTexture t)      { return t.isClosed(); }
 *
 *     // Async compute: a compute queue the game made and never submits to, or null to run it in order
 *     @Override protected VkQueue computeQueue()                { return game.idleComputeQueue(); }
 *     @Override protected int computeQueueFamily()              { return game.idleComputeFamily(); }
 *     @Override protected void waitInSubmit(long s, long v)     { game.nextSubmitWaits(s, v); }
 *     @Override protected void signalInSubmit(long s, long v)   { game.nextSubmitSignals(s, v); }
 *     // Copies into new images: a transfer queue the game made and never submits to, or null to copy in order
 *     @Override protected VkQueue transferQueue()               { return game.idleTransferQueue(); }
 *     @Override protected int transferQueueFamily()             { return game.idleTransferFamily(); }
 *     // ... and CgVulkanHost's device facts: instance(), device(), queue(), apiVersion(), framesInFlight(), ...
 * }
 *
 * CgGLBackend gl = host.openBackend(width, height);   // a device over the host, and GL's semantics over that
 * int color = host.importTexture(gameColorTexture);   // a name CgGL can attach
 * host.endHostFrame();                                // once per host frame, before the host submits it
 * host.closeAfterLastSubmit();                        // at the host's shutdown signal
 * }</pre>
 *
 * <ul>
 *   <li>Each host section ({@code CgGL.fromHost()} to {@code toHost()}) records into command buffers of its own
 *       from {@link #beginCommandBuffer()}, and {@code toHost()} hands them to {@link #execute}, the setup buffer
 *       first. Nothing records into the command buffer the host records into.</li>
 *   <li>A frame is one {@link #endHostFrame()}. It retires when {@link #afterSubmit} runs the action given it
 *       then, so that action must run only once the submit carrying the frame has completed.</li>
 *   <li>A wrapped image rests in the layout {@link #wrap} gives it, and every pass and transfer of ours returns
 *       it there. {@link #isClosed} lets the name go once the host has deleted the texture.</li>
 *   <li>{@code CgGL} used outside a host section is recorded for the next hand-over and warned about once. It
 *       still has to reach the host before its next submit, so bracket it.</li>
 *   <li>{@code verify} checks every hand-over: no pass of ours open, every wrapped image back where it rests.</li>
 *   <li>{@link #submitAndWait()} and {@link #waitRetired} throw: the host submits, so a wait would deadlock.
 *       The host's render thread only.</li>
 *   <li>Async compute runs in order unless {@code -Dcrystalgraphics.vulkan.asyncCompute=true}: it can wait only on the
 *       host's submit, so it overlaps nothing. With it, async work runs on {@link #computeQueue()}, a queue the host
 *       created and never submits to, submitted at {@code endAsync} and waiting on a timeline value the host's own
 *       submit signals later ({@link #signalInSubmit}); what waits for it waits inside that submit
 *       ({@link #waitInSubmit}). An async pass may not touch the host's images, which only the host's family may
 *       use.</li>
 *   <li>Copies into images only the transfer queue has used run on {@link #transferQueue()} where it copies a texel at
 *       a time, else in order. Each batch is submitted when the device asks, and the host's submit waits for it.</li>
 * </ul>
 *
 * @param <T> the host's texture type, held by identity
 */
public abstract class HostedVulkanHost<T> implements CgVulkanHost {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");

    private final String hostName;
    private final boolean verify;
    private CgVulkanDevice device;
    private CgTrackedGLBackend gl;
    private boolean closing;

    // This hand-over's commands, begun when first asked for.
    private VkCommandBuffer setup;
    private VkCommandBuffer commands;
    private boolean inSection;
    private boolean warnedOutside;
    private final Set<String> reported = new HashSet<>();

    // Our frame ends, and the newest whose submit has completed.
    private long frame;
    private long retired = -1;
    private final ArrayDeque<Retirement> retirements = new ArrayDeque<>();

    private record Retirement(long frame, Runnable action) {}

    // The host's textures we have wrapped, by the texture the host holds.
    private final Map<T, Imported> imported = new IdentityHashMap<>();

    private record Imported(CgGpuTexture wrapped, int name) {}

    // Async compute: null queue where the host has none to spare, or it is turned off.
    private VkQueue asyncQueue;
    private int asyncQueueFamily = -1;
    private long mainTimeline, asyncTimeline;
    private long mainValue, asyncValue, asyncWaited, asyncWaitMain;
    private VkCommandBuffer asyncCommands;
    private boolean recordingAsync, queuesClosed;
    private FramePool framePool;
    private final ArrayDeque<FramePool> freePools = new ArrayDeque<>();

    // Copies on the host's transfer queue: null where it has none to spare, or it is turned off.
    private VkQueue transferQueue;
    private int transferQueueFamily = -1;
    private long transferTimeline, transferValue, transferWaited;
    private VkCommandBuffer transferCommands;
    private FramePool transferFramePool;
    private final ArrayDeque<FramePool> freeTransferPools = new ArrayDeque<>();
    private final List<FramePool> pools = new ArrayList<>();

    /** A command pool on async compute's or the transfer queue's family, used by one frame and reset once it retires. */
    private final class FramePool {
        final long pool;
        final boolean transfer;
        final List<VkCommandBuffer> buffers = new ArrayList<>();
        int used;

        FramePool(long pool, boolean transfer) {
            this.pool = pool;
            this.transfer = transfer;
        }

        VkCommandBuffer next() {
            if (used == buffers.size()) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    PointerBuffer pp = stack.mallocPointer(1);
                    check(vkAllocateCommandBuffers(device(), VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                            .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pp),
                            "vkAllocateCommandBuffers");
                    buffers.add(transfer ? new VulkanTransferCommandBuffer(pp.get(0), device())
                            : asyncQueueFamily != queueFamily() ? new VulkanComputeCommandBuffer(pp.get(0), device())
                            : new VkCommandBuffer(pp.get(0), device()));
                }
            }
            VkCommandBuffer cmd = buffers.get(used++);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
            }
            return cmd;
        }

        void reset() {
            if (queuesClosed) return;
            check(vkResetCommandPool(device(), pool, 0), "vkResetCommandPool");
            used = 0;
            (transfer ? freeTransferPools : freePools).add(this);
        }
    }

    /**
     * @param hostName names the host in warnings, "Minecraft"
     * @param verify   check every hand-over; {@code -Dcrystalgraphics.host.verify=true} by convention
     */
    protected HostedVulkanHost(String hostName, boolean verify) {
        this.hostName = hostName;
        this.verify = verify;
        if (verify) LOG.info("[cg-host-verify] ARMED for Vulkan -- every hand-over is checked for an open pass "
                + "and for {}'s images away from where they rest. A line per fault follows, or silence.", hostName);
    }

    // ── What the host answers ────────────────────────────────────────────────────────────────────

    /** A primary command buffer from the host's pool for the submit being recorded, already begun. */
    protected abstract VkCommandBuffer beginCommandBuffer();

    /** Records the host's own full memory barrier into {@code cmd}. */
    protected abstract void barrier(VkCommandBuffer cmd);

    /** Hands an ended command buffer to the host's next submit, after everything the host has recorded so far. */
    protected abstract void execute(VkCommandBuffer cmd);

    /** Runs {@code action} once the submit being recorded has completed on the GPU, or as the host's device closes. */
    protected abstract void afterSubmit(Runnable action);

    /** The host's texture as one of the device's, over the host's image; see {@link CgVulkanDevice#wrap}. */
    protected abstract CgGpuTexture wrap(T texture);

    /** Whether the host has deleted {@code texture}. */
    protected abstract boolean isClosed(T texture);

    /**
     * A queue the host created that runs compute and that nothing of the host's ever submits to, or null. It may
     * be another family's or a second queue of {@link #queueFamily()}. Two threads submitting to one queue at once
     * is undefined, so answer null for any host not checked to leave it alone.
     */
    protected abstract VkQueue computeQueue();

    /** {@link #computeQueue()}'s family. */
    protected abstract int computeQueueFamily();

    /** Adds to the submit being recorded a wait for {@code semaphore} to reach {@code value}, before what follows. */
    protected abstract void waitInSubmit(long semaphore, long value);

    /** Adds to the submit being recorded a signal of {@code value}, after everything handed over so far. */
    protected abstract void signalInSubmit(long semaphore, long value);

    /**
     * A queue the host created that copies and that nothing of the host's ever submits to, or null: copies into new
     * images run there. Answer null for any host not checked to leave it alone, as for {@link #computeQueue()}.
     */
    protected abstract VkQueue transferQueue();

    /** {@link #transferQueue()}'s family. */
    protected abstract int transferQueueFamily();

    // ── Use ──────────────────────────────────────────────────────────────────────────────────────

    /** Builds the device over this host and GL's semantics over that, and makes it CgGL's state provider. Once. */
    public final CgTrackedGLBackend openBackend(int width, int height) {
        if (device != null) throw new IllegalStateException("openBackend already ran on this host");
        // Opt-in: the host's once-a-frame submit is the only signal async work can wait on, so it starts after the
        // whole frame and the next submit waits for it -- serial, and in Minecraft 5 ms a frame slower than in order.
        if ("true".equals(System.getProperty("crystalgraphics.vulkan.asyncCompute"))) {
            asyncQueue = computeQueue();
            asyncQueueFamily = asyncQueue == null ? -1 : computeQueueFamily();
        }
        if (asyncQueue != null) {
            mainTimeline = timeline();
            asyncTimeline = timeline();
            LOG.info("[cg] async compute runs on {}'s compute queue, family {}", hostName, asyncQueueFamily);
        }
        if (!"false".equals(System.getProperty("crystalgraphics.vulkan.transfer"))) {
            VkQueue q = transferQueue();
            if (q != null && texelGranular(transferQueueFamily())) {
                transferQueue = q;
                transferQueueFamily = transferQueueFamily();
            }
        }
        if (transferQueue != null) {
            transferTimeline = timeline();
            LOG.info("[cg] copies into new images run on {}'s transfer queue, family {}", hostName, transferQueueFamily);
        }
        device = new CgVulkanDevice(this, width, height, CgCacheDirectory.of("vulkan"));
        gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(CgCacheDirectory.of("spirv")), verify)
                .compileInBackground();
        CgGlState.setProvider(new CgTrackedStateProvider(gl));
        return gl;
    }

    /** Whether {@code family} copies any region of an image: a granularity of one texel. */
    private boolean texelGranular(int family) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice(), n, null);
            VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice(), n, families);
            VkExtent3D g = families.get(family).minImageTransferGranularity();
            boolean texel = g.width() == 1 && g.height() == 1 && g.depth() == 1;
            if (!texel) {
                LOG.info("[cg] {}'s transfer family {} copies at {}x{}x{}: copies stay on its graphics queue", hostName,
                        family, g.width(), g.height(), g.depth());
            }
            return texel;
        }
    }

    /** The device {@link #openBackend} built, or null before it. */
    protected final CgVulkanDevice cgDevice() {
        return device;
    }

    /** Ends our frame inside the host's: after everything we draw in it, before the host submits it. */
    public final void endHostFrame() {
        if (gl == null || closing) return;
        CgGL.fromHost();
        try {
            gl.endFrame();
        } finally {
            CgGL.toHost();
        }
    }

    /**
     * The host is closing: our device closes once the submit holding our last commands has completed. At the
     * signal itself a submit may still carry them. Nothing of ours records after.
     */
    public final void closeAfterLastSubmit() {
        if (device == null || closing) return;
        closing = true;
        afterSubmit(() -> {
            closeQueues();
            device.close();
        });
    }

    /** The host's texture as a GL name the engine can attach, the same name for as long as the texture lives. */
    public final int importTexture(T texture) {
        forgetClosed();
        Imported known = imported.get(texture);
        if (known != null) return known.name();
        CgGpuTexture wrapped = wrap(texture);
        int name = CgGL.importHostTexture(wrapped);
        imported.put(texture, new Imported(wrapped, name));
        return name;
    }

    /** The default framebuffer at the host's target size, as GL's is the window's. */
    public final void matchSurface(int width, int height) {
        device.resize(width, height);
    }

    // A resize closes the host's old target; our names and views over it go with it.
    private void forgetClosed() {
        Iterator<Map.Entry<T, Imported>> it = imported.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<T, Imported> e = it.next();
            if (!isClosed(e.getKey())) continue;
            CgGL.glDeleteTextures(e.getValue().name());
            device.release(e.getValue().wrapped());
            it.remove();
        }
    }

    // ── CgVulkanHost ─────────────────────────────────────────────────────────────────────────────

    @Override
    public final VkCommandBuffer commandBuffer() {
        if (recordingAsync) return asyncCommands;
        if (commands == null) {
            noteOutsideSection();
            commands = beginCommandBuffer();
            // A host may end each of its passes with this barrier; ours opens with it too, so nothing we read
            // depends on that habit.
            barrier(commands);
        }
        return commands;
    }

    @Override
    public final VkCommandBuffer setupCommandBuffer() {
        if (setup == null) {
            noteOutsideSection();
            setup = beginCommandBuffer();
        }
        return setup;
    }

    // Not while openBackend() builds the device: its first images are recorded before any section, and the first
    // hand-over of that same frame carries them.
    private void noteOutsideSection() {
        if (inSection || warnedOutside || gl == null) return;
        warnedOutside = true;
        LOG.warn("[cg] CgGL used outside a host section on " + hostName + "'s Vulkan device: it reaches " + hostName
                + " at the next hand-over. Bracket it with CgGL.fromHost() and CgGL.toHost().", new Throwable("here"));
    }

    @Override public final long frameIndex() { return frame; }

    @Override
    public final long retiredFrame() {
        runRetired();
        return retired;
    }

    @Override
    public final void whenFrameRetired(long f, Runnable action) {
        if (f <= retired) action.run();
        else retirements.add(new Retirement(f, action));
    }

    private void runRetired() {
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Retirement> it = retirements.iterator(); it.hasNext(); ) {
            Retirement r = it.next();
            if (r.frame() > retired) continue;
            due.add(r.action());
            it.remove();
        }
        for (Runnable action : due) action.run();
    }

    // Once per host frame, before its submit. The host presents its own picture, so output is null. The submit
    // waits for the frame's async work and copies, so its retirement retires them too.
    @Override
    public final void endFrame(CgVulkanImage output) {
        if (recordingAsync) throw new IllegalStateException("The frame ends inside async work");
        handOver();
        if (asyncValue > asyncWaited) {
            waitInSubmit(asyncTimeline, asyncValue);
            asyncWaited = asyncValue;
        }
        submitTransfers();
        if (transferValue > transferWaited) {
            waitInSubmit(transferTimeline, transferValue);
            transferWaited = transferValue;
        }
        if (framePool != null) {
            FramePool used = framePool;
            framePool = null;
            afterSubmit(used::reset);
        }
        if (transferFramePool != null) {
            FramePool used = transferFramePool;
            transferFramePool = null;
            afterSubmit(used::reset);
        }
        long ended = frame++;
        afterSubmit(() -> retired = Math.max(retired, ended));
        runRetired();
    }

    @Override public final boolean ownsSubmission() { return false; }

    /** Off unless the host says its device was created with it, as for the three below. */
    @Override public boolean multiDrawIndirect() { return false; }

    @Override public boolean indirectCount() { return false; }

    @Override public boolean indirectFirstInstance() { return false; }

    @Override public boolean drawParameters() { return false; }

    /** Off unless the host says its device was created with it: a host's own features leave it off. */
    @Override public boolean independentBlend() { return false; }

    @Override public final boolean asyncCompute() { return asyncQueue != null; }

    @Override public final int asyncFamily() { return asyncQueueFamily == queueFamily() ? -1 : asyncQueueFamily; }

    // What came before goes into the host's submit, which signals main=n after it; the async work waits for n.
    @Override
    public final void beginAsync() {
        if (asyncQueue == null) return;
        if (recordingAsync) throw new IllegalStateException("beginAsync inside async work");
        handOver();
        signalInSubmit(mainTimeline, ++mainValue);
        asyncWaitMain = mainValue;
        if (framePool == null) framePool = freePools.isEmpty() ? newPool(asyncQueueFamily, false) : freePools.poll();
        asyncCommands = framePool.next();
        recordingAsync = true;
    }

    // Submitted now, ahead of the host's submit holding the signal it waits for: a timeline wait may precede its
    // signal. An image first made inside the async work has its first layout in the setup buffer, so that goes
    // into the host's submit and the wait moves past it.
    @Override
    public final long endAsync() {
        if (asyncQueue == null) return 0L;
        if (!recordingAsync) throw new IllegalStateException("endAsync with no async work open");
        check(vkEndCommandBuffer(asyncCommands), "vkEndCommandBuffer");
        recordingAsync = false;
        if (setup != null) {
            handOver();
            signalInSubmit(mainTimeline, ++mainValue);
            asyncWaitMain = mainValue;
        }
        long signal = ++asyncValue;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(mainTimeline))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT))
                    .pCommandBuffers(stack.pointers(asyncCommands))
                    .pSignalSemaphores(stack.longs(asyncTimeline))
                    .pNext(VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                            .waitSemaphoreValueCount(1).pWaitSemaphoreValues(stack.longs(asyncWaitMain))
                            .signalSemaphoreValueCount(1).pSignalSemaphoreValues(stack.longs(signal)).address());
            check(vkQueueSubmit(asyncQueue, submit, VK_NULL_HANDLE), "vkQueueSubmit");
        }
        asyncCommands = null;
        return signal;
    }

    @Override
    public final void waitAsync(long point) {
        if (asyncQueue == null || point <= asyncWaited) return;
        if (recordingAsync) throw new IllegalStateException("waitAsync inside async work");
        handOver();
        waitInSubmit(asyncTimeline, point);
        asyncWaited = point;
    }

    @Override public final boolean asyncTransfer() { return transferQueue != null; }

    @Override public final int transferFamily() { return transferQueueFamily == queueFamily() ? -1 : transferQueueFamily; }

    @Override
    public final VkCommandBuffer transferCommandBuffer() {
        if (transferQueue == null) throw new IllegalStateException(hostName + "'s device has no transfer queue of ours");
        if (transferCommands == null) {
            if (transferFramePool == null) {
                transferFramePool = freeTransferPools.isEmpty() ? newPool(transferQueueFamily, true) : freeTransferPools.poll();
            }
            transferCommands = transferFramePool.next();
        }
        return transferCommands;
    }

    // Submitted at once: nothing on the transfer queue waits for the host, and the host's submit waits for it.
    @Override
    public final long submitTransfers() {
        if (transferCommands == null) return transferValue;
        check(vkEndCommandBuffer(transferCommands), "vkEndCommandBuffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(transferCommands)).pSignalSemaphores(stack.longs(transferTimeline))
                    .pNext(VkTimelineSemaphoreSubmitInfo.calloc(stack).sType$Default()
                            .signalSemaphoreValueCount(1).pSignalSemaphoreValues(stack.longs(++transferValue)).address());
            check(vkQueueSubmit(transferQueue, submit, VK_NULL_HANDLE), "vkQueueSubmit");
        }
        transferCommands = null;
        return transferValue;
    }

    @Override
    public final void waitTransfers(long point) {
        if (transferQueue == null || point <= transferWaited) return;
        if (recordingAsync) throw new IllegalStateException("waitTransfers inside async work");
        handOver();
        waitInSubmit(transferTimeline, point);
        transferWaited = point;
    }

    private FramePool newPool(int family, boolean transfer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateCommandPool(device(), VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(family), null, lp),
                    "vkCreateCommandPool");
            FramePool p = new FramePool(lp.get(0), transfer);
            pools.add(p);
            return p;
        }
    }

    private long timeline() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer lp = stack.mallocLong(1);
            VkSemaphoreTypeCreateInfo type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE);
            check(vkCreateSemaphore(device(), VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address()),
                    null, lp), "vkCreateSemaphore");
            return lp.get(0);
        }
    }

    // The last submit has completed. Async work waiting on a signal no submit will now carry is released first,
    // so the queue can drain.
    private void closeQueues() {
        if (queuesClosed) return;
        queuesClosed = true;
        if (asyncQueue != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                LongBuffer at = stack.mallocLong(1);
                check(vkGetSemaphoreCounterValue(device(), mainTimeline, at), "vkGetSemaphoreCounterValue");
                if (at.get(0) < mainValue) {
                    check(vkSignalSemaphore(device(), VkSemaphoreSignalInfo.calloc(stack).sType$Default()
                            .semaphore(mainTimeline).value(mainValue)), "vkSignalSemaphore");
                }
            }
            check(vkQueueWaitIdle(asyncQueue), "vkQueueWaitIdle");
            vkDestroySemaphore(device(), mainTimeline, null);
            vkDestroySemaphore(device(), asyncTimeline, null);
        }
        if (transferQueue != null) {
            check(vkQueueWaitIdle(transferQueue), "vkQueueWaitIdle");
            vkDestroySemaphore(device(), transferTimeline, null);
        }
        for (FramePool p : pools) vkDestroyCommandPool(device(), p.pool, null);
    }

    @Override
    public final void submitAndWait() {
        throw new IllegalStateException(hostName + " submits this device's frames: a wait for one would deadlock");
    }

    @Override
    public final void waitRetired(long f) {
        throw new IllegalStateException(hostName + " submits this device's frames: frame " + f + " cannot be waited on");
    }

    @Override public int validationErrors() { return 0; }

    @Override public final void fromHost() { inSection = true; }

    @Override
    public final void toHost() {
        handOver();
        inSection = false;
    }

    // Our commands into the host's submit, the setup buffer first: it holds first layouts the others depend on.
    private void handOver() {
        if (setup == null && commands == null) return;
        if (verify) verify();
        if (setup != null) {
            check(vkEndCommandBuffer(setup), "vkEndCommandBuffer");
            execute(setup);
            setup = null;
        }
        if (commands != null) {
            // What we wrote, visible to whatever the host records next.
            barrier(commands);
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
            execute(commands);
            commands = null;
        }
    }

    private void verify() {
        if (device != null && ((VulkanEncoder) device.encoder()).passOpen()) report("a pass of ours is still open");
        for (Imported i : imported.values()) {
            VulkanTexture t = (VulkanTexture) i.wrapped();
            if (!t.allIn(t.resting)) report(t.desc().label() + " is not back where it rests");
        }
    }

    private void report(String fault) {
        if (reported.add(fault)) LOG.warn("[cg-host-verify] Vulkan hand-over: {}", fault);
    }
}
