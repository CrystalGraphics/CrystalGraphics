package com.crystalgraphics.mc.modern.platform.vulkan;

//? if >=26.2 <26.3 {
/*import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import org.apache.logging.log4j.LogManager;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
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
 * boolean heap = MinecraftDeviceFeatures.bindless();
 * }</pre>
 *
 * <ul>
 *   <li>{@code independentBlend}, where the GPU has it: a pipeline's attachments may differ in write mask, which the
 *       world's normal target beside the scene needs ({@code CgCapabilities.independentBlend()}).</li>
 *   <li>{@code drawIndirectFirstInstance}, where the GPU has it: with Minecraft's own {@code multiDrawIndirect} and
 *       {@code shaderDrawParameters}, what joins a run of draws into one call ({@code CgCapabilities.multiDraw()}).</li>
 *   <li>{@code drawIndirectCount}, where the GPU has it: draws counted on the GPU.</li>
 *   <li>Descriptor indexing, all four features or none, as the owned device: {@code runtimeDescriptorArray},
 *       {@code descriptorBindingPartiallyBound}, {@code …SampledImageUpdateAfterBind} and
 *       {@code …UpdateUnusedWhilePending}, the bindless texture heap.</li>
 *   <li>{@code textureCompressionBC}, where the GPU has it: cooked BC7 textures uploaded as they are, rather than
 *       decoded ({@code CgCapabilities.bc7()}).</li>
 *   <li>Nothing is required: a GPU without a feature gets Minecraft's set unchanged, never refused.</li>
 *   <li>False until Minecraft creates its device, and on every version but 26.2.</li>
 * </ul>
 */
public final class MinecraftDeviceFeatures {

    private static volatile boolean independentBlend, multiDrawIndirect, drawParameters, indirectFirstInstance,
            indirectCount, bindless, textureCompressionBC;

    private MinecraftDeviceFeatures() {
    }

    /** Whether Minecraft's Vulkan device was created with {@code independentBlend}. */
    public static boolean independentBlend() {
        return independentBlend;
    }

    /** Whether Minecraft's Vulkan device was created with {@code textureCompressionBC}: cooked BC7 textures. */
    public static boolean textureCompressionBC() {
        return textureCompressionBC;
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

    /** Whether Minecraft's Vulkan device was created with {@code drawIndirectCount}. */
    public static boolean indirectCount() {
        return indirectCount;
    }

    /** Whether Minecraft's Vulkan device was created with the four descriptor-indexing features of the texture heap. */
    public static boolean bindless() {
        return bindless;
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
            VkPhysicalDeviceVulkan12Features has12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 has = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(has12.address());
            vkGetPhysicalDeviceFeatures2(physical.vkPhysicalDevice(), has);
            VkPhysicalDeviceFeatures supported = has.features();
            if (supported.independentBlend()) {
                more.add(vk10("independentBlend", VkPhysicalDeviceFeatures.INDEPENDENTBLEND));
                independentBlend = true;
            }
            if (supported.drawIndirectFirstInstance()) {
                more.add(vk10("drawIndirectFirstInstance", VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE));
                indirectFirstInstance = true;
            }
            if (supported.textureCompressionBC()) {
                more.add(vk10("textureCompressionBC", VkPhysicalDeviceFeatures.TEXTURECOMPRESSIONBC));
                textureCompressionBC = true;
            }
            if (has12.drawIndirectCount()) {
                more.add(vk12("drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT));
                indirectCount = true;
            }
            if (has12.runtimeDescriptorArray() && has12.descriptorBindingPartiallyBound()
                    && has12.descriptorBindingSampledImageUpdateAfterBind() && has12.descriptorBindingUpdateUnusedWhilePending()) {
                more.add(vk12("runtimeDescriptorArray", VkPhysicalDeviceVulkan12Features.RUNTIMEDESCRIPTORARRAY));
                more.add(vk12("descriptorBindingPartiallyBound", VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGPARTIALLYBOUND));
                more.add(vk12("descriptorBindingSampledImageUpdateAfterBind",
                        VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGSAMPLEDIMAGEUPDATEAFTERBIND));
                more.add(vk12("descriptorBindingUpdateUnusedWhilePending",
                        VkPhysicalDeviceVulkan12Features.DESCRIPTORBINDINGUPDATEUNUSEDWHILEPENDING));
                bindless = true;
            }
        }
        Set<String> added = new TreeSet<>();
        for (VulkanFeature f : more) if (!features.contains(f)) added.add(f.name());
        LogManager.getLogger("CrystalGraphics").info("[cg] Minecraft's Vulkan device is created with ours too: {}",
                added.isEmpty() ? "none the GPU has" : String.join(", ", added));
        return more;
    }

    private static VulkanFeature vk10(String name, long offset) {
        return new VulkanFeature(VulkanBackend.VK10_FEATURES_STRUCT, name, offset);
    }

    private static VulkanFeature vk12(String name, long offset) {
        return new VulkanFeature(VulkanBackend.VK12_FEATURES_STRUCT, name, offset);
    }
    *///?}
}
