package com.crystalgraphics.platform;

/**
 * Platform abstraction for frame-level rendering integration. Provides access to
 * viewport dimensions and allows registering per-frame callbacks that fire before
 * the CrystalGraphics render pass executes.
 *
 * <p>All rendering hooks are consolidated here: registering a frame callback is
 * the correct way for the core engine to hook into the per-frame draw cycle.</p>
 */
public interface CgRenderingService {
    /**
     * Register a callback to be fired each frame before the CrystalGraphics render pass.
     * Callbacks fire in registration order on the GL thread.
     *
     * @param callback the frame callback to register; must not be {@code null}
     */
    void registerFrameCallback(CgFrameCallback callback);

    /**
     * Returns the current viewport width in pixels.
     *
     * @return viewport width in pixels
     */
    int getViewportWidth();

    /**
     * Returns the current viewport height in pixels.
     *
     * @return viewport height in pixels
     */
    int getViewportHeight();
}
