package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=26.2 <26.3 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.MinecraftDeviceFeatures;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Collection;
import java.util.Set;

// Our device features beside Minecraft 26.2's, where it creates its Vulkan device: no event reaches device creation.
@Mixin(value = VulkanBackend.class, remap = false)
public abstract class DeviceFeaturesHook {

    @ModifyArg(method = "createDevice(JLcom/mojang/blaze3d/shaders/ShaderSource;Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)Lcom/mojang/blaze3d/systems/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;"),
            index = 2, require = 1)
    private Set<VulkanFeature> crystalgraphics$features(Collection<String> extensions, VulkanPhysicalDevice physical,
                                                       Set<VulkanFeature> features) {
        return MinecraftDeviceFeatures.addTo(features, physical);
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty on the nodes that pin this plugin and have no Minecraft 26.2 Vulkan device.
@Mixin(value = Camera.class, remap = false)
public abstract class DeviceFeaturesHook {
}
//?}
