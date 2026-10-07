package com.crystalgraphics.platform.device;

/**
 * What a device is and what it can do: the answers the tracked backend gives to GL's {@code glGetString} and
 * {@code glGetInteger} limits.
 *
 * @param timestamps            timer queries return results
 * @param nonSolidFill          a polygon mode of lines or points
 * @param multiDrawIndirect     an indirect draw may take more than one command
 * @param indirectCount         a draw's count may come from a buffer
 * @param indirectFirstInstance an indirect command's first instance may be other than 0
 * @param asyncCompute          a compute queue beside the frame's, so async work overlaps the frame's other work
 * @param drawParameters        {@code gl_DrawID} and a draw's base vertex and instance in a shader
 * @param independentBlend      a pipeline's colour attachments may differ in blend and write mask
 */
public record CgDeviceInfo(String name, String vendor, String driver, Limits limits,
                           boolean timestamps, boolean anisotropy, boolean nonSolidFill,
                           boolean multiDrawIndirect, boolean indirectCount, boolean indirectFirstInstance,
                           boolean asyncCompute, boolean drawParameters, boolean independentBlend) {

    public record Limits(int maxTextureSize, int max3DTextureSize, int maxArrayLayers, int maxColorAttachments,
                         int maxSamples, int maxVertexAttributes, int maxTextureUnits, int maxUniformBlockSize,
                         int maxUniformBufferBindings, int maxStorageBufferBindings, int maxTexelBufferElements,
                         int uniformOffsetAlignment, int storageOffsetAlignment, int texelOffsetAlignment,
                         int maxViewportSize, float maxAnisotropy, Compute compute) {}

    /**
     * What a kernel may ask for: shared memory in bytes, invocations in a work group, the work group's size and a
     * dispatch's group count per axis, and the subgroup operations a compute stage has.
     *
     * @param subgroupOperations {@code VkSubgroupFeatureFlags} for the compute stage, the same bits as
     *                           {@code GL_SUBGROUP_SUPPORTED_FEATURES_KHR}; 0 where compute has none
     */
    public record Compute(int sharedMemory, int invocations, int sizeX, int sizeY, int sizeZ, int countX, int countY,
                          int countZ, int subgroupSize, int subgroupOperations) {

        /** Vulkan's guaranteed minimums. */
        public static final Compute MINIMUM = new Compute(16384, 128, 128, 128, 64, 65535, 65535, 65535, 1, 0x1);
    }
}
