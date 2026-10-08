package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
*///?}

/**
 * End-of-frame lifecycle for the modern tree: the engine's start, the resize check and {@link CgGraphicsLifecycle#tickFrame()}.
 *
 * <p>{@link LifecycleModern#frameEnd()} calls {@link #endFrame()} once per frame, from each loader's
 * post-GUI point, so a title-screen frame ends here as well as a world frame. Without it the
 * screen-sized FBO registry never learns the window changed, so the UI keeps rendering at the previous
 * size after a resize.</p>
 *
 * <p>Resize is polled rather than subscribed: 1.20.1 Forge has no window-resize event, and polling two
 * ints once a frame is cheaper than a mixin per loader.</p>
 */
public final class FrameHooks {

    private FrameHooks() {}

    public static void endFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && Windows.of(mc) != null) {
            int width = Windows.of(mc).getWidth();
            int height = Windows.of(mc).getHeight();
            // Starts the engine on the title screen rather than in the first world frame, so its shaders compile
            // before a world exists; after the first reload, since a material read mid-reload compiles twice.
            boolean start = LifecycleModern.resourcesLoaded() || CgGraphicsLifecycle.isInitialized();
            if (width > 0 && height > 0 && start) CgGraphicsLifecycle.ensureContext(width, height);
        }
        CgGraphicsLifecycle.tickFrame();
        // Under Vulkan our frame closes here, after everything drawn in it and before Minecraft's submit.
        //? if >=26.2 {
        /*if (GraphicsApi.vulkan()) Blaze3dVulkanHost.endMinecraftFrame();
        *///?}
    }
}
