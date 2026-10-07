package com.crystalgraphics.mc.modern.platform.vulkan;

//? if >=26.2 <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;
*///?}

/**
 * The device features CrystalGraphics asks of Minecraft 26.2's Vulkan device beside Minecraft's own, and what it got.
 * Each loader's {@code DeviceFeaturesHook} hands it the set Minecraft creates its device with.
 *
 * <pre>{@code
 * // the hook, at VulkanBackend.createDevice's call that creates the VkDevice:
 * return MinecraftDeviceFeatures.addTo(features, physicalDevice);
 *
 * // later, the hosted device:
 * boolean masksApart = MinecraftDeviceFeatures.independentBlend();
 * }</pre>
 *
 * <ul>
 *   <li>{@code independentBlend}, where the GPU has it: a pipeline's attachments may differ in write mask, which the
 *       world's merged emission needs ({@code CgCapabilities.independentBlend()}).</li>
 *   <li>Nothing is required: a GPU without a feature gets Minecraft's set unchanged, never refused.</li>
 *   <li>False until Minecraft creates its device, and on every version but 26.2.</li>
 * </ul>
 */
public final class MinecraftDeviceFeatures {

    private static volatile boolean independentBlend;

    private MinecraftDeviceFeatures() {
    }

    /** Whether Minecraft's Vulkan device was created with {@code independentBlend}. */
    public static boolean independentBlend() {
        return independentBlend;
    }

    //? if >=26.2 <26.3 {
    /*/^* Minecraft's features for its device, plus those of ours the GPU has. ^/
    public static Set<VulkanFeature> addTo(Set<VulkanFeature> features, VulkanPhysicalDevice physical) {
        boolean has;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(physical.vkPhysicalDevice(), supported);
            has = supported.independentBlend();
        }
        if (!has) return features;
        Set<VulkanFeature> more = new HashSet<>(features);
        more.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "independentBlend",
                VkPhysicalDeviceFeatures.INDEPENDENTBLEND));
        independentBlend = true;
        return more;
    }
    *///?}
}
