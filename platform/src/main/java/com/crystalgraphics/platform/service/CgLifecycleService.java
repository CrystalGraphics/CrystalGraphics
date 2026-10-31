package com.crystalgraphics.platform.service;

/**
 * A host's graphics-context moments, on the GL thread. Destroy goes to the engine's lifecycle, {@code CgEngineLifecycle};
 * resize and the frame to {@code CgGraphicsLifecycle}. The context starts from {@code CgEngineLifecycle.ensureGraphicsContext},
 * which a host calls at each frame's end.
 *
 * <pre>{@code
 * @Override public void onContextDestroy()          { CgEngineLifecycle.destroyContext(); }
 * @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
 * @Override public void onFrameRendered()           { CgGraphicsLifecycle.tickFrame(); }
 * }</pre>
 */
public interface CgLifecycleService {
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
     * {@code CgGraphicsLifecycle.tickFrame()} — this is
     * the only place per-frame engine bookkeeping (currently {@code CgFontRegistry}'s
     * frame clock) should be triggered from. Feature-level code
     * ({@code CgUiPaintContext}, demo overlays, etc.) must not call
     * {@code CgGraphicsLifecycle.tickFrame()} directly — that scatters responsibility
     * for "when does a frame tick happen" across unrelated classes instead of keeping
     * it in this one platform-abstraction seam.</p>
     */
    void onFrameRendered();
}
