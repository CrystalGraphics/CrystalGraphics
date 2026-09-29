package com.crystalgraphics.vulkan.format;

import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.format.CgCompare;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.EnumMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Every device enum in Vulkan's terms. The depth formats are resolved per device: {@code DEPTH24_PLUS} is D24 where
 * the device renders it and 32-bit float where it does not (Apple GPUs).
 */
public final class VulkanFormats {

    private final Map<CgFormat, Integer> formats = new EnumMap<>(CgFormat.class);
    private final VkPhysicalDevice physical;

    public VulkanFormats(VkPhysicalDevice physical) {
        this.physical = physical;
        put(CgFormat.R8_UNORM, VK_FORMAT_R8_UNORM); put(CgFormat.R8_SNORM, VK_FORMAT_R8_SNORM);
        put(CgFormat.R8_UINT, VK_FORMAT_R8_UINT); put(CgFormat.R8_SINT, VK_FORMAT_R8_SINT);
        put(CgFormat.RG8_UNORM, VK_FORMAT_R8G8_UNORM); put(CgFormat.RG8_UINT, VK_FORMAT_R8G8_UINT);
        put(CgFormat.RG8_SINT, VK_FORMAT_R8G8_SINT);
        put(CgFormat.RGBA8_UNORM, VK_FORMAT_R8G8B8A8_UNORM); put(CgFormat.RGBA8_SNORM, VK_FORMAT_R8G8B8A8_SNORM);
        put(CgFormat.RGBA8_UINT, VK_FORMAT_R8G8B8A8_UINT); put(CgFormat.RGBA8_SINT, VK_FORMAT_R8G8B8A8_SINT);
        put(CgFormat.RGBA8_SRGB, VK_FORMAT_R8G8B8A8_SRGB); put(CgFormat.BGRA8_UNORM, VK_FORMAT_B8G8R8A8_UNORM);
        put(CgFormat.R16_FLOAT, VK_FORMAT_R16_SFLOAT); put(CgFormat.R16_UINT, VK_FORMAT_R16_UINT);
        put(CgFormat.R16_SINT, VK_FORMAT_R16_SINT);
        put(CgFormat.RG16_FLOAT, VK_FORMAT_R16G16_SFLOAT); put(CgFormat.RG16_UINT, VK_FORMAT_R16G16_UINT);
        put(CgFormat.RG16_SINT, VK_FORMAT_R16G16_SINT);
        put(CgFormat.RGBA16_FLOAT, VK_FORMAT_R16G16B16A16_SFLOAT); put(CgFormat.RGBA16_UINT, VK_FORMAT_R16G16B16A16_UINT);
        put(CgFormat.RGBA16_SINT, VK_FORMAT_R16G16B16A16_SINT);
        put(CgFormat.R32_FLOAT, VK_FORMAT_R32_SFLOAT); put(CgFormat.R32_UINT, VK_FORMAT_R32_UINT);
        put(CgFormat.R32_SINT, VK_FORMAT_R32_SINT);
        put(CgFormat.RG32_FLOAT, VK_FORMAT_R32G32_SFLOAT); put(CgFormat.RG32_UINT, VK_FORMAT_R32G32_UINT);
        put(CgFormat.RG32_SINT, VK_FORMAT_R32G32_SINT);
        put(CgFormat.RGBA32_FLOAT, VK_FORMAT_R32G32B32A32_SFLOAT); put(CgFormat.RGBA32_UINT, VK_FORMAT_R32G32B32A32_UINT);
        put(CgFormat.RGBA32_SINT, VK_FORMAT_R32G32B32A32_SINT);
        put(CgFormat.RGB10A2_UNORM, VK_FORMAT_A2B10G10R10_UNORM_PACK32);
        put(CgFormat.RGB10A2_UINT, VK_FORMAT_A2B10G10R10_UINT_PACK32);
        put(CgFormat.RG11B10_UFLOAT, VK_FORMAT_B10G11R11_UFLOAT_PACK32);
        put(CgFormat.RGBA4_UNORM, VK_FORMAT_R4G4B4A4_UNORM_PACK16); put(CgFormat.RGB5A1_UNORM, VK_FORMAT_R5G5B5A1_UNORM_PACK16);
        put(CgFormat.DEPTH16_UNORM, VK_FORMAT_D16_UNORM);
        put(CgFormat.DEPTH24_PLUS, renderable(VK_FORMAT_X8_D24_UNORM_PACK32) ? VK_FORMAT_X8_D24_UNORM_PACK32 : VK_FORMAT_D32_SFLOAT);
        put(CgFormat.DEPTH32_FLOAT, VK_FORMAT_D32_SFLOAT);
        put(CgFormat.DEPTH24_PLUS_STENCIL8, renderable(VK_FORMAT_D24_UNORM_S8_UINT) ? VK_FORMAT_D24_UNORM_S8_UINT
                : VK_FORMAT_D32_SFLOAT_S8_UINT);
        put(CgFormat.DEPTH32_FLOAT_STENCIL8, VK_FORMAT_D32_SFLOAT_S8_UINT);
        put(CgFormat.STENCIL8, renderable(VK_FORMAT_S8_UINT) ? VK_FORMAT_S8_UINT : VK_FORMAT_D24_UNORM_S8_UINT);
    }

