package com.crystalgraphics.platform.service;

/**
 * Platform abstraction for frame-level rendering integration. The platform implementation
 * calls {@link #onFrameBegin} directly each frame — no callback registration needed.
 *
 * <p>Viewport dimensions are also provided here for use by the core engine.</p>
 */
public interface CgRenderingService {
    /** Called by the platform once per frame before the CG render pass executes. */
    void onFrameBegin(float partialTick);
    /** Returns the current viewport width in pixels. */
    int getDisplayWidth();
    /** Returns the current viewport height in pixels. */
    int getDisplayHeight();
}
