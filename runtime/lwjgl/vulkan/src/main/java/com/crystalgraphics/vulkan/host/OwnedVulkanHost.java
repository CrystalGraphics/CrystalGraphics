package com.crystalgraphics.vulkan.host;

import com.crystalgraphics.vulkan.CgVulkanDevice;
import com.crystalgraphics.vulkan.CgVulkanHost;
import com.crystalgraphics.vulkan.CgVulkanImage;
import com.crystalgraphics.vulkan.command.VulkanBarriers;
import com.crystalgraphics.vulkan.format.VulkanCheck;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLineRasterizationFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static com.crystalgraphics.vulkan.format.VulkanCheck.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.EXTLineRasterization.VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

/**
 * A Vulkan device of its own, presenting to a GLFW window: instance, device, one graphics queue, the swapchain,
 * and {@link #FRAMES} frames in flight. What the harness and an application with no Vulkan device run on.
 *
 * <pre>{@code
 * GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);   // no GL context on this window
 * long window = GLFW.glfwCreateWindow(1280, 720, "app", 0, 0);
 * try (OwnedVulkanHost host = new OwnedVulkanHost(window, true)) {
 *     CgVulkanDevice device = new CgVulkanDevice(host);
 *     ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Validation needs {@code VK_LAYER_KHRONOS_validation} (the Vulkan SDK, or {@code VK_LAYER_PATH}); asked for
 *       without it, the host says so once and runs unvalidated. Errors are counted, {@link #validationErrors()}.</li>
 *   <li>The frame is presented the right way up: {@link #endFrame} flips it, the one flip there is.</li>
 *   <li>Close the device before the host, and the host before the window.</li>
 * </ul>
 */
public final class OwnedVulkanHost implements CgVulkanHost, AutoCloseable {

    public static final int FRAMES = 3;
    private static final String VALIDATION = "VK_LAYER_KHRONOS_validation";

    private final long window;
    private final VkInstance instance;
    private final VkDebugUtilsMessengerCallbackEXT callback;
    private final long messenger;
    private final long surface;
    private final VkPhysicalDevice physical;
    private final VkDevice device;
    private final int family;
    private boolean bresenhamLines;
    private boolean multiDrawIndirect, indirectCount, indirectFirstInstance;
    private final VkQueue queue;

    private final long[] pools = new long[FRAMES];
    private final VkCommandBuffer[] buffers = new VkCommandBuffer[FRAMES];
    private final VkCommandBuffer[] setups = new VkCommandBuffer[FRAMES];
    private boolean setupOpen;
    private final long[] fences = new long[FRAMES];
    private final long[] acquired = new long[FRAMES];
    private final boolean[] submitted = new boolean[FRAMES];
    private final TreeMap<Long, List<Runnable>> retirements = new TreeMap<>();
    private long frame;
    private long retired = -1;

    private long swapchain;
    private long[] images = new long[0];
    private long[] presentReady = new long[0];
    private int swapFormat, swapWidth, swapHeight;
    private boolean stale;
    private int errors;

    /** @param validate ask for the Khronos validation layer */
    public OwnedVulkanHost(long window, boolean validate) {
        this.window = window;
        try (MemoryStack stack = stackPush()) {
            boolean layer = validate && hasLayer(stack, VALIDATION);
            if (validate && !layer)
                System.err.println("[crystalgraphics] vulkan: " + VALIDATION + " is not installed; running unvalidated");
            instance = createInstance(stack, layer);
            callback = layer ? VkDebugUtilsMessengerCallbackEXT.create(this::message) : null;
            messenger = layer ? createMessenger(stack) : VK_NULL_HANDLE;

            LongBuffer lp = stack.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, lp), "glfwCreateWindowSurface");
            surface = lp.get(0);