    private void put(CgFormat f, int vk) {
        formats.put(f, vk);
    }

    public int vk(CgFormat format) {
        return formats.get(format);
    }

    /** {@code optimalTilingFeatures} for a format. */
    public int features(int vkFormat) {
        try (MemoryStack stack = stackPush()) {
            VkFormatProperties props = VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, vkFormat, props);
            return props.optimalTilingFeatures();
        }
    }

    private boolean renderable(int vkFormat) {
        return (features(vkFormat) & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0;
    }

    static int aspect(CgFormat f) {
        switch (f.aspect()) {
            case DEPTH: return VK_IMAGE_ASPECT_DEPTH_BIT;
            case STENCIL: return VK_IMAGE_ASPECT_STENCIL_BIT;
            case DEPTH_STENCIL: return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            default: return VK_IMAGE_ASPECT_COLOR_BIT;
        }
    }

    /** The aspect a resolved Vulkan format carries: {@code STENCIL8} may resolve to depth-stencil. */
    public int aspectOf(CgFormat f) {
        int vk = vk(f);
        if (vk == VK_FORMAT_D24_UNORM_S8_UINT || vk == VK_FORMAT_D32_SFLOAT_S8_UINT)
            return VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
        return aspect(f);
    }

    public static int attrib(CgAttribFormat f) {
        switch (f) {
            case FLOAT32: return VK_FORMAT_R32_SFLOAT;
            case FLOAT32X2: return VK_FORMAT_R32G32_SFLOAT;
            case FLOAT32X3: return VK_FORMAT_R32G32B32_SFLOAT;
            case FLOAT32X4: return VK_FORMAT_R32G32B32A32_SFLOAT;
            case FLOAT16X2: return VK_FORMAT_R16G16_SFLOAT;
            case FLOAT16X4: return VK_FORMAT_R16G16B16A16_SFLOAT;
            case UNORM8X2: return VK_FORMAT_R8G8_UNORM;
            case UNORM8X4: return VK_FORMAT_R8G8B8A8_UNORM;
            case SNORM8X2: return VK_FORMAT_R8G8_SNORM;
            case SNORM8X4: return VK_FORMAT_R8G8B8A8_SNORM;
            case USCALED8X2: return VK_FORMAT_R8G8_USCALED;
            case USCALED8X4: return VK_FORMAT_R8G8B8A8_USCALED;
            case SSCALED8X2: return VK_FORMAT_R8G8_SSCALED;
            case SSCALED8X4: return VK_FORMAT_R8G8B8A8_SSCALED;
            case UINT8X2: return VK_FORMAT_R8G8_UINT;
            case UINT8X4: return VK_FORMAT_R8G8B8A8_UINT;
            case SINT8X2: return VK_FORMAT_R8G8_SINT;
            case SINT8X4: return VK_FORMAT_R8G8B8A8_SINT;
            case UNORM16X2: return VK_FORMAT_R16G16_UNORM;
            case UNORM16X4: return VK_FORMAT_R16G16B16A16_UNORM;
            case SNORM16X2: return VK_FORMAT_R16G16_SNORM;
            case SNORM16X4: return VK_FORMAT_R16G16B16A16_SNORM;
            case USCALED16X2: return VK_FORMAT_R16G16_USCALED;
            case USCALED16X4: return VK_FORMAT_R16G16B16A16_USCALED;
            case SSCALED16X2: return VK_FORMAT_R16G16_SSCALED;
            case SSCALED16X4: return VK_FORMAT_R16G16B16A16_SSCALED;
            case UINT16X2: return VK_FORMAT_R16G16_UINT;
            case UINT16X4: return VK_FORMAT_R16G16B16A16_UINT;
            case SINT16X2: return VK_FORMAT_R16G16_SINT;
            case SINT16X4: return VK_FORMAT_R16G16B16A16_SINT;
            case UINT32: return VK_FORMAT_R32_UINT;
            case UINT32X2: return VK_FORMAT_R32G32_UINT;
            case UINT32X3: return VK_FORMAT_R32G32B32_UINT;
            case UINT32X4: return VK_FORMAT_R32G32B32A32_UINT;
            case SINT32: return VK_FORMAT_R32_SINT;
            case SINT32X2: return VK_FORMAT_R32G32_SINT;
            case SINT32X3: return VK_FORMAT_R32G32B32_SINT;
            case SINT32X4: return VK_FORMAT_R32G32B32A32_SINT;
            default: throw new IllegalArgumentException(f.name());
        }
    }

    public static int compare(CgCompare c) {
        switch (c) {
            case NEVER: return VK_COMPARE_OP_NEVER;
            case LESS: return VK_COMPARE_OP_LESS;
            case EQUAL: return VK_COMPARE_OP_EQUAL;
            case LESS_EQUAL: return VK_COMPARE_OP_LESS_OR_EQUAL;
            case GREATER: return VK_COMPARE_OP_GREATER;
            case NOT_EQUAL: return VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_EQUAL: return VK_COMPARE_OP_GREATER_OR_EQUAL;
            default: return VK_COMPARE_OP_ALWAYS;
        }
    }

    public static int stencilOp(CgPipelineDesc.StencilOp op) {
        switch (op) {
            case ZERO: return VK_STENCIL_OP_ZERO;
            case REPLACE: return VK_STENCIL_OP_REPLACE;
            case INCREMENT_CLAMP: return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
            case DECREMENT_CLAMP: return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
            case INVERT: return VK_STENCIL_OP_INVERT;
            case INCREMENT_WRAP: return VK_STENCIL_OP_INCREMENT_AND_WRAP;
            case DECREMENT_WRAP: return VK_STENCIL_OP_DECREMENT_AND_WRAP;
            default: return VK_STENCIL_OP_KEEP;
        }
    }

    public static int blendFactor(CgPipelineDesc.BlendFactor f) {
        switch (f) {
            case ZERO: return VK_BLEND_FACTOR_ZERO;
            case ONE: return VK_BLEND_FACTOR_ONE;
            case SRC_COLOR: return VK_BLEND_FACTOR_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case DST_COLOR: return VK_BLEND_FACTOR_DST_COLOR;
            case ONE_MINUS_DST_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA: return VK_BLEND_FACTOR_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA: return VK_BLEND_FACTOR_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR: return VK_BLEND_FACTOR_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA: return VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            default: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        }
    }

    public static int blendOp(CgPipelineDesc.BlendOp op) {
        switch (op) {
            case SUBTRACT: return VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT: return VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN: return VK_BLEND_OP_MIN;
            case MAX: return VK_BLEND_OP_MAX;
            default: return VK_BLEND_OP_ADD;
        }
    }

    public static int topology(CgPipelineDesc.Topology t) {
        switch (t) {
            case POINTS: return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case LINES: return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case LINE_STRIP: return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLE_STRIP: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            default: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        }
    }

    public static int cull(CgPipelineDesc.CullMode c) {
        switch (c) {
            case FRONT: return VK_CULL_MODE_FRONT_BIT;
            case BACK: return VK_CULL_MODE_BACK_BIT;
            case FRONT_AND_BACK: return VK_CULL_MODE_FRONT_AND_BACK;
            default: return VK_CULL_MODE_NONE;
        }
    }

    public static int polygonMode(CgPipelineDesc.PolygonMode m) {
        switch (m) {
            case LINE: return VK_POLYGON_MODE_LINE;
            case POINT: return VK_POLYGON_MODE_POINT;
            default: return VK_POLYGON_MODE_FILL;
        }
    }

    public static int filter(CgGpuSampler.Filter f) {
        return f == CgGpuSampler.Filter.LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
    }

    public static int wrap(CgGpuSampler.Wrap w) {
        switch (w) {
            case MIRRORED_REPEAT: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case CLAMP_TO_EDGE: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            default: return VK_SAMPLER_ADDRESS_MODE_REPEAT;
        }
    }
}
