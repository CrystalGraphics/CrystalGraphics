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
 * boolean joins = MinecraftDeviceFeatures.multiDrawIndirect() && MinecraftDeviceFeatures.drawParameters()
 *         && MinecraftDeviceFeatures.indirectFirstInstance();
 * }</pre>
 *
 * <ul>
 *   <li>{@code independentBlend}, where the GPU has it: a pipeline's attachments may differ in write mask, which the
 *       world's merged emission needs ({@code CgCapabilities.independentBlend()}).</li>
 *   <li>{@code drawIndirectFirstInstance}, where the GPU has it: with Minecraft's own {@code multiDrawIndirect} and
 *       {@code shaderDrawParameters}, what joins a run of draws into one call ({@code CgCapabilities.multiDraw()}).</li>
 *   <li>Nothing is required: a GPU without a feature gets Minecraft's set unchanged, never refused.</li>
 *   <li>False until Minecraft creates its device, and on every version but 26.2.</li>
 * </ul>
 */
public final class MinecraftDeviceFeatures {

    private static volatile boolean independentBlend, multiDrawIndirect, drawParameters, indirectFirstInstance;

    private MinecraftDeviceFeatures() {
    }

    /** Whether Minecraft's Vulkan device was created with {@code independentBlend}. */
    public static boolean independentBlend() {
        return independentBlend;
    }

    /** Whether Minecraft's Vulkan device was created with {@code multiDrawIndirect}. */
    public static boolean multiDrawIndirect() {
        return multiDrawIndirect;
    }

    /** Whether Minecraft's Vulkan device was created with {@code shaderDrawParameters}. */
    public static boolean drawParameters() {
        return drawParameters;
    }

    /** Whether Minecraft's Vulkan device was created with {@code drawIndirectFirstInstance}. */
    public static boolean indirectFirstInstance() {
        return indirectFirstInstance;
    }

    //? if >=26.2 <26.3 {
    /*/^* Minecraft's features for its device, plus those of ours the GPU has. ^/
    public static Set<VulkanFeature> addTo(Set<VulkanFeature> features, VulkanPhysicalDevice physical) {
        Set<VulkanFeature> more = new HashSet<>(features);
        for (VulkanFeature f : features) {
            if (f.name().equals("multiDrawIndirect")) multiDrawIndirect = true;
            if (f.name().equals("shaderDrawParameters")) drawParameters = true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(physical.vkPhysicalDevice(), supported);
            if (supported.independentBlend()) {
                more.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "independentBlend",
                        VkPhysicalDeviceFeatures.INDEPENDENTBLEND));
                independentBlend = true;
            }
            if (supported.drawIndirectFirstInstance()) {
                more.add(new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, "drawIndirectFirstInstance",
                        VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE));
                indirectFirstInstance = true;
            }
        }
        return more;
    }
    *///?}
}
