package com.crystalgraphics.mc.modern.platform;

//? if >=26.2 {
/*import com.mojang.blaze3d.systems.RenderSystem;
*///?}

/**
 * Which API Minecraft renders through on this client: Vulkan on 26.2 and later when the player's
 * {@code preferredGraphicsBackend} picks it and the machine has it, OpenGL everywhere else.
 *
 * <pre>{@code
 * if (GraphicsApi.vulkan()) {
 *     // CgGL runs on the tracked backend over Minecraft's own Vulkan device
 * } else {
 *     // CgGL is GL, and Blaze3D's GlStateManager cache is ours to keep true
 * }
 * }</pre>
 *
 * <p>Decided once, from Minecraft's device, so ask on the render thread after the device exists: at a host
 * section, never at mod construction.</p>
 */
public final class GraphicsApi {

    private GraphicsApi() {}

    //? if >=26.2 {
    /*private static Boolean vulkan;
    *///?}

    /** Whether Minecraft renders through Vulkan. */
    public static boolean vulkan() {
        //? if >=26.2 {
        /*if (vulkan == null) vulkan = "Vulkan".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
        return vulkan;
        *///?} else {
        return false;
        //?}
    }
}
