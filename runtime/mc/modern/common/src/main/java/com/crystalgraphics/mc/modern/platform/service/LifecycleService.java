package com.crystalgraphics.mc.modern.platform.service;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * Modern implementation of {@link CgLifecycleService}: context destroy to {@link CgEngineLifecycle},
 * resize and the frame to {@link CgGraphicsLifecycle}.
 */
public final class LifecycleService implements CgLifecycleService {

    @Override
    public void onContextDestroy() {
        CgEngineLifecycle.shutdown();
    }

    @Override
    public void onResize(int width, int height) {
        CgGraphicsLifecycle.onResize(width, height);
    }

    /**
     * See {@link CgLifecycleService#onFrameRendered()}. Delegates to
     * {@link CgGraphicsLifecycle#tickFrame()}. No loader calls it: the tick runs from
     * {@code LifecycleModern.frameEnd()}, at each loader's post-GUI point.
     */
    @Override
    public void onFrameRendered() {
        CgGraphicsLifecycle.tickFrame();
    }
}
