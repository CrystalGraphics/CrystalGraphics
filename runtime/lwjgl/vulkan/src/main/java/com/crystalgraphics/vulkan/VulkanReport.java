package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.gl.CgGpuReport;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderAtomicFloatFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupSizeControlProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_PATCH;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * {@link CgVulkanDevice#describe}: what the physical device supports, under {@link CgGpuReport}'s keys. On a hosted
 * device the host chose what is enabled, so {@code host=hosted} reads as "supported, not necessarily enabled".
 */
final class VulkanReport {

    private VulkanReport() {}

    static void describe(CgVulkanHost host, BiConsumer<String, String> fact) {
        VkPhysicalDevice pd = host.physicalDevice();
        try (MemoryStack stack = stackPush()) {
            Set<String> listed = extensions(stack, pd);

            VkPhysicalDeviceProperties basic = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(pd, basic);
            int api = basic.apiVersion();
            VkPhysicalDeviceSubgroupSizeControlProperties sizes = null;
            if (api >= VK_API_VERSION_1_3 || listed.contains("VK_EXT_subgroup_size_control")) {
                sizes = VkPhysicalDeviceSubgroupSizeControlProperties.calloc(stack).sType$Default();
            }
            VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default()
                    .pNext(sizes == null ? NULL : sizes.address());
            VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                    .sType$Default().pNext(driver.address());
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(subgroup.address());
            vkGetPhysicalDeviceProperties2(pd, props);

            VkPhysicalDeviceVulkan12Features v12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features v11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default()
                    .pNext(v12.address());
            VkPhysicalDeviceShaderAtomicFloatFeaturesEXT atomics = null;
            if (listed.contains("VK_EXT_shader_atomic_float")) {
                atomics = VkPhysicalDeviceShaderAtomicFloatFeaturesEXT.calloc(stack).sType$Default();
                v12.pNext(atomics.address());
            }
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default()
                    .pNext(v11.address());
            vkGetPhysicalDeviceFeatures2(pd, features2);
            VkPhysicalDeviceFeatures features = features2.features();
            VkPhysicalDeviceLimits l = props.properties().limits();

            fact.accept("api", "vulkan");
            fact.accept("version", VK_API_VERSION_MAJOR(api) + "." + VK_API_VERSION_MINOR(api) + "." + VK_API_VERSION_PATCH(api));
            fact.accept("renderer", props.properties().deviceNameString());
            fact.accept("vendor", vendor(props.properties().vendorID()));
            fact.accept("driver", driver.driverNameString() + " " + driver.driverInfoString());
            fact.accept("host", host.ownsSubmission() ? "owned" : "hosted");

            fact.accept("compute", "core");
            fact.accept("storage", "core");
            fact.accept("images", "core");
            boolean computeSubgroups = (subgroup.supportedStages() & VK_SHADER_STAGE_COMPUTE_BIT) != 0;
            fact.accept("subgroups", computeSubgroups ? "core" : "no");
            fact.accept("floatAtomics", atomics != null && atomics.shaderBufferFloat32AtomicAdd() ? "EXT_shader_atomic_float" : "no");
            fact.accept("drawIndirect", "core");
            fact.accept("multiDrawIndirect", features.multiDrawIndirect() ? "core" : "no");
            fact.accept("indirectCount", v12.drawIndirectCount() ? "core" : "no");
            fact.accept("baseInstance", features.drawIndirectFirstInstance() ? "core" : "no");
            fact.accept("drawParameters", v11.shaderDrawParameters() ? "core" : "no");
            fact.accept("bindless", v12.descriptorIndexing() && v12.runtimeDescriptorArray() ? "core" : "no");

            fact.accept("computeInvocations", Integer.toString(l.maxComputeWorkGroupInvocations()));
            fact.accept("computeGroupSize", dimensions(l.maxComputeWorkGroupSize()));
            fact.accept("computeGroupCount", dimensions(l.maxComputeWorkGroupCount()));
            fact.accept("computeSharedMemory", Integer.toString(l.maxComputeSharedMemorySize()));
            fact.accept("storageBlockSize", Integer.toUnsignedString(l.maxStorageBufferRange()));
            fact.accept("storageBindings", Integer.toString(l.maxPerStageDescriptorStorageBuffers()));
            fact.accept("imageUnits", Integer.toString(l.maxPerStageDescriptorStorageImages()));
            fact.accept("subgroupSize", Integer.toString(subgroup.subgroupSize()));
            fact.accept("subgroupStages", CgGpuReport.bitNames(subgroup.supportedStages(),
                    "vertex", "tessControl", "tessEval", "geometry", "fragment", "compute"));
            fact.accept("subgroupOps", CgGpuReport.subgroupOps(subgroup.supportedOperations()));
            if (sizes != null) {
                fact.accept("subgroupSizeRange", sizes.minSubgroupSize() + "-" + sizes.maxSubgroupSize());
            }
            fact.accept("texelBuffer", Integer.toUnsignedString(l.maxTexelBufferElements()));
            fact.accept("textureSize", Integer.toString(l.maxImageDimension2D()));
            fact.accept("queues", queues(stack, pd));
        }
    }

    private static Set<String> extensions(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, null);
        VkExtensionProperties.Buffer all = VkExtensionProperties.malloc(n.get(0), stack);
        vkEnumerateDeviceExtensionProperties(pd, (String) null, n, all);
        Set<String> names = new HashSet<>();
        for (VkExtensionProperties e : all) names.add(e.extensionNameString());
        return names;
    }

    /** Each family as {@code index:graphics+compute+transfer(count)}. */
    private static String queues(MemoryStack stack, VkPhysicalDevice pd) {
        IntBuffer n = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(n.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, n, families);
        StringJoiner out = new StringJoiner(",");
        for (int i = 0; i < families.capacity(); i++) {
            VkQueueFamilyProperties f = families.get(i);
            int flags = f.queueFlags() & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT | VK_QUEUE_TRANSFER_BIT);
            out.add(i + ":" + CgGpuReport.bitNames(flags, "graphics", "compute", "transfer").replace(',', '+')
                    + "(" + f.queueCount() + ")");
        }
        return out.toString();
    }

    private static String dimensions(IntBuffer xyz) {
        return Integer.toUnsignedString(xyz.get(0)) + "x" + Integer.toUnsignedString(xyz.get(1)) + "x"
                + Integer.toUnsignedString(xyz.get(2));
    }

    private static String vendor(int id) {
        return switch (id) {
            case 0x10DE -> "NVIDIA";
            case 0x1002 -> "AMD";
            case 0x8086 -> "Intel";
            case 0x106B -> "Apple";
            case 0x13B5 -> "ARM";
            case 0x5143 -> "Qualcomm";
            default -> "0x" + Integer.toHexString(id);
        };
    }
}
