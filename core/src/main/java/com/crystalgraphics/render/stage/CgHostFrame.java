package com.crystalgraphics.render.stage;

/**
 * What a host knows about its frame when it fires a stage.
 *
 * <pre>{@code
 * CgRenderStage.WORLD_OPAQUE.fire(
 *         new CgHostFrame(partialTick, window.getWidth(), window.getHeight(), mainFramebufferId));
 * }</pre>
 *
 * @param partialTick     the frame's interpolation between game ticks, 0 to 1
 * @param width           the host's main target, in pixels
 * @param height          the host's main target, in pixels
 * @param mainFramebuffer the GL framebuffer the host draws its world into, which holds the scene's depth
 */
public record CgHostFrame(float partialTick, int width, int height, int mainFramebuffer) {
}