            int[] familyOut = new int[1];
            physical = choosePhysicalDevice(stack, familyOut);
            family = familyOut[0];
            device = createDevice(stack);
            PointerBuffer pp = stack.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, pp);
            queue = new VkQueue(pp.get(0), device);
            createFrames(stack);
        }
        createSwapchain();
        beginFrame();
    }

    // ── CgVulkanHost ───────────────────────────────────────────────────────────

    @Override public VkInstance instance() { return instance; }
    @Override public VkPhysicalDevice physicalDevice() { return physical; }
    @Override public VkDevice device() { return device; }
    @Override public VkQueue queue() { return queue; }
    @Override public int queueFamily() { return family; }
    @Override public int apiVersion() { return VK_API_VERSION_1_2; }
    @Override public int framesInFlight() { return FRAMES; }
    @Override public VkCommandBuffer commandBuffer() { return buffers[slot()]; }

    @Override
    public VkCommandBuffer setupCommandBuffer() {
        VkCommandBuffer setup = setups[slot()];
        if (!setupOpen) {
            begin(setup);
            setupOpen = true;
        }
        return setup;
    }
    @Override public long frameIndex() { return frame; }
    @Override public long retiredFrame() { return retired; }
    @Override public boolean ownsSubmission() { return true; }
    @Override public int validationErrors() { return errors; }

    @Override public boolean bresenhamLines() { return bresenhamLines; }
    @Override public boolean multiDrawIndirect() { return multiDrawIndirect; }
    @Override public boolean indirectCount() { return indirectCount; }
    @Override public boolean indirectFirstInstance() { return indirectFirstInstance; }

    @Override
    public void whenFrameRetired(long f, Runnable action) {
        if (f <= retired) action.run();
        else retirements.computeIfAbsent(f, k -> new ArrayList<>()).add(action);
    }

    @Override
    public void endFrame(CgVulkanImage output) {
        int s = slot();
        VkCommandBuffer cmd = buffers[s];
        int image = acquire();
        if (image >= 0) present(cmd, output, images[image]);
        check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(commands(stack, s));
            if (image >= 0) {
                submit.waitSemaphoreCount(1).pWaitSemaphores(stack.longs(acquired[s]))
                        .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                        .pSignalSemaphores(stack.longs(presentReady[image]));
            }
            check(vkQueueSubmit(queue, submit, fences[s]), "vkQueueSubmit");
            submitted[s] = true;
            if (image >= 0) {
                VkPresentInfoKHR info = VkPresentInfoKHR.calloc(stack).sType$Default()
                        .pWaitSemaphores(stack.longs(presentReady[image])).swapchainCount(1)
                        .pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(image));
                int result = vkQueuePresentKHR(queue, info);
                if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) stale = true;
                else check(result, "vkQueuePresentKHR");
            }
        }
        frame++;
        beginFrame();
    }

    @Override
    public void submitAndWait() {
        int s = slot();
        VkCommandBuffer cmd = buffers[s];
        check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        try (MemoryStack stack = stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(commands(stack, s));
            check(vkQueueSubmit(queue, submit, fences[s]), "vkQueueSubmit");
        }
        check(vkWaitForFences(device, fences[s], true, -1L), "vkWaitForFences");
        check(vkResetFences(device, fences[s]), "vkResetFences");
        // A fence covers everything submitted before it: every earlier frame is finished too.
        retireThrough(frame - 1);
        check(vkResetCommandPool(device, pools[s], 0), "vkResetCommandPool");
        begin(cmd);
    }

    @Override
    public void waitRetired(long f) {
        if (f <= retired) return;
        if (f >= frame) throw new IllegalStateException("Frame " + f + " has not ended");
        int s = (int) (f % FRAMES);
        check(vkWaitForFences(device, fences[s], true, -1L), "vkWaitForFences");
        retireThrough(f);
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        retired = frame;                    // idle: the frame being recorded has nothing in flight either
        while (!retirements.isEmpty()) for (Runnable r : retirements.pollFirstEntry().getValue()) r.run();
        destroySwapchain(swapchain);
        for (int i = 0; i < FRAMES; i++) {
            vkDestroyFence(device, fences[i], null);
            vkDestroySemaphore(device, acquired[i], null);
            vkDestroyCommandPool(device, pools[i], null);
        }
        vkDestroyDevice(device, null);
        vkDestroySurfaceKHR(instance, surface, null);
        if (messenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, messenger, null);
        vkDestroyInstance(instance, null);
        if (callback != null) callback.free();
    }

    // ── frames ─────────────────────────────────────────────────────────────────

    private int slot() {
        return (int) (frame % FRAMES);
    }

    private void beginFrame() {
        int s = slot();
        if (submitted[s]) {
            check(vkWaitForFences(device, fences[s], true, -1L), "vkWaitForFences");
            check(vkResetFences(device, fences[s]), "vkResetFences");
            retireThrough(frame - FRAMES);
        }
        check(vkResetCommandPool(device, pools[s], 0), "vkResetCommandPool");
        begin(buffers[s]);
    }

    /** The setup buffer, when anything was recorded into it, then the frame's own. */
    private PointerBuffer commands(MemoryStack stack, int s) {
        if (!setupOpen) return stack.pointers(buffers[s]);
        check(vkEndCommandBuffer(setups[s]), "vkEndCommandBuffer");
        setupOpen = false;
        return stack.pointers(setups[s], buffers[s]);
    }

    private static void begin(VkCommandBuffer cmd) {
        try (MemoryStack stack = stackPush()) {
            check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
        }
    }

    private void retireThrough(long f) {
        if (f > retired) retired = Math.min(f, frame - 1);
        while (!retirements.isEmpty() && retirements.firstKey() <= retired) {
            for (Runnable r : retirements.pollFirstEntry().getValue()) r.run();
        }
    }

    private void createFrames(MemoryStack stack) {
        LongBuffer lp = stack.mallocLong(1);
        PointerBuffer two = stack.mallocPointer(2);
        for (int i = 0; i < FRAMES; i++) {
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(family), null, lp), "vkCreateCommandPool");
            pools[i] = lp.get(0);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(pools[i]).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(2), two),
                    "vkAllocateCommandBuffers");
            buffers[i] = new VkCommandBuffer(two.get(0), device);
            setups[i] = new VkCommandBuffer(two.get(1), device);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, lp), "vkCreateFence");
            fences[i] = lp.get(0);
            acquired[i] = semaphore(stack);
        }
    }

    private long semaphore(MemoryStack stack) {
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, lp), "vkCreateSemaphore");
        return lp.get(0);
    }

    // ── presentation ───────────────────────────────────────────────────────────

    /** The swapchain image to present this frame into, or -1 while the window has no area. */
    private int acquire() {
        if (stale) createSwapchain();
        if (swapchain == VK_NULL_HANDLE) return -1;
        try (MemoryStack stack = stackPush()) {
            IntBuffer ip = stack.mallocInt(1);
            int result = vkAcquireNextImageKHR(device, swapchain, -1L, acquired[slot()], VK_NULL_HANDLE, ip);
            if (result == VK_ERROR_OUT_OF_DATE_KHR) {
                stale = true;
                return -1;
            }
            if (result == VK_SUBOPTIMAL_KHR) stale = true;
            else check(result, "vkAcquireNextImageKHR");
            return ip.get(0);
        }
    }

    /** The frame's picture onto the swapchain image, flipped: its row 0 is GL's bottom, a window's is its top. */
    private void present(VkCommandBuffer cmd, CgVulkanImage output, long target) {
        VulkanBarriers.image(cmd, target, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1, VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        try (MemoryStack stack = stackPush()) {
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.srcOffsets(0).set(0, 0, 0);
            blit.srcOffsets(1).set(output.width(), output.height(), 1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.dstOffsets(0).set(0, swapHeight, 0);
            blit.dstOffsets(1).set(swapWidth, 0, 1);
            boolean same = output.width() == swapWidth && output.height() == swapHeight;
            vkCmdBlitImage(cmd, output.image(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, same ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        }
        VulkanBarriers.image(cmd, target, VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
    }

    private void createSwapchain() {
        stale = false;
        vkDeviceWaitIdle(device);
        long old = swapchain;
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            if (w.get(0) == 0 || h.get(0) == 0) {           // minimised: nothing to present into
                destroySwapchain(old);
                swapchain = VK_NULL_HANDLE;
                stale = true;
                return;
            }
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, caps), "surface capabilities");
            swapWidth = caps.currentExtent().width() != -1 ? caps.currentExtent().width()
                    : Math.max(caps.minImageExtent().width(), Math.min(w.get(0), caps.maxImageExtent().width()));
            swapHeight = caps.currentExtent().height() != -1 ? caps.currentExtent().height()
                    : Math.max(caps.minImageExtent().height(), Math.min(h.get(0), caps.maxImageExtent().height()));
            int count = Math.max(caps.minImageCount(), FRAMES);
            if (caps.maxImageCount() > 0) count = Math.min(count, caps.maxImageCount());
            VkSurfaceFormatKHR format = chooseFormat(stack);
            swapFormat = format.format();

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(surface).minImageCount(count).imageFormat(swapFormat).imageColorSpace(format.colorSpace())
                    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(choosePresentMode(stack))
                    .clipped(true).oldSwapchain(old);
            ci.imageExtent().set(swapWidth, swapHeight);
            LongBuffer lp = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, ci, null, lp), "vkCreateSwapchainKHR");
            destroySwapchain(old);
            swapchain = lp.get(0);

            IntBuffer n = stack.mallocInt(1);
            check(vkGetSwapchainImagesKHR(device, swapchain, n, null), "vkGetSwapchainImagesKHR");
            LongBuffer imgs = stack.mallocLong(n.get(0));
            check(vkGetSwapchainImagesKHR(device, swapchain, n, imgs), "vkGetSwapchainImagesKHR");
            images = new long[n.get(0)];
            presentReady = new long[n.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = imgs.get(i);
                presentReady[i] = semaphore(stack);
            }
        }
    }

    private void destroySwapchain(long old) {
        if (old == VK_NULL_HANDLE) return;
        for (long s : presentReady) vkDestroySemaphore(device, s, null);
        presentReady = new long[0];
        vkDestroySwapchainKHR(device, old, null);
    }

    /** {@code B8G8R8A8_UNORM} first: the unconverted pixels GL's default framebuffer shows. */
    private VkSurfaceFormatKHR chooseFormat(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, n, null), "surface formats");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(n.get(0), stack);
        check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, n, formats), "surface formats");
        for (int preferred : new int[] {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}) {
            for (VkSurfaceFormatKHR f : formats) {
                if (f.format() == preferred && f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return f;
            }
        }
        return formats.get(0);
    }

    /** Unsynchronised first, as the GL harness swaps: the loop paces itself. */
    private int choosePresentMode(MemoryStack stack) {
        IntBuffer n = stack.mallocInt(1);
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physical, surface, n, null), "present modes");
        IntBuffer modes = stack.mallocInt(n.get(0));
        check(vkGetPhysicalDeviceSurfacePresentModesKHR(physical, surface, n, modes), "present modes");
        Set<Integer> offered = new HashSet<>();
        for (int i = 0; i < modes.limit(); i++) offered.add(modes.get(i));
        // -Dcrystalgraphics.vulkan.presentMode=immediate|mailbox|fifo forces one the surface offers.
        String forced = System.getProperty("crystalgraphics.vulkan.presentMode");
        if (forced != null) {
            int m = switch (forced) {
                case "immediate" -> VK_PRESENT_MODE_IMMEDIATE_KHR;
                case "mailbox" -> VK_PRESENT_MODE_MAILBOX_KHR;
                case "fifo" -> VK_PRESENT_MODE_FIFO_KHR;
                default -> throw new IllegalArgumentException("crystalgraphics.vulkan.presentMode=" + forced
                        + ": immediate, mailbox or fifo");
            };
            if (!offered.contains(m)) throw new IllegalStateException("The surface does not offer present mode " + forced);
            return m;
        }
        for (int m : new int[] {VK_PRESENT_MODE_IMMEDIATE_KHR, VK_PRESENT_MODE_MAILBOX_KHR}) {
            if (offered.contains(m)) return m;
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    // ── creation ───────────────────────────────────────────────────────────────

    private static boolean hasLayer(MemoryStack stack, String name) {
        IntBuffer n = stack.mallocInt(1);
        check(vkEnumerateInstanceLayerProperties(n, null), "vkEnumerateInstanceLayerProperties");
        // The heap, not the stack: a driver lists enough of these to overflow it.
        try (VkLayerProperties.Buffer layers = VkLayerProperties.malloc(n.get(0))) {
            check(vkEnumerateInstanceLayerProperties(n, layers), "vkEnumerateInstanceLayerProperties");
            for (VkLayerProperties l : layers) if (l.layerNameString().equals(name)) return true;
            return false;
        }
    }

    private VkInstance createInstance(MemoryStack stack, boolean validate) {
        PointerBuffer required = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (required == null) throw new IllegalStateException("GLFW finds no Vulkan: no loader or no Vulkan driver");
        boolean mac = Platform.get() == Platform.MACOSX;
        PointerBuffer extensions = stack.mallocPointer(required.remaining() + 2);
        extensions.put(required);
        if (validate) extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        if (mac) extensions.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
        extensions.flip();
        VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .pApplicationName(stack.UTF8("CrystalGraphics")).pEngineName(stack.UTF8("CrystalGraphics"))
                .apiVersion(VK_API_VERSION_1_2);
        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(app)
                .ppEnabledExtensionNames(extensions)
                .flags(mac ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0);
        if (validate) ci.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION)));
        PointerBuffer pp = stack.mallocPointer(1);
        check(vkCreateInstance(ci, null, pp), "vkCreateInstance");
        return new VkInstance(pp.get(0), ci);
    }

    private long createMessenger(MemoryStack stack) {
        VkDebugUtilsMessengerCreateInfoEXT ci = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT)
                .pfnUserCallback(callback);
        LongBuffer lp = stack.mallocLong(1);
        check(vkCreateDebugUtilsMessengerEXT(instance, ci, null, lp), "vkCreateDebugUtilsMessengerEXT");
        return lp.get(0);
    }

    private int message(int severity, int types, long data, long user) {
        String text = VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();
        boolean error = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0;
        if (error) errors++;
        System.err.println("[vulkan] " + (error ? "ERROR " : "") + text);
        return VK_FALSE;
    }

    /** Discrete first; Vulkan 1.2, the three extensions, and one family that draws and presents. */
    private VkPhysicalDevice choosePhysicalDevice(MemoryStack stack, int[] familyOut) {
        IntBuffer n = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, n, null), "vkEnumeratePhysicalDevices");
        PointerBuffer handles = stack.mallocPointer(n.get(0));
        check(vkEnumeratePhysicalDevices(instance, n, handles), "vkEnumeratePhysicalDevices");
        VkPhysicalDevice best = null;
        int bestScore = -1;
        List<String> refused = new ArrayList<>();
        for (int i = 0; i < handles.limit(); i++) {
            VkPhysicalDevice pd = new VkPhysicalDevice(handles.get(i), instance);
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(pd, props);
            String why = unsuitable(stack, pd, props);
            int queueFamily = why == null ? drawAndPresentFamily(stack, pd) : -1;
            if (why == null && queueFamily < 0) why = "no queue family both draws and presents";
            if (why != null) {
                refused.add(props.deviceNameString() + ": " + why);
                continue;
            }
            int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2
                    : props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 1 : 0;
            if (score > bestScore) {
                best = pd;
                bestScore = score;
                familyOut[0] = queueFamily;
            }
        }
        if (best == null) throw new IllegalStateException("No Vulkan device CrystalGraphics can run on: " + refused);
        return best;
    }

    private static String unsuitable(MemoryStack stack, VkPhysicalDevice pd, VkPhysicalDeviceProperties props) {
        if (props.apiVersion() < VK_API_VERSION_1_2) return "Vulkan below 1.2";
        Set<String> ext = extensions(stack, pd);
        for (String need : List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME, VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME,
                VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) {
            if (!ext.contains(need)) return "no " + need;
        }
        return null;
    }

    private static Set<String> extensions(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(pd, (String) null, n, null), "device extensions");
        try (VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(n.get(0))) {
            check(vkEnumerateDeviceExtensionProperties(pd, (String) null, n, props), "device extensions");
            Set<String> names = new HashSet<>();
            for (VkExtensionProperties p : props) names.add(p.extensionNameString());
            return names;
        }
    }

    private int drawAndPresentFamily(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, families);
        IntBuffer present = stack.mallocInt(1);
        for (int i = 0; i < families.limit(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            check(vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface, present), "surface support");
            if (present.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private VkDevice createDevice(MemoryStack stack) {
        VkPhysicalDeviceFeatures has = VkPhysicalDeviceFeatures.malloc(stack);
        vkGetPhysicalDeviceFeatures(physical, has);
        boolean hasLines = extensions(stack, physical).contains(VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME);
        VkPhysicalDeviceLineRasterizationFeaturesEXT hasLineModes = VkPhysicalDeviceLineRasterizationFeaturesEXT.calloc(stack)
                .sType$Default();
        VkPhysicalDeviceVulkan12Features has12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                .pNext(hasLines ? hasLineModes.address() : 0L);
        vkGetPhysicalDeviceFeatures2(physical, VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(has12.address()));
        bresenhamLines = hasLines && hasLineModes.bresenhamLines();
        indirectCount = has12.drawIndirectCount();
        indirectFirstInstance = has.drawIndirectFirstInstance();
        multiDrawIndirect = has.multiDrawIndirect();

        VkPhysicalDeviceFeatures enable = VkPhysicalDeviceFeatures.calloc(stack)
                .samplerAnisotropy(has.samplerAnisotropy()).fillModeNonSolid(has.fillModeNonSolid())
                .independentBlend(has.independentBlend()).imageCubeArray(has.imageCubeArray())
                // What GL 4.x gives a shader: doubles and 64-bit integers, where the hardware has them.
                .shaderFloat64(has.shaderFloat64()).shaderInt64(has.shaderInt64())
                // GPU-driven draws: several commands per call, a first instance, and image writes in kernels.
                .multiDrawIndirect(multiDrawIndirect).drawIndirectFirstInstance(indirectFirstInstance)
                .shaderStorageImageWriteWithoutFormat(has.shaderStorageImageWriteWithoutFormat());
        // GL's line rule, where the device has it: without it a line on a pixel boundary can vanish.
        VkPhysicalDeviceLineRasterizationFeaturesEXT lineModes = VkPhysicalDeviceLineRasterizationFeaturesEXT.calloc(stack)
                .sType$Default().bresenhamLines(true);
        VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamic = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                .sType$Default().dynamicRendering(true).pNext(bresenhamLines ? lineModes.address() : 0L);
        VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default()
                .hostQueryReset(has12.hostQueryReset()).drawIndirectCount(indirectCount).pNext(dynamic.address());

        List<String> names = new ArrayList<>(List.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
        if (extensions(stack, physical).contains(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME))
            names.add(VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
        if (bresenhamLines) names.add(VK_EXT_LINE_RASTERIZATION_EXTENSION_NAME);
        PointerBuffer ext = stack.mallocPointer(names.size());
        for (String name : names) ext.put(stack.UTF8(name));
        ext.flip();

        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                .queueFamilyIndex(family).pQueuePriorities(stack.floats(1f));
        VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(v12.address())
                .pQueueCreateInfos(queues).ppEnabledExtensionNames(ext).pEnabledFeatures(enable);
        PointerBuffer pp = stack.mallocPointer(1);
        check(vkCreateDevice(physical, ci, null, pp), "vkCreateDevice");
        return new VkDevice(pp.get(0), physical, ci, VK_API_VERSION_1_2);
    }
}
