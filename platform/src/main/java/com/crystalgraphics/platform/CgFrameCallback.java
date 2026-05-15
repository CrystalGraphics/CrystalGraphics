package com.crystalgraphics.platform;

/**
 * Callback fired at the start of each rendered frame, before the CrystalGraphics
 * render pass executes. Registered via {@link CgRenderingService#registerFrameCallback}.
 */
@FunctionalInterface
public interface CgFrameCallback {
    /**
     * Called once per frame, immediately before CrystalGraphics draws its geometry.
     *
     * @param partialTick interpolation factor between the last two game ticks, in [0.0, 1.0]
     */
    void onFrameBegin(float partialTick);
}
