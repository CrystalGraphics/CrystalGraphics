package com.crystalgraphics.mc.modern.platform.vulkan;

//? if >=26.2 {
/*import com.crystalgraphics.platform.device.format.CgFormat;
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
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
*///?}

/**
 * Minecraft 26.2's own Vulkan device as a {@code CgVulkanHost}: CrystalGraphics draws into Minecraft's frame,
 * between its passes, on its queue and into its images. What it reads and what it keeps true is
 * plan/crystalgraphics/device-seam.md §9.1.
 *
 * <pre>{@code
 * CgGLBackend gl = Blaze3dVulkanHost.start(width, height);   // the host, a device over it, GL's semantics over that
 *
 * int color = Blaze3dVulkanHost.current().importTexture(mainTarget.getColorTexture());   // a name to attach
 * ...
 * Blaze3dVulkanHost.endMinecraftFrame();                     // once per Minecraft frame, before its submit
 * }</pre>
 *
 * <ul>
 *   <li>Each host section records into command buffers of its own from Blaze3D's per-submit pool, and
 *       {@code CgGL.toHost()} hands them to Minecraft's submit with {@code execute}, the setup buffer first.
 *       Nothing records into the command buffer Minecraft records into.</li>
 *   <li>A frame is one of our frame ends. It retires when the submit carrying it has completed, which Blaze3D's
 *       own destroy queue reports.</li>
 *   <li>Minecraft's images rest in {@code GENERAL}, and every pass and transfer of ours leaves them there.</li>
 *   <li>{@code CgGL} used outside a host section is recorded for the next hand-over and logged once. It still has
 *       to reach Minecraft before Minecraft's next submit, so bracket it.</li>
 *   <li>{@code -Dcrystalgraphics.host.verify=true} checks every hand-over: no pass of ours open, and every image of
 *       Minecraft's back in {@code GENERAL}.</li>
 *   <li>Render thread only, and only while Minecraft renders through Vulkan.</li>
 * </ul>
 */
