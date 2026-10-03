package com.crystalgraphics.platform.device;

/**
 * What a device is and what it can do: the answers the tracked backend gives to GL's {@code glGetString} and
 * {@code glGetInteger} limits.
 *
 * @param timestamps            timer queries return results
 * @param nonSolidFill          a polygon mode of lines or points
 * @param indirectCount         a draw's count may come from a buffer
 * @param indirectFirstInstance an indirect command's first instance may be other than 0
 */
public record CgDeviceInfo(String name, String vendor, String driver, Limits limits,
                           boolean timestamps, boolean anisotropy, boolean nonSolidFill,
                           boolean indirectCount, boolean indirectFirstInstance) {

    public record Limits(int maxTextureSize, int max3DTextureSize, int maxArrayLayers, int maxColorAttachments,
                         int maxSamples, int maxVertexAttributes, int maxTextureUnits, int maxUniformBlockSize,
                         int maxUniformBufferBindings, int maxStorageBufferBindings, int maxTexelBufferElements,
                         int uniformOffsetAlignment, int storageOffsetAlignment, int texelOffsetAlignment,
                         int maxViewportSize, float maxAnisotropy) {}
}
