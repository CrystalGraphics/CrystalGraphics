/**
 * {@code CgVulkanDevice}'s host-visible memory: the staging chunks uploads and readbacks pass through, and the cut of
 * an upload too large for one chunk.
 *
 * <p>Public for the device and its command package, and not an API.
 */
package com.crystalgraphics.vulkan.memory;