public final class Blaze3dVulkanHost
        //? if >=26.2 {
        /*implements CgVulkanHost
        *///?}
{
    //? if >=26.2 {
    /*private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final boolean VERIFY = Boolean.getBoolean("crystalgraphics.host.verify");

    private static Blaze3dVulkanHost current;
    private static boolean shuttingDown;

    private final VulkanDevice minecraft;
    private final VulkanCommandEncoder encoder;
    private CgVulkanDevice device;
    private CgTrackedGLBackend gl;

    // This hand-over's commands, taken from Blaze3D's per-submit pool when first asked for.
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

    // Minecraft's textures we have wrapped, by the texture Minecraft holds.
    private final Map<GpuTexture, Imported> imported = new IdentityHashMap<>();

    private record Imported(CgGpuTexture wrapped, int name) {}

    private Blaze3dVulkanHost(VulkanDevice minecraft) {
        this.minecraft = minecraft;
        this.encoder = minecraft.createCommandEncoder();
        if (VERIFY) LOG.info("[cg-host-verify] ARMED for Vulkan -- every hand-over is checked for an open pass "
                + "and for Minecraft's images outside GENERAL. A line per fault follows, or silence.");
    }

    // Everything CgGL runs on under Vulkan: this host, a device over it, and GL's semantics over that.
    public static CgTrackedGLBackend start(int width, int height) {
        Blaze3dVulkanHost host = fromMinecraft();
        CgVulkanDevice device = new CgVulkanDevice(host, width, height);
        host.attach(device);
        host.gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), VERIFY);
        CgGlState.setProvider(new CgTrackedStateProvider(host.gl));
        current = host;
        return host.gl;
    }

    // The host start() built, or null before it.
    public static Blaze3dVulkanHost current() {
        return current;
    }

    // Ends our frame inside Minecraft's: after everything we draw in it, before Minecraft's submit.
    public static void endMinecraftFrame() {
        if (current == null || shuttingDown) return;
        CgGL.fromHost();
        try {
            current.gl.endFrame();
        } finally {
            CgGL.toHost();
        }
    }

    // The game is closing: our device closes from Blaze3D's destroy queue, which runs it once the submit holding
    // our last commands has completed, or in VulkanDevice.close after its wait for the GPU and before the device
    // goes. At the shutdown signal itself a submit may still carry our commands. Nothing of ours records after.
    public static void shutdown() {
        if (current == null || shuttingDown) return;
        shuttingDown = true;
        CgVulkanDevice device = current.device;
        current.encoder.queueForDestroy(device::close);
    }

    // The one private read: GpuDevice keeps its backend in a private field, found by type so a rename cannot
    // hide it. Everything past it is Blaze3D's public API.
    public static Blaze3dVulkanHost fromMinecraft() {
        GpuDevice gpu = RenderSystem.getDevice();
        for (Field f : GpuDevice.class.getDeclaredFields()) {
            if (!GpuDeviceBackend.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                if (f.get(gpu) instanceof VulkanDevice vulkan) return new Blaze3dVulkanHost(vulkan);
            } catch (ReflectiveOperationException | RuntimeException refused) {
                throw new IllegalStateException("Cannot reach Minecraft's Vulkan device", refused);
            }
        }
        throw new IllegalStateException("Minecraft's GpuDevice holds no VulkanDevice");
    }

    // The device built over this host: importTexture wraps for it, and the verifier asks it about passes.
    public void attach(CgVulkanDevice device) {
        this.device = device;
    }

    // Minecraft's texture as a GL name the engine can attach, the same name for as long as the texture lives.
    public int importTexture(GpuTexture texture) {
        forgetClosed();
        Imported known = imported.get(texture);
        if (known != null) return known.name();
        GpuFormat format = texture.getFormat();
        CgGpuTexture wrapped = device.wrap(((VulkanGpuTexture) texture).vkImage(), VulkanConst.toVk(format),
                new CgGpuTexture.Desc("minecraft " + texture.getLabel(), CgGpuTexture.Kind.D2, cgFormat(format),
                        texture.getWidth(0), texture.getHeight(0), 1, texture.getMipLevels(), 1, CgGpuTexture.Usage.ALL),
                VK_IMAGE_LAYOUT_GENERAL);
        int name = CgGL.importHostTexture(wrapped);
        imported.put(texture, new Imported(wrapped, name));
        return name;
    }

    // The default framebuffer at the main target's size, as GL's is the window's.
    public void matchSurface(int width, int height) {
        device.resize(width, height);
    }

    // A resize closes Minecraft's old target; our names and views over it go with it.
    private void forgetClosed() {
        Iterator<Map.Entry<GpuTexture, Imported>> it = imported.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GpuTexture, Imported> e = it.next();
            if (!e.getKey().isClosed()) continue;
            CgGL.glDeleteTextures(e.getValue().name());
            device.release(e.getValue().wrapped());
            it.remove();
        }
    }

    private static CgFormat cgFormat(GpuFormat format) {
        return switch (format) {
            case D32_FLOAT -> CgFormat.DEPTH32_FLOAT;
            case D32_FLOAT_S8_UINT -> CgFormat.DEPTH32_FLOAT_STENCIL8;
            case D24_UNORM_S8_UINT -> CgFormat.DEPTH24_PLUS_STENCIL8;
            case D16_UNORM -> CgFormat.DEPTH16_UNORM;
            default -> CgFormat.valueOf(format.name());
        };
    }

    // ── CgVulkanHost ─────────────────────────────────────────────────────────────────────────────

    @Override public VkInstance instance() { return minecraft.instance().vkInstance(); }
    @Override public VkPhysicalDevice physicalDevice() { return minecraft.vkDevice().getPhysicalDevice(); }
    @Override public VkDevice device() { return minecraft.vkDevice(); }
    @Override public VkQueue queue() { return minecraft.graphicsQueue().vkQueue(); }
    @Override public int queueFamily() { return minecraft.graphicsQueue().queueFamilyIndex(); }
    // Minecraft creates its instance for Vulkan 1.2 (VulkanInstance).
    @Override public int apiVersion() { return VK_API_VERSION_1_2; }
    @Override public int framesInFlight() { return VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT; }

    @Override
    public VkCommandBuffer commandBuffer() {
        if (commands == null) {
            noteOutsideSection();
            commands = encoder.allocateAndBeginTransientCommandBuffer();
            // Minecraft ends each of its passes with this barrier; ours opens with it too, so nothing we read
            // depends on that habit.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
        }
        return commands;
    }

    @Override
    public VkCommandBuffer setupCommandBuffer() {
        if (setup == null) {
            noteOutsideSection();
            setup = encoder.allocateAndBeginTransientCommandBuffer();
        }
        return setup;
    }

    // Not while start() builds the device: its first images are recorded before any section, and the first
    // hand-over of that same frame carries them.
    private void noteOutsideSection() {
        if (inSection || warnedOutside || gl == null) return;
        warnedOutside = true;
        LOG.warn("[cg] CgGL used outside a host section under Minecraft's Vulkan backend: it reaches Minecraft at "
                + "the next hand-over. Bracket it with CgGL.fromHost() and CgGL.toHost().", new Throwable("here"));
    }

    @Override public long frameIndex() { return frame; }

    @Override
    public long retiredFrame() {
        runRetired();
        return retired;
    }

    @Override
    public void whenFrameRetired(long f, Runnable action) {
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

    // Once per Minecraft frame, before its submit. Minecraft presents its own picture, so output is null.
    @Override
    public void endFrame(CgVulkanImage output) {
        handOver();
        long ended = frame++;
        // Queued into this submit's destroy slot, which Blaze3D empties once the submit has completed.
        encoder.queueForDestroy(() -> retired = Math.max(retired, ended));
        runRetired();
    }

    @Override public boolean ownsSubmission() { return false; }

    @Override
    public void submitAndWait() {
        throw new IllegalStateException("Minecraft submits this device's frames: a wait for one would deadlock");
    }

    @Override
    public void waitRetired(long f) {
        throw new IllegalStateException("Minecraft submits this device's frames: frame " + f + " cannot be waited on");
    }

    @Override public int validationErrors() { return 0; }

    // Minecraft's device enables no line rasterization extension.
    @Override public boolean bresenhamLines() { return false; }

    @Override public void fromHost() { inSection = true; }

    @Override
    public void toHost() {
        handOver();
        inSection = false;
    }

    // Our commands into Minecraft's submit, the setup buffer first: it holds first layouts the others depend on.
    private void handOver() {
        if (setup == null && commands == null) return;
        if (VERIFY) verify();
        if (setup != null) {
            check(vkEndCommandBuffer(setup), "vkEndCommandBuffer");
            encoder.execute(setup);
            setup = null;
        }
        if (commands != null) {
            // What we wrote, visible to whatever Minecraft records next.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanCommandEncoder.memoryBarrier(commands, stack);
            }
            check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
            encoder.execute(commands);
            commands = null;
        }
    }

    private void verify() {
        if (device != null && ((VulkanEncoder) device.encoder()).passOpen()) report("a pass of ours is still open");
        for (Imported i : imported.values()) {
            VulkanTexture t = (VulkanTexture) i.wrapped();
            if (!t.allIn(VK_IMAGE_LAYOUT_GENERAL)) report(t.desc().label() + " is not back in GENERAL");
        }
    }

    private void report(String fault) {
        if (reported.add(fault)) LOG.warn("[cg-host-verify] Vulkan hand-over: {}", fault);
    }
    *///?}
}
