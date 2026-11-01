package com.crystalgraphics.mc.legacy.platform.service;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.service.CgLifecycleService;

/**
 * Forge 1.8–1.12.2's {@link CgLifecycleService}, called from the mixins: context destroy to
 * {@link CgEngineLifecycle}, resize and the frame to {@link CgGraphicsLifecycle}.
 */
public final class LifecycleService implements CgLifecycleService {

    @Override public void onContextDestroy()          { CgEngineLifecycle.shutdown(); }
    @Override public void onResize(int w, int h)      { CgGraphicsLifecycle.onResize(w, h); }
    @Override public void onFrameRendered()           { CgGraphicsLifecycle.tickFrame(); }
}
