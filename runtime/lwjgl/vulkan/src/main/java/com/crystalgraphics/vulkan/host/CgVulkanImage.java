package com.crystalgraphics.vulkan.host;

/** An image a host is handed: its handle, {@code VkFormat} and size. */
public record CgVulkanImage(long image, int format, int width, int height) {}
