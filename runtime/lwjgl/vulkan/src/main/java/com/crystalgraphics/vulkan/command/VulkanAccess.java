package com.crystalgraphics.vulkan.command;

import com.crystalgraphics.platform.rhi.command.CgAccess;
import org.lwjgl.vulkan.KHRSwapchain;

import static org.lwjgl.vulkan.VK10.*;

/** {@link CgAccess} bits as Vulkan's barriers take them: pipeline stages, access masks, and the layout an image needs. */
final class VulkanAccess {

    private static final int SHADERS = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;

    private static final int DEPTH_TESTS = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;

    /** By bit, in {@link CgAccess}'s order. */
    private static final int[] STAGES = {
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, SHADERS, SHADERS,
            VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, DEPTH_TESTS, DEPTH_TESTS, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT};
    private static final int[] ACCESSES = {
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_ACCESS_UNIFORM_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT,
            VK_ACCESS_INDEX_READ_BIT, VK_ACCESS_INDIRECT_COMMAND_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT,
            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT, 0};

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

    /** The layout {@code access} needs ({@link CgAccess#layout}); {@code resting} for {@link CgAccess#RESTING} and none. */
    static int layout(int access, int resting) {
        CgAccess.Layout l = CgAccess.layout(access);
        return l == null ? resting : vk(l);
    }

    static int layout(int access) {
        return layout(access, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static int vk(CgAccess.Layout layout) {
        return switch (layout) {
            case GENERAL -> VK_IMAGE_LAYOUT_GENERAL;
            case SHADER_READ -> VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case COLOR -> VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH_READ -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
            case DEPTH -> VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            case COPY_SRC -> VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case COPY_DST -> VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            case PRESENT -> KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        };
    }
}
