package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * Modern implementation of {@link CgLifecycleService}.
 * Delegates directly to {@link CgGraphicsLifecycle} — no callback registration needed.
 */
public final class LifecycleService implements CgLifecycleService {

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
     * {@link CgGraphicsLifecycle#tickFrame()}. No loader calls it yet: the tick runs from
     * {@code FrameHooks.endFrame()}, which a GUI-only frame never reaches —
     * {@code runtime/mc/modern/common/AGENTS.md} § <i>Open</i>.
     */
    @Override
    public void onFrameRendered() {
        CgGraphicsLifecycle.tickFrame();
    }
}
