package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.command.CgAccess;

import static org.lwjgl.vulkan.VK10.*;

/** A {@link CgAccess} as Vulkan's barriers take it: a pipeline stage, an access mask, and the layout an image needs. */
final class VulkanAccess {

    private VulkanAccess() {}

    static int stage(CgAccess a) {
        return switch (a) {
            case COMPUTE_READ, COMPUTE_WRITE -> VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case VERTEX_READ -> VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
            case FRAGMENT_READ -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case UNIFORM_READ, SAMPLED_READ -> VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case VERTEX_INPUT, INDEX_INPUT -> VK_PIPELINE_STAGE_VERTEX_INPUT_BIT;
            case INDIRECT -> VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
            case COPY_READ, COPY_WRITE -> VK_PIPELINE_STAGE_TRANSFER_BIT;
            case HOST_READ -> VK_PIPELINE_STAGE_HOST_BIT;
            case COLOR_WRITE -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        };
    }

    static int access(CgAccess a) {
        return switch (a) {
            case COMPUTE_READ, VERTEX_READ, FRAGMENT_READ, SAMPLED_READ -> VK_ACCESS_SHADER_READ_BIT;
            case COMPUTE_WRITE -> VK_ACCESS_SHADER_WRITE_BIT;
            case UNIFORM_READ -> VK_ACCESS_UNIFORM_READ_BIT;
            case VERTEX_INPUT -> VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT;
            case INDEX_INPUT -> VK_ACCESS_INDEX_READ_BIT;
            case INDIRECT -> VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
            case COPY_READ -> VK_ACCESS_TRANSFER_READ_BIT;
            case COPY_WRITE -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case HOST_READ -> VK_ACCESS_HOST_READ_BIT;
            case COLOR_WRITE -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        };
    }

    /** The layout an image is in for {@code a}: storage access is {@code GENERAL}, sampling read-only. */
    static int layout(CgAccess a) {
        return switch (a) {
            case COMPUTE_READ, COMPUTE_WRITE, VERTEX_READ, FRAGMENT_READ -> VK_IMAGE_LAYOUT_GENERAL;
            case COPY_READ -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case COPY_WRITE -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            case COLOR_WRITE -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            default -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        };
    }
}
