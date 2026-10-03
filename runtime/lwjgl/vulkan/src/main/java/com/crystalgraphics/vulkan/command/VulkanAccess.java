package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.device.command.CgAccess;

import static org.lwjgl.vulkan.VK10.*;

/** {@link CgAccess} bits as Vulkan's barriers take them: pipeline stages, access masks, and the layout an image needs. */
final class VulkanAccess {

    private static final int SHADERS = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

    /** By bit, in {@link CgAccess}'s order. */
    private static final int[] STAGES = {
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, SHADERS, SHADERS,
            VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    private static final int[] ACCESSES = {
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_ACCESS_UNIFORM_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT,
            VK_ACCESS_INDEX_READ_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT};

    private VulkanAccess() {}

    /** Top of pipe for none: nothing to wait on. */
    static int stage(int access) {
        int stages = 0;
        for (int i = 0; i < STAGES.length; i++) if ((access & (1 << i)) != 0) stages |= STAGES[i];
        return stages == 0 ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : stages;
    }

    static int access(int access) {
        int mask = 0;
        for (int i = 0; i < ACCESSES.length; i++) if ((access & (1 << i)) != 0) mask |= ACCESSES[i];
        return mask;
    }

    /** Storage access is {@code GENERAL}, a copy or a target its own, sampling read-only; uses wanting two are {@code GENERAL}. */
    static int layout(int access) {
        if ((access & CgAccess.STORAGE) != 0) return VK_IMAGE_LAYOUT_GENERAL;
        int layout = -1;
        for (int i = 0; i < STAGES.length; i++) {
            if ((access & (1 << i)) == 0) continue;
            int l = layoutOf(1 << i);
            if (layout >= 0 && l != layout) return VK_IMAGE_LAYOUT_GENERAL;
            layout = l;
        }
        return layout < 0 ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : layout;
    }

    private static int layoutOf(int bit) {
        return switch (bit) {
            case CgAccess.COPY_READ -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case CgAccess.COPY_WRITE -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            case CgAccess.COLOR_WRITE -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            default -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        };
    }
}
