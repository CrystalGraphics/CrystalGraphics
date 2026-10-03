package com.crystalgraphics.vulkan.host;

import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.CgTrackedStateProvider;
import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.CgVulkanHost;
import com.crystalgraphics.vulkan.CgVulkanImage;
import com.crystalgraphics.vulkan.command.VulkanEncoder;
import com.crystalgraphics.vulkan.resource.VulkanTexture;
import com.crystalgraphics.vulkan.shader.ShadercGlslCompiler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;

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

    // ── Use ──────────────────────────────────────────────────────────────────────────────────────

    /** Builds the device over this host and GL's semantics over that, and makes it CgGL's state provider. Once. */
    public final CgTrackedGLBackend openBackend(int width, int height) {
        if (device != null) throw new IllegalStateException("openBackend already ran on this host");
        device = new CgVulkanDevice(this, width, height);
        gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), verify);
        CgGlState.setProvider(new CgTrackedStateProvider(gl));
        return gl;
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
        afterSubmit(device::close);
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

    // Once per host frame, before its submit. The host presents its own picture, so output is null.
    @Override
    public final void endFrame(CgVulkanImage output) {
        handOver();
        long ended = frame++;
        afterSubmit(() -> retired = Math.max(retired, ended));
        runRetired();
    }

    @Override public final boolean ownsSubmission() { return false; }

    /** Minecraft 26.2 enables neither indirect count nor a first instance, and a hosted device has what it enabled. */
    @Override public boolean indirectCount() { return false; }

    @Override public boolean indirectFirstInstance() { return false; }

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
