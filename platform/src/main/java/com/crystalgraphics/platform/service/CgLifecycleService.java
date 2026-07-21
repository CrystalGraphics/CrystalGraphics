package com.crystalgraphics.platform.service;

/**
 * Platform abstraction for GL context lifecycle events. The platform implementation
 * calls these methods directly on the GL thread — no callback registration needed.
 *
 * <p>All methods are invoked on the GL thread.</p>
 */
public interface CgLifecycleService {
    /** Called by the platform on the GL thread once the context is created and sized. */
    void onContextInit(int width, int height);
    /** Called by the platform on the GL thread before the context is destroyed. */
    void onContextDestroy();
    /** Called by the platform on the GL thread when the viewport dimensions change. */
    void onResize(int width, int height);

    /**
     * Called by the platform on the GL thread once per fully-rendered frame — world
     * frame or GUI-only frame alike (e.g. a menu screen with no world loaded). This is
     * the canonical "a frame was just displayed" signal implementations should hook to
     * their loader's most general per-frame point (for MC 1.7.10: the tail of
     * {@code Minecraft.runGameLoop()}, which covers both cases uniformly).
     *
     * <p>Implementations should delegate to
     * {@code com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle.tickFrame()} — this is
     * the only place per-frame engine bookkeeping (currently {@code CgFontRegistry}'s
     * frame clock) should be triggered from. Feature-level code
     * ({@code CgUiPaintContext}, demo overlays, etc.) must not call
     * {@code CgGraphicsLifecycle.tickFrame()} directly — that scatters responsibility
     * for "when does a frame tick happen" across unrelated classes instead of keeping
     * it in this one platform-abstraction seam.</p>
     */
    void onFrameRendered();
}
