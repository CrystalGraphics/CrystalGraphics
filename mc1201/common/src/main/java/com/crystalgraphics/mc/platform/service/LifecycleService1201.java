package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * MC 1.20.x implementation of {@link CgLifecycleService}.
 * Delegates directly to {@link CgGraphicsLifecycle} — no callback registration needed.
 */
public final class LifecycleService1201 implements CgLifecycleService {

    @Override
    public void onContextInit(int width, int height) {
        CgGraphicsLifecycle.initContext(width, height);
    }

    @Override
    public void onContextDestroy() {
        CgGraphicsLifecycle.destroyContext();
    }

    @Override
    public void onResize(int width, int height) {
        CgGraphicsLifecycle.onResize(width, height);
    }

    /**
     * See {@link CgLifecycleService#onFrameRendered()}. Delegates to
     * {@link CgGraphicsLifecycle#tickFrame()} — shared logic for all three mc1201
     * loaders (forge/neoforge/fabric). Each loader is responsible for wiring this method
     * to its own once-per-frame event/callback (see "mc1201 Render Stage Events" in
     * {@code CrystalGraphics/AGENTS.md} for the pattern used by the existing
     * opaque/transparent world-render hooks — this needs an equivalent GUI-inclusive
     * event per loader, not yet wired as of this method's introduction).
     */
    @Override
    public void onFrameRendered() {
        CgGraphicsLifecycle.tickFrame();
    }
}
