package com.crystalgraphics.mc.modern.platform.vulkan;

//? if >=26.2 {
/*import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.vulkan.host.HostedVulkanHost;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.lang.reflect.Field;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
*///?}

/**
 * Minecraft 26.2's own Vulkan device as a {@code HostedVulkanHost}: CrystalGraphics draws into Minecraft's frame,
 * between its passes, on its queue and into its images. What it reads and what it keeps true is
 * plan/crystalgraphics/device-seam.md §9.1; how a hosted device works is {@code HostedVulkanHost}'s, and this class
 * answers only what is Minecraft's.
 *
 * <pre>{@code
 * CgGLBackend gl = Blaze3dVulkanHost.start(width, height);   // the host, a device over it, GL's semantics over that
 *
 * int color = Blaze3dVulkanHost.current().importTexture(mainTarget.getColorTexture());   // a name to attach
 * ...
 * Blaze3dVulkanHost.endMinecraftFrame();                     // once per Minecraft frame, before its submit
 * Blaze3dVulkanHost.shutdown();                              // at the game's shutdown signal
 * }</pre>
 *
 * <ul>
 *   <li>Our command buffers come from Blaze3D's per-submit pool and run in Minecraft's submit, through its encoder's
 *       {@code execute}. A frame retires through Blaze3D's destroy queue.</li>
 *   <li>Minecraft's images rest in {@code GENERAL}.</li>
 *   <li>{@code -Dcrystalgraphics.host.verify=true} checks every hand-over.</li>
 *   <li>Render thread only, and only while Minecraft renders through Vulkan.</li>
 * </ul>
 */
public final class Blaze3dVulkanHost
        //? if >=26.2 {
        /*extends HostedVulkanHost<GpuTexture>
        *///?}
{
    //? if >=26.2 {
    /*private static Blaze3dVulkanHost current;

    private final VulkanDevice minecraft;
    private final VulkanCommandEncoder encoder;

    private Blaze3dVulkanHost(VulkanDevice minecraft) {
        super("Minecraft", Boolean.getBoolean("crystalgraphics.host.verify"));
        this.minecraft = minecraft;
        this.encoder = minecraft.createCommandEncoder();
    }

    // Everything CgGL runs on under Vulkan: this host, a device over it, and GL's semantics over that.
    public static CgTrackedGLBackend start(int width, int height) {
        Blaze3dVulkanHost host = fromMinecraft();
        CgTrackedGLBackend gl = host.openBackend(width, height);
        current = host;
        return gl;
    }

    // The host start() built, or null before it.
    public static Blaze3dVulkanHost current() {
        return current;
    }

    // Ends our frame inside Minecraft's: after everything we draw in it, before Minecraft's submit.
    public static void endMinecraftFrame() {
        if (current != null) current.endHostFrame();
    }

    // The game is closing: our device closes from Blaze3D's destroy queue.
    public static void shutdown() {
        if (current != null) current.closeAfterLastSubmit();
    }

    // The one private read: the device keeps its backend in a private field, found by type so a rename cannot
    // hide it -- on GpuDevice itself, or from 26.3 on FrontendGpuDevice, GpuDevice being an interface there.
    // Everything past it is Blaze3D's public API.
    private static Blaze3dVulkanHost fromMinecraft() {
        GpuDevice gpu = RenderSystem.getDevice();
        for (Class<?> c = gpu.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!GpuDeviceBackend.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(gpu) instanceof VulkanDevice vulkan) return new Blaze3dVulkanHost(vulkan);
                } catch (ReflectiveOperationException | RuntimeException refused) {
                    throw new IllegalStateException("Cannot reach Minecraft's Vulkan device", refused);
                }
            }
        }
        throw new IllegalStateException("Minecraft's GpuDevice holds no VulkanDevice");
    }

    // ── HostedVulkanHost ─────────────────────────────────────────────────────────────────────────

    @Override protected VkCommandBuffer beginCommandBuffer() { return encoder.allocateAndBeginTransientCommandBuffer(); }

    // The barrier Minecraft ends each of its passes with.
    @Override
    protected void barrier(VkCommandBuffer cmd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
    }

    @Override protected void execute(VkCommandBuffer cmd) { encoder.execute(cmd); }

    // Into this submit's destroy slot, which Blaze3D empties once the submit has completed -- or in
    // VulkanDevice.close, after its wait for the GPU and before the device goes.
    @Override protected void afterSubmit(Runnable action) { encoder.queueForDestroy(action::run); }

    @Override
    protected CgGpuTexture wrap(GpuTexture texture) {
        GpuFormat format = texture.getFormat();
        return cgDevice().wrap(((VulkanGpuTexture) texture).vkImage(), VulkanConst.toVk(format),
                new CgGpuTexture.Desc("minecraft " + texture.getLabel(), CgGpuTexture.Kind.D2, cgFormat(format),
                        texture.getWidth(0), texture.getHeight(0), 1, texture.getMipLevels(), 1, CgGpuTexture.Usage.ALL),
                VK_IMAGE_LAYOUT_GENERAL);
    }

    @Override protected boolean isClosed(GpuTexture texture) { return texture.isClosed(); }

    // Minecraft's compute queue where it is a queue of its own rather than its graphics queue again.
    @Override
    protected VkQueue computeQueue() {
        return COMPUTE_QUEUE_UNUSED && minecraft.computeQueue() != minecraft.graphicsQueue()
                ? minecraft.computeQueue().vkQueue() : null;
    }

    @Override protected int computeQueueFamily() { return minecraft.computeQueue().queueFamilyIndex(); }

    @Override
    protected void waitInSubmit(long semaphore, long value) {
        encoder.waitSemaphore(semaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
    }

    @Override
    protected void signalInSubmit(long semaphore, long value) {
        encoder.signalSemaphore(semaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
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

    // ── The device ───────────────────────────────────────────────────────────────────────────────

    @Override public VkInstance instance() { return minecraft.instance().vkInstance(); }
    @Override public VkPhysicalDevice physicalDevice() { return minecraft.vkDevice().getPhysicalDevice(); }
    @Override public VkDevice device() { return minecraft.vkDevice(); }
    @Override public VkQueue queue() { return minecraft.graphicsQueue().vkQueue(); }
    @Override public int queueFamily() { return minecraft.graphicsQueue().queueFamilyIndex(); }
    // Minecraft creates its instance for Vulkan 1.2 (VulkanInstance).
    @Override public int apiVersion() { return VK_API_VERSION_1_2; }
    @Override public int framesInFlight() { return VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT; }
    // Minecraft's device enables no line rasterization extension.
    @Override public boolean bresenhamLines() { return false; }
    *///?}

    // Minecraft creates a compute queue it never submits to: in 26.2 and 26.3 no class but VulkanDevice and
    // VulkanPhysicalDevice names it. A version not yet checked records async compute in order.
    //? if >=26.2 <=26.3 {
    /*private static final boolean COMPUTE_QUEUE_UNUSED = true;
    *///?} else {
    private static final boolean COMPUTE_QUEUE_UNUSED = false;
    //?}
}
