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
}
